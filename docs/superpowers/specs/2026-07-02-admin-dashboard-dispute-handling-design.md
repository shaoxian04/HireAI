# Admin Dashboard + Administrator Dispute Handling — Design Spec

**Date:** 2026-07-02
**Status:** design (awaiting user review → writing-plans)
**Builds on:** Module 4 Phase 3b (async arbitrator + append-only `dispute_rulings` history, PR #17). The seams for tier-2 already exist: `Role.ADMIN`, `RulingDecidedBy.ADMINISTRATOR`, `DisputeStatus.ESCALATED`, and the append-only `dispute_rulings` child table keyed by `tier` + `decided_by` — all reserved with **no writer**. This spec gives them a writer.

## Decisions locked in brainstorming
- **Dashboard scope = overview + disputes.** A read-only platform overview (metrics + task / agent / user-wallet browsers) plus the dispute queue → detail → rule. No mutation beyond dispute rulings.
- **Override reach = human backstop only.** The admin rules **un-settled** disputes (`OPEN | ARBITRATING | ESCALATED`), where no money has moved. The ruling settles escrow **once**, deterministically, from the chosen category. **No** overturning already-settled disputes, **no** compensating entries, **no** builder claw-back, **no** negative balances. Settled disputes appear in the queue read-only.
- **Provisioning = seed a dedicated admin.** A new migration seeds `admin@hireai.local` with a single `ADMIN` role. No endpoint ever grants ADMIN.
- **DLQ = route to admin.** Persistent arbitration failure no longer auto-refunds. `ArbitrationDlqListener` transitions the dispute to `ESCALATED` (removing `resolveByFallback`).
- **Stranded detection = background sweeper.** A `@Scheduled` job flips stale `ARBITRATING` disputes to `ESCALATED` proactively, closing the deferred timeout-sweeper gap.

## Goal
Give the platform a human backstop for disputes the arbitrator could not resolve, and a read-only window onto platform state. An admin sees every dispute that needs attention — whether it dead-lettered (loud failure) or silently stranded (worker ACKed but never ruled) — and issues a ruling. The ruling picks a **category**, not an amount; the Java domain computes settlement deterministically (Invariant #3). Money moves exactly once, so all money invariants hold by construction.

## Scope

**In:**
1. Domain — two new `DisputeModel` transitions: `escalate()` and an admin ruling path (tier-2, `ADMINISTRATOR`).
2. Application — `DisputeAppService`: replace `resolveByFallback` with `escalate`; add `adminRule`; add `sweepStaleArbitrating`. New `AdminReadAppService` (thin cross-aggregate reads).
3. Infrastructure — `ArbitrationDlqListener` → `escalate`; new `ArbitrationSweeper` (`@Scheduled`); new `JdbcAdminQueryDao`.
4. Controller — `AdminController` under `/api/admin/**`, gated by `ROLE_ADMIN`; read DTOs + rule endpoint.
5. Persistence — new migration seeds the admin account (no schema change to `dispute_rulings`).
6. Frontend — a new `/admin` surface (overview, dispute queue, dispute detail + rule action); auth/Nav/RoleGuard learn `ADMIN`.
7. Tests — domain, application, integration (authz + settlement + DLQ→escalate + sweeper), frontend vitest, and a live E2E.

**Out (deferred):**
- Overturning **settled** disputes / money reversal (compensating entries, claw-back). Explicitly rejected for this scope.
- Managing users / roles / agents / wallets (read-only browsers only; no admin mutation of these).
- SSE push — the admin UI polls, like every other surface.
- Notifications / SLA timers hung off the `ESCALATED` transition (the transition now exists as a seam for them).

**Untouched:** the Python arbitrator service. Only the Java-side reaction to a DLQ changes (escalate vs refund); the message contract, worker, and callback are unaffected.

---

## Architecture

```
                                    RabbitMQ
  Java backend ──publish──▶ task.dispute.requested ──consume──▶ Python arbitrator
       │                          │ (DLQ on nack)                 (unchanged)
       │                          ▼                                     │ POST ruling
       │                 task.dispute.requested.dlq                     ▼
       │                          │                    /api/arbitration-callbacks/{id}/ruling
       │      ArbitrationDlqListener  ── escalate() ──┐   (arbitrator, tier-1, first-ruling-wins)
       │      ArbitrationSweeper(@Scheduled)─ escalate()┤
       │                                                ▼
       │                                      dispute: → ESCALATED
       │                                                │
       └──  /api/admin/**  (ROLE_ADMIN) ── adminRule ──▶ settle once + resolve (tier-2 ADMINISTRATOR)
                    │                                     dispute_rulings (append-only)
             JdbcAdminQueryDao ──read──▶ overview + queue + detail + browsers
```

Two failure modes now converge on one actionable state, `ESCALATED`:
- **Case A — DLQ (loud).** Worker nacks → dead-letters → `ArbitrationDlqListener` fires → `escalate` immediately.
- **Case B — silently stranded (quiet).** Worker ACKs but never rules; nothing fires. The `ArbitrationSweeper` finds it by age and `escalate`s it.

The admin's `adminRule` is the settlement path for both, and also for any still-`OPEN`/`ARBITRATING` dispute the admin chooses to act on.

---

## Component 1 — Domain (`hireai-domain`)

### `DisputeModel` transitions
Current state machine: `OPEN → ARBITRATING → RULED → RESOLVED`, plus a fallback path from `OPEN|ARBITRATING → RESOLVED`. Changes:

- **`escalate()`** — `OPEN | ARBITRATING → ESCALATED`. No ruling recorded; escalation only signals "needs admin." Idempotent-friendly: callers check `isResolvable()` first, and the transition guard rejects `RESOLVED`/already-`ESCALATED`.
- **Admin ruling** — reuse the existing record→resolve path, widened. `recordRuling`'s internal guard broadens from `OPEN|ARBITRATING` to `OPEN|ARBITRATING|ESCALATED` (so an escalated dispute can be ruled). Callers gate correctly, so this is safe (see below).
- **`isResolvable()` stays `OPEN | ARBITRATING`.** It gates the **arbitrator** callback path only. Consequences:
  - A late arbitrator callback on an `ESCALATED` dispute → `isResolvable()==false` → ignored. The admin owns it.
  - A late arbitrator callback after the admin resolved (`RESOLVED`) → ignored. First-ruling-wins preserved.

### Ruling tier + author
Admin rulings are recorded at **tier 2**, `decidedBy = ADMINISTRATOR`. Invariant on the tier/author pairing: `ARBITRATOR`/`FALLBACK` = tier 1, `ADMINISTRATOR` = tier 2. `effectiveRuling()` (already `max(tier)`) therefore surfaces the admin decision over any tier-1 ruling — correct even in the (unreachable-in-backstop-scope) case where both exist. In the backstop flow there is no tier-1 ruling, so the admin's tier-2 ruling stands alone.

### `RulingDecidedBy.FALLBACK`
Becomes write-dead once the DLQ escalates instead of refunding. **Kept** as an enum value and in the `dispute_rulings.decided_by` CHECK — removing it would need a pointless migration, and it remains a legitimate historical/domain concept. No writer.

---

## Component 2 — Application (`hireai-application`)

### `DisputeAppService` (interface + impl)
- **Remove** `resolveByFallback`.
- **Add** `escalate(UUID disputeId)` — load dispute; if `isResolvable()` → save `escalate()`; else log and no-op (a callback or a prior sweep already handled it).
- **Add** `adminRule(UUID disputeId, RulingCategory category, String rationale, UUID adminId)` — load dispute; if not in `OPEN|ARBITRATING|ESCALATED` → throw a domain conflict (`DOMAIN_RULE_VIOLATION`, mapped to HTTP 409, **not** 500); else settle deterministically by category (same switch as the arbitrator path) at **tier 2 / ADMINISTRATOR**, resolve task + dispute. `adminId` is recorded via the ruling author (the domain models "who" as the enum; `adminId` is used for audit logging — see note).
- **Add** `sweepStaleArbitrating()` — find disputes `ARBITRATING` older than the threshold; `escalate` each. Returns the count (for logging/testing). This is a plain method so tests call it directly; the `@Scheduled` bean just delegates.
- **Refactor** `settleAndResolve(dispute, info, decidedBy)` → `settleAndResolve(dispute, info, tier, decidedBy)`; the arbitrator path passes `(1, ARBITRATOR)`, the admin path `(2, ADMINISTRATOR)`.

> **Audit note:** `Ruling` currently records `decidedBy` (the enum) but not the specific admin's user-id. For traceability we log `adminId` at `adminRule` time. Persisting the admin's user-id on the ruling row is a possible future column but is **out of scope** here (no schema change); the enum + log is sufficient for the backstop.

### `AdminReadAppService` (interface + impl)
Thin orchestration over `JdbcAdminQueryDao`. Read-only; no aggregate loading. Methods:
- `overview()` → `AdminOverview` (counts: open/escalated disputes, tasks by status, users, agents; escrow held = sum of frozen wallet balances; commission earned = sum of platform commission from settlements).
- `disputeQueue(filter)` → list rows: dispute id, task id + title, status, reason category, created-at/age, `needsAttention` (= `ESCALATED` or `OPEN`), and whether an arbitrator ruling exists.
- `disputeDetail(disputeId)` → task description, output spec (format/schema/acceptance criteria), result payload, reason category, full ruling history.
- `recentTasks()`, `usersWithWallets()`, `agents()` → read-only browser rows (paged/bounded).

### `DisputeRepository` (domain interface)
Add `findStaleArbitrating(Instant cutoff)` (returns dispute ids or lightweight models) for the sweeper.

---

## Component 3 — Infrastructure (`hireai-infrastructure` / `hireai-repository`)

- **`ArbitrationDlqListener.onDeadLetter`** → `disputeAppService.escalate(message.disputeId())` (was `resolveByFallback`). Log wording updated (escalate-to-admin, not refund-fallback).
- **`ArbitrationSweeper`** — new `@Component @Profile("!test")`; `@Scheduled(fixedDelayString = "${hireai.arbitration.sweep-interval:PT1M}")` delegates to `sweepStaleArbitrating()`. Guarded so an exception in one run is logged, not fatal. Requires `@EnableScheduling` (new `@Configuration` or on the main app; verify it isn't already enabled).
- **Config** — `hireai.arbitration.stale-after` (default `PT2M` for the demo) and `hireai.arbitration.sweep-interval` (default `PT1M`) in `application.yml`.
- **`JdbcAdminQueryDao`** — new read DAO in `hireai-repository`, mirroring `JdbcCatalogueQueryDao`: hand-written SQL joining `disputes` / `dispute_rulings` / `tasks` / `task_results` / `agent_versions` / `wallets` / `users` / `settlements` for the overview, queue, detail, and browsers. Read-only, bounded result sets.

### Race safety (sweeper vs. arbitrator callback)
`escalate` runs `@Transactional`; it re-loads the dispute and applies the guard inside the transaction. Interleavings:
- Callback commits first (`→RESOLVED`): sweep's `escalate` sees non-resolvable → no-op.
- Sweep commits first (`→ESCALATED`): callback's `applyRuling` sees `isResolvable()==false` → ignored.

Both are safe. The dispute row's lifecycle guard is the serialization point; no lost update because neither path blindly overwrites — each checks current status.

---

## Component 4 — Controller + Security (`hireai-controller`)

### `AdminController` — `/api/admin/**`
| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/admin/overview` | — | `AdminOverviewDTO` |
| GET | `/api/admin/disputes?filter=needs_attention\|all` | — | `DisputeQueueRowDTO[]` |
| GET | `/api/admin/disputes/{id}` | — | `AdminDisputeDetailDTO` |
| POST | `/api/admin/disputes/{id}/rule` | `{category, rationale}` | resolved `DisputeOutcomeDTO` (or 409 if already resolved) |
| GET | `/api/admin/tasks` | — | `AdminTaskRowDTO[]` |
| GET | `/api/admin/users` | — | `AdminUserRowDTO[]` |
| GET | `/api/admin/agents` | — | `AdminAgentRowDTO[]` |

Thin: auth (via chain) + map → app service. `adminId` from `CurrentUserProvider.currentUserId()`. The rule request validates `category ∈ {FULFILLED, PARTIALLY_FULFILLED, NOT_FULFILLED}` and a non-blank `rationale`.

### `SecurityConfig`
Add, in the secured (`!test`) chain, before `anyRequest().authenticated()`:
```java
.requestMatchers("/api/admin/**").hasRole("ADMIN")
```
The `JwtAuthenticationFilter` already maps each `Role` to a `ROLE_<name>` authority, so `hasRole("ADMIN")` matches `ROLE_ADMIN`. A CLIENT/BUILDER JWT hitting `/api/admin/**` gets 403; no token gets 401. This is the Invariant #5 boundary. (The permissive `test` chain is unchanged; controller integration tests that need ADMIN assert against the real chain or use a minted ADMIN token / `@WithMockUser` equivalent — see testing.)

---

## Component 5 — Persistence migration

**`V23__seed_admin_user.sql`** — insert `admin@hireai.local` into `users` (bcrypt of `DemoPass123!`, matching the V5 seed convention) and one `user_roles` row `(admin_id, 'ADMIN')`. Idempotent-safe (`ON CONFLICT DO NOTHING` on the natural key), mirroring V5. **No** wallet is required (admin neither pays nor earns), but seed one at zero if the schema/foreign keys expect every user to have a wallet — verify against V1/V5.

No change to `dispute_rulings` (already supports `ADMINISTRATOR` + `tier`). No change to `disputes` (keeps `status` incl. `ESCALATED`).

---

## Component 6 — Frontend (`frontend/`)

### Auth + chrome
- **`lib/auth.tsx`** — `homeFor(roles)` returns `/admin` for an admin-only user; `activeSurface` may be `ADMIN`. The CLIENT/BUILDER dual-switcher is unchanged (admin is a distinct, seeded-only surface, not toggled).
- **`components/RoleGuard.tsx`** — supports `role="ADMIN"` (verify it's already generic over `Role`).
- **`components/Nav.tsx`** — an `activeSurface === "ADMIN"` branch: links **Overview** (`/admin`) and **Disputes** (`/admin/disputes`); the role chip shows `ADMIN`.
- **Login redirect** — routes an admin to `/admin` via `homeFor`.

### Pages (reuse the Mission-Control UI kit — Card / Badge / Button / Field; no new visual language)
- **`/admin`** — overview: metric tiles (open/escalated disputes, tasks, users, agents, escrow held, commission earned) + compact read-only tables (recent tasks, agents, users+wallets).
- **`/admin/disputes`** — queue: default filter `needs_attention`; each row shows task title, status badge, reason, age; escalated rows visually flagged. Row → detail.
- **`/admin/disputes/[id]`** — detail: task description, output spec, result payload (pretty JSON, like the client task view), reason category, ruling history. If the dispute is actionable (`OPEN|ARBITRATING|ESCALATED`), a **rule** control: pick category (FULFILLED / PARTIALLY_FULFILLED / NOT_FULFILLED) + required rationale → `POST …/rule`. If already resolved, read-only outcome (reuse `DisputeOutcomePanel` styling).

### Types + client
- **`lib/types.ts`** — add the admin DTO types + extend `Role` usage where needed (`Role` already includes `ADMIN`).
- `api()` calls for the seven endpoints.

---

## Invariant check
1. **Escrow before execution** — unaffected; admin never dispatches.
2. **Append-only money & audit** — the admin ruling is a *first* settlement (escrow moves once); recorded in append-only `dispute_rulings` + `ledger_entries`/`settlements`. No reversal exists in this scope, so nothing violates append-only.
3. **Deterministic money path** — `adminRule` computes settlement from the category in the domain switch; the admin selects a category, never an amount. The rationale is never in the money path.
4. **Declared output spec is the binding contract** — the admin detail view surfaces the same `output_spec` + result the arbitrator judged; the admin rules against it.
5. **Server-side identity from JWT** — `/api/admin/**` gated by `ROLE_ADMIN`; `adminId` derived from the JWT principal, never the path/body.
6. **Signed, HTTPS-only Agent I/O** — untouched.

---

## Testing

**Domain (`hireai-domain`):**
- `DisputeModelTest` — `escalate` from `OPEN` and `ARBITRATING`; `escalate` rejected from `RESOLVED`/`ESCALATED`; admin ruling from `ESCALATED` records tier-2 `ADMINISTRATOR`; `effectiveRuling` prefers tier-2.

**Application (`hireai-application`):**
- `DisputeAppServiceImplTest` — `escalate` transitions and no-ops when not resolvable; `adminRule` settles each category (FULFILLED→payout, PARTIALLY→split, NOT_FULFILLED→refund) and resolves task + dispute; `adminRule` on an already-`RESOLVED` dispute → conflict; `sweepStaleArbitrating` escalates only stale rows.

**Integration (`hireai-main`, Testcontainers):**
- `/api/admin/**` authz: ADMIN → 200, CLIENT/BUILDER → 403, anonymous → 401.
- `adminRule` end-to-end: escalated dispute → rule NOT_FULFILLED → client wallet refunded, task `RESOLVED/REJECTED`, `dispute_rulings` has a tier-2 ADMINISTRATOR row.
- DLQ → `ESCALATED` (listener), and `sweepStaleArbitrating` → `ESCALATED` for an aged `ARBITRATING` dispute; a fresh one is untouched; a `RESOLVED` one is a no-op.
- `AdminReadAppService` / DAO: overview counts + queue + detail assemble correctly.

**Frontend (vitest + MSW):**
- Admin queue renders needs-attention rows; detail renders evidence; the rule action posts `{category, rationale}` and reflects the resolved outcome; RoleGuard blocks non-admins.

**Live E2E (Playwright, full local stack):**
- Strand a dispute (start a dispute, then either kill the worker to force silent stranding + let the sweeper escalate, or force a DLQ). Log in as `admin@hireai.local` → see it under needs-attention/`ESCALATED` → rule NOT_FULFILLED → confirm the client is refunded and the dispute shows the tier-2 ADMINISTRATOR ruling. Screenshot each step.

---

## Build order (feeds writing-plans)
1. Domain transitions (`escalate`, admin ruling tier-2) + tests.
2. App layer: `escalate`, `adminRule`, `sweepStaleArbitrating`, `settleAndResolve` refactor; remove `resolveByFallback`; repository `findStaleArbitrating` + tests.
3. Infra: DLQ listener → escalate; `ArbitrationSweeper` + `@EnableScheduling` + config; `JdbcAdminQueryDao`.
4. Read app service + DTOs.
5. `AdminController` + `SecurityConfig` `/api/admin/**` gate + integration tests.
6. `V23` seed admin migration.
7. Frontend: auth/Nav/RoleGuard `ADMIN`; `/admin`, `/admin/disputes`, `/admin/disputes/[id]`; types + api; vitest.
8. Docs: update `CLAUDE.md` build status (admin surface built; timeout sweeper built; DLQ now escalates; tier-2 backstop built) + `docs/details/*` as needed; memory.
9. Live E2E verification.
```
