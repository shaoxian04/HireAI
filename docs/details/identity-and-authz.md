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

## Two security chains (exactly one active per profile) — `SecurityConfig`

- **Default / prod / dev** — `securedFilterChain` (`@Profile("!test")`): stateless, CSRF off,
  **authenticated by default**. Public only: `POST /api/auth/login`, `/api/agent-callbacks/**`,
  `/actuator/health`. `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`;
  an unauthenticated request to a protected route gets **401** (no redirect).
- **`test`** — `permissiveFilterChain` (`@Profile("test")`): `permitAll`. Existing integration /
  `@WebMvcTest` tests carry `@ActiveProfiles("test")` so they run permissively with `DevCurrentUserProvider`.

## Login & tokens

`POST /api/auth/login {email,password}` → `AuthAppService.login`: look up the user, **BCrypt**-verify the
password, and issue an **HS256 JWT** via `JjwtService` (secret `hireai.auth.jwt-secret`, TTL
`hireai.auth.jwt-ttl-seconds`, default 86400s). Claims: `sub` = userId, `role`. Any failure (unknown email
or wrong password) collapses to one generic **401** — no user enumeration. `JwtAuthenticationFilter` sets
the principal to the userId (UUID) and a single authority `ROLE_<role>`.

## The Agent callback is NOT JWT-authenticated

`POST /api/agent-callbacks/{taskId}/result` is `permitAll` in the chain because the **Agent** (not a
logged-in user) calls it. It is gated instead by the short-lived **signed dispatch token** (Hard
Invariant #6): the callback app service verifies the token — its `taskId` and `agentVersionId` must match
the task's assignment — and returns **401** on an invalid / expired / mismatched token. Keep the two token
systems distinct: the user **JWT** secures the UI/API; the **dispatch token** secures Agent I/O.

## Seed users (Flyway `V5`)

`client@hireai.local` (CLIENT, wallet pre-funded) and `builder@hireai.local` (BUILDER); password
`DemoPass123!` (throwaway demo credentials, BCrypt-hashed in the migration).

## Status / gaps

- **RBAC is not enforced per-endpoint yet.** The role rides in the token and is exposed as `ROLE_<role>`,
  but the secured chain only does `anyRequest().authenticated()` — no `hasRole` / `@PreAuthorize` gating.
  Add it when role-scoped endpoints are needed.
- No **fail-fast** guard if the `test` profile is launched in prod (it would disable auth).
- Frontend token storage is `localStorage` (demo-grade; an httpOnly cookie is the hardening).
