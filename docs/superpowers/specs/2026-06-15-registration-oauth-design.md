# Registration Module + Google OAuth — Design

- **Date:** 2026-06-15
- **Status:** Approved (ready for implementation planning)
- **Branch:** `feat/marketplace-spine`
- **Author:** shaoxian04

## 1. Context

HireAI today has a **thin JWT auth slice** but **no self-registration**:

- `POST /api/auth/login` → BCrypt verify → HS256 JWT (24h TTL, carries a single `role` claim). This JWT is the single identity transport (hard invariant #5).
- `users` table: `id, email, password_hash (nullable), role (CLIENT|BUILDER|ADMIN), is_active, gmt_create, gmt_modified`. One role per user.
- Users exist **only via Flyway seeds** (`dev@hireai.local`, `client@hireai.local`, `builder@hireai.local`). There is no signup endpoint and no signup UI.
- Frontend: a login page + an auth context that stores the token in `localStorage` (`hireai.token` / `hireai.auth`); the `api()` client attaches the bearer and redirects to `/login` on 401.

**Goal:** let people **sign up** (email/password) **and** sign in with **Google**, both minting the same JWT the platform already trusts — and let a Client opt in to becoming a Builder.

## 2. Decisions

| # | Decision | Choice |
|---|----------|--------|
| D1 | OAuth provider | **Google** only (extensible to others via the identities table) |
| D2 | Client vs Builder model | **Dual-capability RBAC** — everyone is a `CLIENT`; `BUILDER` is an additive, opt-in role. One account can hold both. |
| D3 | Role storage | **`user_roles` join table** (proper many-to-many), replacing the single `users.role` column |
| D4 | OAuth architecture | **Backend-driven** (Spring Security OAuth2 Client) — backend completes the OAuth handshake, then mints **our own** HS256 JWT. OAuth never becomes a second identity transport. |
| D5 | Account linking | **Link by Google-verified email** — email/password and Google for the same person resolve to one account |
| D6 | OAuth token hand-off | **URL fragment** (`/auth/callback#token=…`), scrubbed from history via `replaceState` |
| D7 | Wallet provisioning | Every new user gets a **zero-balance wallet** in the same transaction as the user + role rows |

### Why dual-capability (D2)

Real marketplaces (Uber, Airbnb, Fiverr) let one account hold both sides. A role *flip* (CLIENT→BUILDER) would create dead-ends: a builder could no longer submit tasks, and their prior client data (tasks, reviews, wallet history) would sit behind a surface they can't reach. The wallet belongs to a *user*, not a role, so dual-capability also unifies the money path — a builder's earnings and a client's top-ups live in one wallet. This is the more defensible engineering story and composes cleanly with invariant #5.

## 3. Goals / Non-goals

**Goals**
- Email/password self-registration creating a `CLIENT` + wallet, returning a JWT.
- Google OAuth login that creates/links accounts and mints our JWT.
- "Become a Builder" upgrade that adds the `BUILDER` role and re-issues the token.
- Frontend: register page, Google buttons on login + register, OAuth callback, become-builder page, Client/Builder nav switcher.

**Non-goals (this iteration)**
- Refresh tokens (current model is access-token only; unchanged).
- Email verification for password signups, password reset, MFA.
- Providers other than Google (schema leaves room; no implementation).
- Admin self-registration (ADMIN remains seed/operator-assigned).

## 4. Data model

Three forward-only migrations, `V10`–`V12`. **`V1` and `V5` are not edited** (Flyway checksums); they still write `users.role`, and `V10` reads it before dropping it. This works for both fresh and existing databases because `V10` runs after `V5`.

### V10 — `user_roles`
```sql
CREATE TABLE user_roles (
    user_id      UUID NOT NULL REFERENCES users(id),
    role         TEXT NOT NULL CHECK (role IN ('CLIENT','BUILDER','ADMIN')),
    gmt_create   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);

-- Backfill from the legacy single-role column, then retire it.
INSERT INTO user_roles (user_id, role)
SELECT id, role FROM users;

ALTER TABLE users DROP COLUMN role;
```

### V11 — `user_identities` (OAuth links)
```sql
CREATE TABLE user_identities (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users(id),
    provider         TEXT NOT NULL,            -- e.g. 'google'
    provider_subject TEXT NOT NULL,            -- the provider's stable 'sub'
    email_at_link    TEXT,
    gmt_create       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_subject)
);
```
A table (not columns on `users`) so a second provider is additive later.

### V12 — `users.display_name`
```sql
ALTER TABLE users ADD COLUMN display_name TEXT;
```
Populated from the signup form or Google's profile name. `password_hash` stays nullable (OAuth-only accounts have none).

### Wallet provisioning
Registration and first-time OAuth account creation both create a zero-balance wallet via the existing Wallet aggregate, in the **same transaction** as the user + role rows. If wallet creation fails, the whole registration rolls back (no orphan users, no dispatch-before-escrow risk).

## 5. Backend design

Layering follows the existing DDD conventions (`controller → application → domain ← infrastructure`).

### 5.1 Role model refactor (lands first)
Everything downstream depends on roles becoming a **set**:
- `UserModel`: `Role role` → `Set<Role> roles`.
- `UserJpaEntity` / repository: read roles from `user_roles` (element collection or a small join mapping); drop the `role` column mapping.
- `JwtService.issue(...)`: emit a `roles` claim as a **list**; `JwtPrincipal` carries `Set<Role>`.
- `JwtAuthenticationFilter`: set one `ROLE_*` authority per role.
- `AuthAppServiceImpl` (login): load the role set; otherwise unchanged (same non-enumerating failure).
- **Ownership checks unchanged** — still derived from the JWT subject (user ID), still per-user. Roles gate *surfaces*, not *ownership*.
- `LoginResponse`: `role: String` → `roles: string[]`.

### 5.2 `POST /api/auth/register` (permitAll)
- Body: `{ email, password, displayName }`, bean-validated (email format ≤320, password min length, displayName optional ≤120).
- Duplicate email → **409** with a clean code (not a 500, not user-enumeration on the *login* path — register may legitimately report "email already registered").
- Creates user + `CLIENT` role + wallet (one transaction), BCrypt-hashes the password, mints a JWT.
- Returns the standard `LoginResponse` (`token`, `userId`, `roles`).

### 5.3 Google OAuth (`spring-boot-starter-oauth2-client`)
- Frontend links to Spring's default authorization endpoint `/oauth2/authorization/google`; Google redirects to `/login/oauth2/code/google`.
- A custom `OAuth2AuthenticationSuccessHandler`:
  1. Requires `email_verified == true` from Google (email-based linking is only safe because Google verifies it). Otherwise reject.
  2. Resolves the account, in order:
     - by `(provider='google', provider_subject=sub)` → existing linked user;
     - else by `email` → **link** (insert a `user_identities` row pointing at the existing user);
     - else **create** a new `CLIENT` + wallet + identity row.
  3. Mints our HS256 JWT and redirects to the frontend callback (§5.5).
- Resolution/linking/creation logic lives in an application service so it is unit-testable with a mocked `OAuth2User`.

### 5.4 `POST /api/auth/become-builder` (authenticated)
- Adds `BUILDER` to the caller's `user_roles` (idempotent — adding twice is a no-op).
- Re-issues a JWT carrying the expanded role set.
- Payload: accept-terms flag + optional builder display name. Agents themselves are still registered via the existing Module 2 flow; this endpoint only unlocks the builder surface.

### 5.5 Security config
- Add a **dedicated, higher-precedence `SecurityFilterChain`** scoped (via `securityMatcher`) to `/oauth2/**` and `/login/oauth2/**`, running `.oauth2Login()` with the custom success handler and allowing the session the handshake needs. The existing **stateless JWT chain is untouched** for all `/api/**`.
- `permitAll` additions: `POST /api/auth/register`, the OAuth handshake paths. `POST /api/auth/login` and `/api/agent-callbacks/**` stay as-is.
- `become-builder` requires a valid JWT.
- Secrets `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` via env (`application.yml` placeholders), never committed.

## 6. OAuth token hand-off (D6 — fragment)

After Google succeeds the backend has a JWT but the browser is mid-redirect (and the frontend may be a different origin in deployment). The success handler redirects to:

```
{frontend}/auth/callback#token=<jwt>
```

The `/auth/callback` page reads `location.hash`, stores the token + decoded roles, then `history.replaceState` scrubs the fragment from the URL/history, and routes by role. Fragments are not sent in `Referer` headers or server logs. (Hardened alternative — a one-time exchange code — was considered and deferred; not needed over HTTPS for this project.)

## 7. Frontend design

- **`/register` page**: email + password + name; submit calls `register()`. A **"Continue with Google"** button links to `/oauth2/authorization/google`. The login page gets the same Google button.
- **`/auth/callback` route**: consumes the fragment token (per §6), stores it, routes by role.
- **Auth context** (`lib/auth.tsx`): `roles: Role[]`, `hasRole(r)`, an `activeSurface` (client | builder) for the switcher, plus `register()` and `becomeBuilder()` methods. `login()` and storage keys unchanged except the session now holds `roles[]`.
- **Route guards** (`RequireAuth`): check **has role X**, not **is role X**.
- **`/client/become-builder` page**: CTA + accept-terms → `becomeBuilder()` → updates stored token/roles → switcher appears.
- **Nav switcher**: rendered only when the user holds both `CLIENT` and `BUILDER`; toggles `activeSurface` and routes to `/client` or `/builder`.
- **Types** (`lib/types.ts`): `LoginResponse.role` → `roles: Role[]`; add `RegisterRequest`.

## 8. Error handling & security

- Duplicate email on register → 409 with a typed code; weak/invalid password → 400 with a field message.
- OAuth without `email_verified` → rejected.
- Login failures remain **non-enumerating** (existing behavior); register may report "email already registered" since that endpoint's purpose differs.
- **Invariant #5 preserved**: identity is always the JWT subject; roles only gate surfaces; ownership checks stay per-user.
- Token in URL mitigated by fragment + `replaceState` (§6).
- Secrets via env only.

## 9. Testing (TDD)

- **Unit**: register service (dup email, BCrypt hashing, wallet provisioning, rollback on wallet failure); OAuth account-resolution (all three branches: existing-link / link-by-email / new); become-builder (idempotent add + token re-issue); JWT issue/verify with a role **set**.
- **Integration** (Testcontainers Postgres): `/register`, `/login` round-trip with the new token shape, `/become-builder` re-issues an expanded token; OAuth success handler with a mocked `OAuth2User`. (Skip automatically when no Docker — existing behavior.)
- **Frontend** (vitest): register form validation/submit, callback token handling, switcher visibility logic, `hasRole` guards.
- **E2E** (Playwright): email/password signup → submit a task; become-builder → register an agent. **Real Google OAuth is stubbed** — Google's consent screen can't be driven in CI; this is called out, not faked.

## 10. Sequencing

1. Schema migrations `V10`–`V12`.
2. Backend role refactor (single → set) across model/JPA/JWT/filter/login — **lands first**, everything depends on it.
3. `POST /api/auth/register` + wallet provisioning.
4. Google OAuth (client config + success handler + account resolution service).
5. `POST /api/auth/become-builder`.
6. Frontend: register page + Google buttons → `/auth/callback` → become-builder + switcher.
7. Tests at each step (TDD).

## 11. Resolved questions

- Provider: **Google** (D1).
- Role model: **dual-capability `user_roles`** (D2/D3).
- OAuth flow: **backend-driven, mints our JWT** (D4).
- Linking: **by verified email** (D5).
- Token hand-off: **fragment** (D6).
- Wallet: **provisioned at registration** (D7).
