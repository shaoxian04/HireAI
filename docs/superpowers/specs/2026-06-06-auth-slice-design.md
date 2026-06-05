# Thin JWT Auth Slice — Design Spec

**Date:** 2026-06-06
**Status:** Approved (design); decisions locked
**Branch:** `feat/marketplace-spine` (continues the spine work)

## Goal

Make hard invariant **#5 (server-side identity from JWT)** runtime-enforced: derive the caller's identity from a validated JWT and require authentication on protected endpoints — without breaking the 127 existing tests. The agent result callback stays authenticated by its **dispatch token**, never JWT.

## Locked decisions

1. **Auth enforced by default.** The default (and `prod`) profile enforces JWT. A `test` profile bypasses auth (permitAll + `DevCurrentUserProvider`); the ~existing integration/controller tests get `@ActiveProfiles("test")`. The demo runs the default profile and is secured.
2. **Login only (no register endpoint).** A CLIENT and a BUILDER are seeded (with BCrypt password hashes + wallets) via a new Flyway migration; the demo logs in as them. This avoids an Auth→Wallet provisioning coupling.

## Scope

**In:** `POST /api/auth/login`; JWT issue/verify (HS256, jjwt); BCrypt password check; a minimal **User read aggregate** mapping the existing `users` table; `JwtAuthenticationFilter` + `JwtCurrentUserProvider`; `SecurityConfig` rewrite (stateless, authenticate by default); Flyway **V5** seed (CLIENT + BUILDER + wallets); `test`-profile bypass + `@ActiveProfiles("test")` on existing tests; new auth tests.

**Out (explicitly):** registration endpoint, refresh tokens, password reset, OAuth/social login, RBAC method-level rules beyond authenticated/anonymous (role is carried in the token and as an authority, but per-endpoint role gating is a later slice), account lockout, email verification.

## Components

All under `com.hireai`. Follows the interface + `impl/` convention; domain stays framework-free.

- **User read aggregate** (maps the existing `users` table — **no schema change**):
  - `domain/biz/user/model/UserModel` (id, email, passwordHash, role, active) + `domain/biz/user/enums/Role` (CLIENT/BUILDER/ADMIN).
  - `domain/biz/user/repository/UserRepository` — `Optional<UserModel> findByEmail(String email)`.
  - `infrastructure/repository/user/UserJpaEntity` + `UserRepositoryImpl`.
- **JWT port + impl:**
  - `application/port/security/JwtService` — `String issue(UUID userId, String role, Duration ttl)`; `JwtPrincipal verify(String token)` (throws `JwtInvalidException` on bad signature/expiry); `application/port/security/JwtPrincipal` record (userId, role).
  - `infrastructure/security/JjwtService` — HS256 via `io.jsonwebtoken:jjwt`; secret from `hireai.auth.jwt-secret` (env `AUTH_JWT_SECRET`, dev default ≥32 chars); TTL from `hireai.auth.jwt-ttl-seconds` (default 86400).
- **Auth application + controller:**
  - `application/biz/auth/AuthAppService` (+ `impl/`) — `login(LoginInfo) → AuthResult(token, userId, role)`. Looks up by email, verifies BCrypt, issues JWT. Wrong email/password → a single `BadCredentialsException`-style failure mapped to **401** (no user-enumeration leak).
  - `controller/biz/auth/AuthController` — `POST /api/auth/login` (`LoginRequest{email,password}` → `WebResult<LoginResponse{token,userId,role}>`).
- **Security wiring:**
  - `controller/config/JwtAuthenticationFilter` (OncePerRequestFilter) — reads `Authorization: Bearer`, `JwtService.verify`, sets a `UsernamePasswordAuthenticationToken` (principal = userId UUID, authority = `ROLE_<role>`). Missing/blank header → continue unauthenticated (the chain then 401s on protected routes).
  - `controller/config/JwtCurrentUserProvider implements CurrentUserProvider` (`@Profile("!test")`) — returns the principal UUID from the SecurityContext; throws if unauthenticated.
  - `DevCurrentUserProvider` → change `@Profile("!prod")` to `@Profile("test")`.
  - `SecurityConfig` — **two filter chains by profile.** Default/prod: stateless, csrf off, `permitAll` for `POST /api/auth/login`, `/api/agent-callbacks/**`, `/actuator/health`; `authenticated()` for everything else; add `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter`; 401 entry point. `test` profile: a permissive chain (`permitAll`). `BCryptPasswordEncoder` bean.
- **Seed migration `V5__seed_demo_users.sql`** — insert `client@hireai.local` (CLIENT) and `builder@hireai.local` (BUILDER) with BCrypt hashes of a documented demo password, fixed UUIDs, and a wallet row each (client wallet funded for the escrow demo). Idempotent-safe (fixed ids; runs once).

## Identity swap (why it's clean)

`CurrentUserProvider` already abstracts identity and every controller (`AgentController`, `TaskController`, `WalletController`) uses it — confirmed. `JwtCurrentUserProvider` just reads the JWT principal, so **no controller changes**. This is exactly the swap the existing `SecurityConfig`/`DevCurrentUserProvider` `TODO(auth)` notes anticipated.

## Data flow

```
POST /api/auth/login {email,password}
  → AuthAppService.login: UserRepository.findByEmail → BCrypt matches? → JwtService.issue(userId, role, ttl)
  → 200 {token, userId, role}   (any failure → 401, generic message)

Authenticated request: Authorization: Bearer <jwt>
  → JwtAuthenticationFilter.verify → SecurityContext(principal=userId, ROLE_x)
  → controller calls currentUserProvider.currentUserId() → JwtCurrentUserProvider returns userId
  → existing owner checks apply unchanged

Agent callback POST /api/agent-callbacks/{taskId}/result
  → permitAll in the chain; still gated by the dispatch token (401 on bad token) — UNCHANGED
```

## Invariants

- **#5 now runtime-enforced** — protected endpoints reject anonymous calls (401); identity comes only from the verified JWT; existing per-resource owner checks unchanged.
- **#6 unchanged** — the callback remains dispatch-token-authenticated; HTTPS/signed-token mechanics untouched.

## Testing

- **Unit:** `JjwtService` issue→verify round-trip + reject tampered/expired; `AuthAppServiceImpl` login success, wrong password, unknown email (both → generic 401 failure); `JwtAuthenticationFilter` sets the principal for a valid token and leaves the context empty for a missing/invalid one.
- **Integration (Testcontainers Postgres, `@EnabledIf` Docker):** seed-user login returns a token; calling a protected endpoint (e.g. `GET /api/agents`) with the token → 200, without it → 401; the agent callback still works token-only with no JWT.
- **Regression:** add `@ActiveProfiles("test")` to the existing integration + `@WebMvcTest` controller tests so the 127 stay green under the permissive `test` chain + `DevCurrentUserProvider`. Full suite must remain green.

## Risks & mitigations

- **Test ripple:** ~10 existing test classes need `@ActiveProfiles("test")`. Mechanical; the build verifies it. Mitigation: the plan enumerates them; a final full-suite run confirms zero regressions.
- **Two filter chains by profile:** ensure exactly one `SecurityFilterChain` is active per profile (`@Profile` on each `@Bean`/config) to avoid ambiguity.
- **Seed password in a migration:** the BCrypt hash is for a *documented demo* password on throwaway demo accounts; not a real secret. The JWT signing secret is env-overridable with a dev default (≥32 chars), never a real secret in git.
