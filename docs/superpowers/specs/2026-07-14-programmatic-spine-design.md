# Programmatic submission spine (Phase 3) — design

**Date:** 2026-07-14
**Status:** Approved (design) — ready for implementation plan
**Branch:** `feat/programmatic-spine` (off `feat/shortlist-selection` HEAD, to reuse the Phase-2 `Modal` primitive for the key-reveal UI)
**Source of truth:** `docs/programmatic-task-submission.md` — this spec is the **Phase-3 slice** of that feature (the "programmatic spine"). Push webhooks (Phase 4) and MCP + OpenAPI (Phase 5) are explicitly deferred.
**Roadmap:** Phase 1 (scored matcher + reliability sweepers) and Phase 2 (shortlist selection) are built; this is Phase 3.

## Context

Today a task can only be submitted interactively through the web app, authenticated by a user JWT. Phase 3 adds a **programmatic submission channel**: an external client agent submits and tracks tasks via an **API key**, over the *unchanged* submission/escrow/routing/settlement core. It is delivered as **new adapters at the edges** — an API-key auth filter, idempotency, a per-key spend cap, and a key-management surface — reusing the existing `CurrentUserProvider` identity seam.

The key insight (verified against current code): `JwtCurrentUserProvider.currentUserId()` only requires the `SecurityContext` principal to be a user `UUID`. So an API-key filter that sets the **same** principal makes every existing controller, ownership check, and settlement path work unchanged. Invariant #5 stays intact.

## Locked decisions

| # | Decision | Choice |
|---|----------|--------|
| 1 | Slice scope | Spine **+ per-key spend cap** |
| 2 | Key management auth | **JWT-only** — an API key cannot create/revoke keys (a leaked key can't self-perpetuate) |
| 3 | Key authority | **Submit-scoped** — a key may only submit/track/settle tasks; not wallet, storefront, key-mgmt, admin |
| 4 | Idempotency | **Honored when present** — optional `Idempotency-Key`, owner-scoped dedup; conflicting payload → 409 |
| 5 | Spend-cap semantics | Two independent optional per-key caps: **concurrent frozen escrow** (max in-flight) **+ daily spend** (max committed per rolling 24h, by submission time, regardless of later outcome) |

## Goals
- Let a client agent submit (routed `POST /api/tasks` and direct `POST /api/tasks/direct`) and track/settle tasks with an API key.
- API keys: create (reveal raw key once), list, revoke — from the client dashboard, hashed at rest, with an optional per-key spend cap.
- Idempotent submission (owner-scoped) so machine retries never double-freeze escrow (protects Invariant #1).

## Non-goals (deferred)
- Push webhooks + HMAC signing + SSRF guard + `webhook_deliveries` log (**Phase 4**).
- MCP server facade + OpenAPI document (**Phase 5**).
- Dedicated key **rotation-with-grace** endpoint (rotate = revoke + create, via the UI, for now).
- OAuth2 client-credentials tier; per-endpoint scope *beyond* the submit/track/settle allow-list.
- Any change to the money path, matcher, settlement, or task lifecycle.

## Architecture

```
 Human (browser) ──JWT──────────┐
                                ├─► [CurrentUserProvider → principal = user UUID] ─► Task/Wallet/Routing/Settlement core (UNCHANGED)
 Client agent (REST) ─API key───┘   + [CurrentApiKeyProvider → {keyId, spendCap} when API-key auth]
                                            │
                             idempotency guard · spend-cap guard · attribution  (new, at the submit edge)
```

`ApiKeyAuthenticationFilter` runs in the secured chain alongside `JwtAuthenticationFilter`. Both are "set the principal if a valid credential is present, else pass through," so they compose. The submit edge gains three guards (idempotency, spend-cap, attribution); the core is untouched.

## Data model — Flyway **V25**, three additive tables (money tables untouched)

**`api_keys`**
- `id UUID PK`, `user_id UUID NOT NULL` (owner)
- `key_hash TEXT NOT NULL UNIQUE` — hex SHA-256 of the raw key (keys are high-entropy → fast hash + O(1) lookup; bcrypt is unnecessary and too slow for per-request auth)
- `display_prefix TEXT NOT NULL` — e.g. `hk_live_a1b2c3` (identifies the key in the UI; not enough to reconstruct)
- `name TEXT` — human label
- `spend_cap NUMERIC(18,2) NULL` — max **concurrent frozen escrow** for this key; `NULL` = uncapped
- `daily_spend_cap NUMERIC(18,2) NULL` — max credits this key may commit per **rolling 24h** (by submission time); `NULL` = uncapped
- `status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED'))`
- `last_used_at TIMESTAMPTZ NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `revoked_at TIMESTAMPTZ NULL`
- index on `user_id`

**`idempotency_keys`**
- `owner_id UUID NOT NULL`, `idempotency_key TEXT NOT NULL`, `request_fingerprint TEXT NOT NULL` (SHA-256 of the normalized submit payload), `task_id UUID NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- **`UNIQUE (owner_id, idempotency_key)`** — the concurrency arbiter (mirrors `settlements.task_id UNIQUE`, V14)

**`api_key_task`** (attribution, keeps the Task aggregate untouched — soft `task_id` reference like `validation_reports`)
- `task_id UUID PK`, `api_key_id UUID NOT NULL`, `budget NUMERIC(18,2) NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- index on `api_key_id`

## Components

### 1. API-key aggregate + generation (domain, framework-free)
`ApiKeyModel` aggregate; issuance via a domain service returning `IssuedApiKey(model, rawKey)`:
- raw key = `hk_live_` + 32 bytes `SecureRandom` → URL-safe base64 (no padding).
- `key_hash` = hex `SHA-256(rawKey)`; `display_prefix` = first 14 chars of `rawKey`.
- The **raw key is returned once** to the app service (to show the user) and **never stored**.
- `revoke()` transitions ACTIVE→REVOKED, stamps `revoked_at`.

### 2. `ApiKeyAuthenticationFilter` (controller layer, mirrors `JwtAuthenticationFilter`)
- Reads the key from `Authorization: ApiKey <raw>` **or** `X-API-Key: <raw>`.
- Calls an application port `ApiKeyAuthService.authenticate(rawKey) → Optional<ApiKeyPrincipal{userId, keyId, spendCap}>`: hash → `ApiKeyRepository.findActiveByHash` → ACTIVE only.
- On success: sets `UsernamePasswordAuthenticationToken(userId, null, [ROLE_API_CLIENT])` with **`details = ApiKeyContext{keyId, spendCap}`**; best-effort bumps `last_used_at` (throttled: only if null or older than ~1 min).
- On absent/invalid/revoked key: leaves the context unauthenticated → chain returns **401**. Never writes a response itself.

### 3. `CurrentApiKeyProvider` seam (controller layer, mirrors `CurrentUserProvider`)
- `Optional<ApiKeyContext> current()` — reads `authentication.getDetails()`; present iff the request was API-key authenticated (JWT requests → empty). Prod impl `@Profile("!test")`; test impl returns empty.
- The **controller** reads it (alongside `CurrentUserProvider` and the `Idempotency-Key` header) to assemble the `SubmitContext` it passes to the orchestration app service — the app layer never touches the `SecurityContext`.

### 4. Submit-scope enforcement (`SecurityConfig` change)
Replace `anyRequest().authenticated()` with a **default-deny allow-list** so `ROLE_API_CLIENT` only reaches submit/track/settle:
```
.requestMatchers(POST, "/api/tasks", "/api/tasks/direct").hasAnyRole("CLIENT","API_CLIENT")
.requestMatchers(GET,  "/api/tasks", "/api/tasks/*", "/api/tasks/*/result", "/api/tasks/*/validation").hasAnyRole("CLIENT","API_CLIENT")
.requestMatchers(POST, "/api/tasks/*/accept", "/api/tasks/*/reject").hasAnyRole("CLIENT","API_CLIENT")
.requestMatchers("/api/keys/**").hasRole("CLIENT")          // JWT-only key management
.requestMatchers("/api/admin/**").hasRole("ADMIN")          // unchanged
.anyRequest().hasAnyRole("CLIENT","BUILDER","ADMIN")        // API keys (only API_CLIENT) locked out of everything else
```
This is equivalent to `authenticated()` for every JWT user (they always hold ≥1 of CLIENT/BUILDER/ADMIN), so no regression; it only *adds* the API-key lockout. `ApiKeyAuthenticationFilter` is registered in the same chain, before `UsernamePasswordAuthenticationFilter`.

### 5. Idempotency + spend-cap orchestration (application layer)
A `SubmitOrchestrationAppService` wraps both submit endpoints. Context: `SubmitContext{ownerId, idempotencyKey?, apiKey?}`. One `@Transactional` method per endpoint:
1. **Idempotency pre-check** — if `idempotencyKey` present: look up `(owner, key)`; existing + matching fingerprint → return its `task_id` (no work); existing + different fingerprint → throw `IDEMPOTENCY_CONFLICT` (409).
2. **Spend-cap check** — for a capped key, reject with `SPEND_CAP_EXCEEDED` (409) if **either** cap would be exceeded: **concurrent** — `SpendReadDao.committedFor(keyId) + budget > spend_cap`; **daily** — `SpendReadDao.dailySpendFor(keyId, now−24h) + budget > daily_spend_cap`. The 409 message names which cap was hit. A `NULL` cap is skipped.
3. **Submit** — call the existing `TaskWriteAppService.submit` / `DirectBookingAppService.book` (joins the outer tx via `REQUIRED`: freeze escrow + create task + `TaskSubmitted` event after the outer commit).
4. **Attribution** — if `apiKey`: insert `api_key_task(task_id, keyId, budget)`.
5. **Idempotency insert** — if `idempotencyKey`: insert `(owner, key, fingerprint, task_id)` in the **same** transaction. A `UNIQUE` violation means a concurrent retry won → the outer tx rolls back (**undoing the freeze — no double-freeze**); catch it, then in a **new** transaction re-read the winner's row and return its `task_id` (fingerprint match) or 409.

`SpendReadDao` (CQRS reads, infra layer):
- `committedFor(keyId)` — concurrent frozen: `SELECT COALESCE(SUM(akt.budget),0) FROM api_key_task akt JOIN tasks t ON t.id = akt.task_id WHERE akt.api_key_id = :keyId AND t.status NOT IN ('RESOLVED','SPEC_VIOLATION','TIMED_OUT','FAILED','CANCELLED')`.
- `dailySpendFor(keyId, since)` — rolling-24h velocity, counts all submissions regardless of outcome: `SELECT COALESCE(SUM(budget),0) FROM api_key_task WHERE api_key_id = :keyId AND created_at > :since`.

**Accepted limitation:** two concurrent submits can both pass the cap check and slightly overshoot (no per-key lock); documented, acceptable for the demo (matches the matcher's documented single-instance assumptions).

The request **fingerprint** normalizes the submit payload (title, description, category, budget, output_spec) to a canonical JSON string → SHA-256. Applied to both submit endpoints, honored for **any** channel (JWT or key); the browser simply never sends the header.

### 6. Key management (JWT-only) — `ApiKeyController` (`ROLE_CLIENT`)
- `POST /api/keys` `{name, spendCap?, dailySpendCap?}` → `CreatedApiKeyDTO{id, name, displayPrefix, spendCap, dailySpendCap, rawKey}` — `rawKey` returned **only here**.
- `GET /api/keys` → `ApiKeyDTO[]{id, name, displayPrefix, spendCap, dailySpendCap, status, lastUsedAt, createdAt}` (never the key).
- `POST /api/keys/{id}/revoke` → `ApiKeyDTO` (owner-scoped: `getForOwner` throws `NOT_FOUND` on a non-owned key, per Invariant #5).

### 7. Frontend — `/client/keys` (client surface)
- List keys (prefix, name, both caps, status, last-used); "Create key" (name + optional concurrent cap + optional daily cap) → `Modal` reveals the raw key with a **copy button** and a *"Copy it now — you won't see it again"* warning; revoke with a confirm.
- Reuses the Mission Control kit (`Modal`, `Button`, `Card`, `Field`, `Input`, `Badge`) + the `api()` client. Linked from the `/client` console and nav.
- Types: `ApiKeyDTO`, `CreateApiKeyRequest`, `CreatedApiKeyDTO`.

## Security & invariants

| # | Invariant | Effect |
|---|-----------|--------|
| 1 | Escrow before execution | **Protected under retries** — idempotency's same-tx insert makes a duplicate roll back the freeze |
| 2 | Append-only money/audit | **Intact** — spend cap is an authorization check computed from tasks, not a second ledger |
| 3 | Deterministic money path | Unchanged |
| 4 | Output spec is the binding contract | Unchanged (routed = client spec in body; direct = agent spec) |
| 5 | Server-side identity | **Extended** — derived from the API-key principal; body/args never trusted |
| 6 | Signed, HTTPS-only I/O | Untouched (outbound client webhooks are Phase 4) |

Additional: keys hashed (SHA-256), only hash + prefix stored, raw revealed once; **least-privilege** (submit-scoped `ROLE_API_CLIENT`); no key enumeration (invalid → generic 401); JWT-only key management (a leaked key can't mint keys). **A `security-reviewer` agent pass is mandatory before merge** (auth/credential code).

## Error handling (`WebResult` / `ResultCode`)
- Missing/invalid/revoked key → **401** (unauthenticated chain entry point; bare 401, existing behavior).
- API key on a JWT-only endpoint → **403** (Spring access-denied).
- New `ResultCode.IDEMPOTENCY_CONFLICT` → **409**; new `ResultCode.SPEND_CAP_EXCEEDED` → **409** (join the `INSUFFICIENT_BALANCE, DOMAIN_RULE_VIOLATION → CONFLICT` arm in `GlobalExceptionConfiguration`).
- Insufficient escrow on submit → existing 409 path, unchanged.

## Testing
- **Domain/unit:** key issue (raw format, hex hash, prefix), verify-by-hash; fingerprint normalization stability; spend-cap arithmetic; `revoke()` transition.
- **App-service:** idempotency dedup (same fp → same task; different fp → 409); spend-cap guard — **concurrent** (under ok / over rejected) **and daily** (under ok / over rejected / older-than-24h submissions don't count) / uncapped bypass; key-mgmt create/list/revoke owner-scoped (non-owner → NOT_FOUND).
- **Controller (`@WebMvcTest`):** `ApiKeyController` (create returns rawKey once; list omits key; revoke); submit with `Idempotency-Key`.
- **Integration (Testcontainers Postgres + RabbitMQ):** submit-via-key → escrow freeze → route; idempotent retry returns the same task with no second freeze; **two concurrent identical retries → exactly one task** (UNIQUE); revoked key → 401; API key blocked from `/api/keys` and `/api/wallet` → 403; spend-cap rejection at the boundary.
- **Security chain:** the `SecurityConfig` allow-list change exercised via the auth integration test.
- **Frontend (vitest + msw):** keys page — create → reveal-once modal, list, revoke; keep the ≥80% gate + lint clean.
- **Mandatory:** a `security-reviewer` agent pass over the whole branch before it's offered for merge.

## Rollout
New branch `feat/programmatic-spine`; one migration (V25); Hibernate `ddl-auto: validate` stays green. New PR, **not merged** without explicit go-ahead and a passing security review. No config flag (the API-key filter is always on; keys simply don't exist until created).
