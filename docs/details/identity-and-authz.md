# Identity & authorization

How HireAI enforces **Hard Invariant #5 (server-side identity)**. Read before touching auth, `SecurityConfig`, or anything that derives the current user. Schema: [`data-model.md`](data-model.md). The machine channel's behaviour: [`programmatic-channel.md`](programmatic-channel.md).

## The seam: `CurrentUserProvider`

Every controller gets the caller's id from `currentUser.currentUserId()`, **never** from a path or body.
- **`JwtCurrentUserProvider`** (`@Profile("!test")`) returns the UUID principal in the `SecurityContext`, and throws if there is none. API-key requests set the same principal, so this covers them too.
- **`DevCurrentUserProvider`** (`@Profile("test")`) returns a fixed dev user, so business-logic tests don't mint tokens.

The chain authenticates; **app services** then authorize the specific resource (a builder acts only on agents they own, a client only on their own tasks and wallet). A foreign resource surfaces as `NOT_FOUND`, not a denial, so existence doesn't leak.

## Roles

Stored in the `user_roles` join table; a user may hold several of `CLIENT`, `BUILDER`, `ADMIN`. The JWT `roles` claim is a sorted array of names, and `sub` is the userId. `JwtAuthenticationFilter` maps each name to a `ROLE_<name>` authority. `ADMIN` has no public path: it is seeded only.

## Security chains (`SecurityConfig`)

1. **`oauthFilterChain`** (`@Order(1)`, `!test`, `@ConditionalOnProperty hireai.auth.oauth2.enabled=true`): scoped to `/oauth2/**` and `/login/oauth2/**`, and allows the session Spring's authorization-request repository needs. Loads only under the `oauth` profile.
2. **`securedFilterChain`** (`@Order(2)`, `!test`): stateless, CSRF off.
   - **Public:** `POST /api/auth/login`, `POST /api/auth/register`, `/api/agent-callbacks/**`, `/api/arbitration-callbacks/**`, `/actuator/health`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`. The two callback routes have their own credentials (see below).
   - **Everything else** needs a valid JWT or API key plus a role. See the role rules below.
3. **`permissiveFilterChain`** (`test`): `permitAll`, for tests with `@ActiveProfiles("test")`.

### Role rules on `securedFilterChain`

| Route | Allowed |
|---|---|
| `POST /api/tasks`, `/api/tasks/direct`; `GET /api/tasks`, `/api/tasks/*`, `/api/tasks/*/result`, `/api/tasks/*/validation`, `/api/tasks/*/attempts` | `CLIENT`, `API_CLIENT` |
| `GET /api/webhooks/deliveries`, `POST /api/webhooks/deliveries/*/redeliver` | `CLIENT`, `API_CLIENT` |
| `POST /api/tasks/*/accept`, `/reject` | `CLIENT`, `BUILDER`, `ADMIN` (human-only) |
| `/api/keys/**`, `/api/webhooks/subscription/**` | `CLIENT` (JWT-only: a leaked key cannot mint keys or repoint a callback) |
| `/api/admin/**` | `ADMIN` |
| `POST /api/agents/*/rotate-secret` | `BUILDER` (JWT-only; the app service then requires ownership, a foreign agent is `NOT_FOUND`) |
| `GET /api/catalogue/**` | `CLIENT`, `BUILDER`, `ADMIN`, `API_CLIENT` |
| Anything else | `CLIENT`, `BUILDER`, `ADMIN` |

Consequences worth knowing:
- The catalogue needs a role; "public" in these docs means "any signed-in role", not anonymous.
- The `GET /api/tasks/*` wildcard also matches `GET /api/tasks/match-preview`, so API keys can reach it, while a BUILDER-only or ADMIN-only JWT cannot.
- The final rule only *adds* the API-key lockout; every human already holds one of those roles. So most builder routes are not gated to `BUILDER`: a CLIENT passes the chain and only ownership applies. See [Known gaps](build-status.md#known-gaps).

### Denied means 401 in the full app

The chain sets an `authenticationEntryPoint` (401) and no `accessDeniedHandler`, so an authenticated caller lacking a role gets **401, not 403**. `@WebMvcTest` slices render the same denial as 403. Assert denied status against the full app. Why, and what a fix must do: [post-mortem](../post-mortem/2026-07-17-api-key-lockout-401-vs-403.md).

## Login, registration, become-builder

- **`POST /api/auth/login {email,password}`** (`AuthAppService.login`): checks the account is active, BCrypt-verifies, and issues an HS256 JWT via `JjwtService` (`hireai.auth.*`). Any failure collapses to a generic 401, so there is no user enumeration.
- **`POST /api/auth/register {email,password,displayName}`**: a duplicate email is 409 `EMAIL_ALREADY_REGISTERED`. Creates a `CLIENT` user and zero-balance wallet atomically and returns `{token, userId, roles}`.
- **`POST /api/auth/become-builder {acceptTerms:true}`**: idempotently adds `BUILDER` (`UserRepository.addRole`, `ON CONFLICT DO NOTHING`) and re-issues the token.

## Google OAuth (flag-gated)

Disabled by default. `SPRING_PROFILES_ACTIVE=oauth` loads `application-oauth.yml`, which has no defaults for `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`, so missing credentials fail at startup. Redirect targets: `OAUTH2_SUCCESS_REDIRECT_URL`, `OAUTH2_FAILURE_REDIRECT_URL`.

**Handshake:**
1. The frontend generates a nonce, keeps it in `sessionStorage`, and goes to `<backend>/oauth2/authorization/google?cb_nonce=<nonce>`. `NonceCarryingAuthorizationRequestResolver` stores it in the session.
2. Spring completes the Google exchange at `/login/oauth2/code/google`.
3. `OAuth2AuthenticationSuccessHandler` resolves the account, mints the JWT, and redirects to `<success-url>#token=<jwt>&nonce=<nonce>`. The fragment keeps the token out of Referer and logs.
4. `/auth/callback` accepts the token only if the nonce matches, which binds the callback to the browser that started it (login-CSRF guard). Any failure goes to `<failure-url>?error=oauth`.

**Account resolution** (`OAuthAppServiceImpl.loginWithOAuth`):
- Requires `email_verified`.
- Existing `user_identities` link: log in.
- **Local account with the same email and no link: REFUSE.** Local emails aren't verified at registration, so silent linking would let someone who pre-registered a victim's email capture the victim's Google sign-in. The rule lives in `OAuthAccountLinkingDomainService`. An explicit re-authenticated link flow is a follow-up.
- No link and no account: create `CLIENT`, wallet and link atomically.
- Inactive account: reject.

## API keys

`ApiKeyAuthenticationFilter` reads `Authorization: ApiKey <raw>` or `X-API-Key: <raw>` and resolves it through `ApiKeyAuthService` (hash, `findActiveByHash`, throttled `last_used_at` bump). It sets the same token shape as the JWT filter (principal = owning user's UUID), so ownership checks work unchanged. It differs in two ways:
- a single `ROLE_API_CLIENT` authority, never a human role;
- an `ApiKeyContext(keyId, spendCap, dailySpendCap)` as the `Authentication`'s details, exposed by `CurrentApiKeyProvider` (`HttpCurrentApiKeyProvider`, `!test`; `NoApiKeyProvider`, `test`). `SubmitOrchestrationAppService` uses it to apply caps and attribution.

**Both credentials on one request:** the API-key filter runs first, then the JWT filter, which overwrites unconditionally. So a valid JWT always wins and the request is treated as human, with no caps or attribution. An invalid JWT leaves the key's authentication standing.

Lookup checks key status only, so a deactivated user's keys keep authenticating. See [Known gaps](build-status.md#known-gaps).

Key endpoints (`ApiKeyController`) are owner-scoped: revoking a key you don't own is `NOT_FOUND`.

## Other credentials

Keep these distinct from the user JWT:

| Credential | Secures | Check |
|---|---|---|
| **Signed dispatch token** (Inv #6) | `POST /api/agent-callbacks/{taskId}/result`, called by the Agent | `taskId` and `agentVersionId` must match the assignment; invalid, expired (see [token lifetime](matching.md#the-workorder)) or mismatched is 401 |
| **Shared bearer secret** (`ARBITRATION_CALLBACK_SECRET`) | `POST /api/arbitration-callbacks/{disputeId}/ruling`, called by the arbitrator | Constant-time compare in `ArbitrationCallbackController`; mismatch is 401 |
| **HMAC signature** (outbound) | Push webhooks to a client's callback; the platform authenticates itself to the client | See [`programmatic-channel.md` §6](programmatic-channel.md#6-push-webhooks) |

## Seed users

`client@hireai.local` (CLIENT, funded wallet), `builder@hireai.local` (BUILDER) and `admin@hireai.local` (ADMIN), all with password `DemoPass123!`. These are throwaway demo credentials seeded by Flyway. Seeded accounts need not hold `CLIENT`: only self-registered users do.

## Known limits

- Role gating is coarse, as above; there is no `@PreAuthorize`, and ownership lives in app services.
- No fail-fast guard if the `test` profile is launched in production, which would disable auth.
- The frontend stores the token in `localStorage` (demo-grade; an httpOnly cookie is the hardening).
