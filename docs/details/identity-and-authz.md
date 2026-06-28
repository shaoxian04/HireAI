# Identity & authorization

How HireAI enforces **Hard Invariant #5 (server-side identity from JWT)**. Distilled from the auth slice;
read before touching auth, `SecurityConfig`, or anything that derives the current user.

## The seam — `CurrentUserProvider`

Every controller obtains the caller's id via `currentUser.currentUserId()` — **never** from a path or
body. Two implementations, selected by profile:
- **`JwtCurrentUserProvider`** (`@Profile("!test")`) — returns the UUID principal placed in the
  `SecurityContext` by the JWT filter; throws if there is no authenticated principal.
- **`DevCurrentUserProvider`** (`@Profile("test")`) — a fixed dev user, so business-logic tests don't mint tokens.

Per-resource ownership is then checked in the **app services** (a builder acts only on agents they own;
a client only on their own tasks/wallet). Identity (#5) and ownership are separate concerns: the chain
authenticates, the app service authorizes the specific resource.

## RBAC — `user_roles` join table + `roles` JWT claim

Roles are stored in a `user_roles` join table (not a single `users.role` column — see migration `V10`).
A user may hold multiple roles simultaneously (`CLIENT`, `BUILDER`, or both). The JWT carries a `roles`
claim (a JSON array of role names); `JwtAuthenticationFilter` maps each name to a `ROLE_<name>`
Spring authority. The `sub` claim is still the userId (UUID) — `CurrentUserProvider` reads from there.
Hard Invariant #5 is fully intact: ownership checks remain per-user from the JWT subject.

New schema objects from the RBAC work:
- **`user_roles`** — join table (`user_id`, `role`) with a `UNIQUE (user_id, role)` guard; backfilled from
  the old `users.role` column then the column is dropped (`V10`).
- **`user_identities`** — OAuth identity links (`user_id`, `provider`, `provider_subject`, `email`) (`V11`).
- **`users.display_name`** — added in `V12`.

## Three security chains (profile/flag-gated)

All three live in `SecurityConfig`:

1. **`oauthFilterChain`** (`@Order(1)`, `@Profile("!test")`, `@ConditionalOnProperty hireai.auth.oauth2.enabled=true`)  
   Scoped to `/oauth2/**` and `/login/oauth2/**` only. Allows the session that Spring's
   authorization-request repository needs. Loads **only** when the `oauth` profile is active.

2. **`securedFilterChain`** (`@Order(2)`, `@Profile("!test")`) — stateless, CSRF off, JWT-authenticated.  
   Public routes: `POST /api/auth/login`, `POST /api/auth/register`, `/api/agent-callbacks/**`,
   `/actuator/health`. Everything else requires a valid JWT. `JwtAuthenticationFilter` runs before
   `UsernamePasswordAuthenticationFilter`; an unauthenticated request to a protected route returns **401**.

3. **`permissiveFilterChain`** (`@Profile("test")`) — `permitAll`. Existing integration / `@WebMvcTest`
   tests carry `@ActiveProfiles("test")` so they run permissively with `DevCurrentUserProvider`.

## Login & tokens

`POST /api/auth/login {email,password}` → `AuthAppService.login`: look up the user, check active,
**BCrypt**-verify the password, and issue an **HS256 JWT** via `JjwtService` (secret
`hireai.auth.jwt-secret`, TTL `hireai.auth.jwt-ttl-seconds`, default 86400 s). Claims: `sub` = userId,
`roles` = sorted list of role names. Any failure (unknown email, wrong password, inactive account)
collapses to a generic **401** — no user enumeration. `JwtAuthenticationFilter` sets the principal to
the userId and maps each role name to one `ROLE_<name>` authority.

## Email/password registration

`POST /api/auth/register {email,password,displayName}` (`permitAll`) → `AuthAppService.register`:
- Checks for duplicate email — throws `EmailAlreadyRegisteredException` → **HTTP 409** with code
  `EMAIL_ALREADY_REGISTERED`.
- BCrypt-hashes the password, creates a `CLIENT` user + a zero-balance wallet **atomically** in one
  transaction (`UserModel.newClient`).
- Issues a JWT and returns `{token, userId, roles}` (same `LoginResponse` shape as `/login`).

## Become a builder — `POST /api/auth/become-builder`

JWT-gated (must be logged in). Body: `{acceptTerms:true}`. Identity is read from `CurrentUserProvider`
(Hard Invariant #5 — body carries no user id). Idempotently adds the `BUILDER` role via
`UserRepository.addRole` (guarded by the `UNIQUE (user_id, role)` constraint) and re-issues an expanded
token containing both roles. Returns `{token, userId, roles}`.

## Google OAuth (flag-gated)

OAuth is **disabled by default**. Enable by activating the `oauth` Spring profile
(`SPRING_PROFILES_ACTIVE=oauth`) which loads `application-oauth.yml`. That file activates
`hireai.auth.oauth2.enabled=true` and wires the Google client registration — it intentionally
**has no defaults for the credentials** (`${GOOGLE_CLIENT_ID}` / `${GOOGLE_CLIENT_SECRET}`), so the app
fails fast if the creds are missing rather than starting silently broken.

**Handshake flow:**
1. Frontend links to `<backend>/oauth2/authorization/google` (Spring initiates the Google OAuth dance).
2. Google redirects to `<backend>/login/oauth2/code/google` (Spring exchanges the code).
3. `OAuth2AuthenticationSuccessHandler` resolves the local account and mints our JWT.
4. Redirects to `${OAUTH2_SUCCESS_REDIRECT_URL:http://localhost:3000/auth/callback}#token=<jwt>`.
   The token is in the **URL fragment** — never sent to any server in Referer or logs.
5. On any error: redirects to `${OAUTH2_FAILURE_REDIRECT_URL:http://localhost:3000/login}?error=oauth`.

**Account-resolution logic** (in `OAuthAppServiceImpl.loginWithOAuth`):
- Requires `email_verified`; rejects unverified emails immediately.
- Existing identity link (`user_identities` row for this `provider`/`subject`) → load and log in.
- A pre-existing **local account with the same email but no link → REFUSE** (no silent linking).
  Account-takeover guard: local emails are **not** independently verified (registration does not prove
  ownership), so silently linking would let an attacker who pre-registered a victim's email capture the
  victim's later Google sign-in. The rule lives in the framework-free `OAuthAccountLinkingDomainService`;
  the user must sign in with their password first (an explicit, re-authenticated link flow is a follow-up).
- No link and no account for the email → create a new `CLIENT` + wallet + identity link atomically.
- Inactive account → reject (`Account is disabled`).

**New environment variables for OAuth:**

| Variable | Default | Purpose |
|---|---|---|
| `GOOGLE_CLIENT_ID` | _(required when oauth profile active)_ | Google OAuth client ID |
| `GOOGLE_CLIENT_SECRET` | _(required when oauth profile active)_ | Google OAuth client secret |
| `OAUTH2_SUCCESS_REDIRECT_URL` | `http://localhost:3000/auth/callback` | Frontend callback page |
| `OAUTH2_FAILURE_REDIRECT_URL` | `http://localhost:3000/login` | Frontend login on failure |

## The Agent callback is NOT JWT-authenticated

`POST /api/agent-callbacks/{taskId}/result` is `permitAll` in the chain because the **Agent** (not a
logged-in user) calls it. It is gated instead by the short-lived **signed dispatch token** (Hard
Invariant #6): the callback app service verifies the token — its `taskId` and `agentVersionId` must match
the task's assignment — and returns **401** on an invalid / expired / mismatched token. Keep the two token
systems distinct: the user **JWT** secures the UI/API; the **dispatch token** secures Agent I/O.

## Seed users (Flyway `V5`)

`client@hireai.local` (CLIENT, wallet pre-funded) and `builder@hireai.local` (BUILDER); password
`DemoPass123!` (throwaway demo credentials, BCrypt-hashed in the migration). Both are seeded with rows
in `user_roles` (not the old `role` column, which was dropped in `V10`).

## Status / gaps

- **RBAC is not enforced per-endpoint yet.** Roles ride in the token and are exposed as `ROLE_*`
  authorities, but the secured chain only does `anyRequest().authenticated()` — no `hasRole` /
  `@PreAuthorize` gating. Add it when role-scoped endpoints are needed.
- No **fail-fast** guard if the `test` profile is launched in prod (it would disable auth).
- Frontend token storage is `localStorage` (demo-grade; an httpOnly cookie is the hardening).
