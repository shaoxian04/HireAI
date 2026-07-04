# Client Appeal to Human (Delayed Settlement) + Discoverable Disputes — Design Spec

Status: approved in brainstorming, 2026-07-04. Feeds `writing-plans`.

## Decisions locked in brainstorming

1. **Appeal is a real feature**, not just a test path — the client can escalate an arbitrator ruling they disagree with to a human administrator (tier-2), who confirms or overrides.
2. **Delayed settlement (Option A).** The arbitrator's ruling becomes a *proposal*; escrow stays frozen until the client accepts it, an admin finalizes an appeal, or an auto-accept timeout fires. **Money moves exactly once** — no reversal, no clawback. Invariants #1/#2/#3 hold with no new compensating-entry logic.
3. **Client-only appeal.** Only the client who raised the dispute reviews the proposed ruling and chooses accept vs appeal. If a ruling is `NOT_FULFILLED` (builder gets nothing), the builder has no recourse — accepted trade-off for the FYP scope.
4. **Auto-accept on timeout.** If the client neither accepts nor appeals within a configurable window, a scheduled sweeper auto-accepts the arbitrator's proposed ruling and settles. Prevents escrow hanging forever.
5. **Disputes become a first-class client surface.** A dedicated `/client/disputes` list plus a "Disputes" nav item with a count badge for disputes awaiting the client's decision — because disputes now resolve asynchronously over hours/days and must be discoverable from anywhere.

## Goal

Turn the arbitrator ruling from an instant, final settlement into a **proposed outcome the client can accept or appeal to a human**, without ever moving money more than once; and make in-flight disputes visible and reachable so a client returning days later immediately sees what needs their decision.

## Scope

**In:**
- Delayed settlement: decouple ruling-recording from settlement in the application layer.
- New domain transition `appeal()` (`RULED → ESCALATED`).
- Client endpoints: accept-ruling, appeal, and a client-scoped disputes list.
- Auto-accept timeout sweeper for stale `RULED` disputes.
- Frontend: `DisputeProgressPanel` (live lifecycle + accept/appeal actions), `/client/disputes` list, nav "Disputes" item + badge.

**Out (explicitly):**
- Builder-side appeal (client-only, decision #3).
- Settlement *reversal* / clawback (Option B was rejected).
- Push/SSE notification of resolution — the nav badge is the pull-based signal (SSE stays deferred).
- Persisting an appeal note / new dispute columns — YAGNI; the admin already has the original reject reason + the arbitrator's proposed ruling + rationale as context. **No schema migration.**
- Any change to the pre-dispute happy path (client accepts the RESULT with no dispute → settles FULFILLED as today).

## Architecture

The dispute lifecycle reuses the **existing** `DisputeStatus` values (`OPEN, ARBITRATING, RULED, RESOLVED, ESCALATED`). What changes is *when settlement runs* and two client-driven transitions out of `RULED`.

```
client rejects result (A/B/C)
  → task DISPUTED, dispute OPEN ──► arbitrationGateway.requestRuling
  → ARBITRATING (async)   [or immediate in the test/stub profile]
  → arbitrator returns {category, rationale}
       ══► recordRuling(tier-1 ARBITRATOR)  → RULED   (escrow STILL HELD — no settlement)
             ├─ client ACCEPT  → settleFromEffective → RESOLVED      ← money moves once
             ├─ client APPEAL  → appeal() → ESCALATED
             │        └─ admin rules (tier-2) → settleFromEffective → RESOLVED   ← money moves once
             └─ timeout        → auto-accept → settleFromEffective → RESOLVED

stranded (DLQ / stale-arbitration sweeper, no arbitrator ruling)
  → escalate() → ESCALATED → admin rules → settleFromEffective → RESOLVED
```

The one structural change: **settlement moves out of the arbitrator callback path** into the three finalizing actions (client-accept, admin-rule, auto-accept), each settling deterministically from the dispute's **effective (highest-tier) ruling** — which the aggregate already exposes via `effectiveRuling()`.

## Component 1 — Domain (`hireai-domain`)

### `DisputeModel` transitions

- **Add `appeal()`**: `RULED → ESCALATED`. Records no new ruling — the arbitrator's tier-1 proposal stays in the history as the admin's context. Guard: status must be `RULED`, else `DOMAIN_RULE_VIOLATION`.
- **`recordRuling(ruling)`** — unchanged. Already lands at `RULED` and appends to the append-only history (guard `OPEN|ARBITRATING|ESCALATED`). This is now the arbitrator's *proposal* step and the admin's *record* step.
- **`resolve()`** — unchanged (`RULED → RESOLVED`); now called only after a settlement runs.
- **`escalate()`** (`OPEN|ARBITRATING → ESCALATED`), **`startArbitrating()`**, **`isResolvable()`** (`OPEN|ARBITRATING`), **`effectiveRuling()`** — all unchanged. `isResolvable()` still guards the arbitrator callback so a late/duplicate ruling on a `RULED` dispute is ignored (first-ruling-wins).
- **`resolveByFallback()`** — already dead (DLQ escalates instead of auto-refunding). Leave as-is; removing it is out of scope.

No new `DisputeStatus` value. No entity/column change.

## Component 2 — Application (`hireai-application`)

### `DisputeAppService` (interface + impl) — the core refactor

Split today's `settleAndResolve(dispute, info, tier, decidedBy)` (record + settle + resolve, fused) into two reusable steps:

- `private recordProposedRuling(dispute, ruling)` — `dispute.recordRuling(ruling)` → save (`RULED`). **No settlement.**
- `private settleFromEffective(dispute)` — read `dispute.effectiveRuling().category()`, lock the task (`findByIdForUpdate`), run the existing `FULFILLED/PARTIALLY_FULFILLED/NOT_FULFILLED` settlement switch + `task.resolveDispute(...)`, then `dispute.resolve()` → save (`RESOLVED`). Money moves here, once.

Rewire the callers:

- **`applyRuling(disputeId, ruling)`** (arbitrator callback): keep the `isResolvable()` first-ruling-wins guard, then `recordProposedRuling` only. **Remove the settlement call.** The dispute rests at `RULED`.
- **`openDispute(...)`** synchronous branch (stub returns an immediate ruling): `recordProposedRuling` instead of settling. The dispute rests at `RULED`; the client (or a test) then accepts.
- **New `acceptRuling(disputeId, clientId)`**: load dispute; **ownership check** `dispute.raisedBy() == clientId` (Inv #5); require status `RULED` and a present `effectiveRuling`; `settleFromEffective`.
- **New `appeal(disputeId, clientId)`**: load; ownership check; require `RULED`; `dispute.appeal()` → save (`ESCALATED`).
- **`adminRule(disputeId, category, rationale, adminId)`**: unchanged behavior — `recordProposedRuling(tier-2 ADMINISTRATOR)` then `settleFromEffective` (which now reads the tier-2 ruling as effective). Still accepts `OPEN|ARBITRATING|ESCALATED`.
- **New `autoAcceptStaleRulings(cutoff)`** (called by the sweeper): for each stale `RULED` id, `settleFromEffective` under the task lock (try/catch per id, like the arbitration sweeper).
- **New read: `List<DisputeMineRow> myDisputes(UUID clientId)`** on a read app service (see Component 5-read) — client-scoped rows for `/client/disputes`.

### Race safety

`acceptRuling`, `appeal`, `adminRule`, and `autoAcceptStaleRulings` can race (e.g., client appeals just as the auto-accept sweeper fires). **All four acquire the task pessimistic lock (`findByIdForUpdate`) and re-read/re-validate the dispute status under it**, so exactly one transition out of `RULED` wins; the loser sees a non-`RULED` status and no-ops or throws `DOMAIN_RULE_VIOLATION`. This reuses the existing settlement lock pattern; `appeal` takes the lock purely as the serialization point.

### `DisputeRepository` (domain interface)

- Add `List<UUID> findStaleRuledIds(Instant cutoff)` — `RULED` disputes whose `gmtModified` (time they entered `RULED`) is older than `cutoff`. Mirrors the existing `findStaleArbitratingIds`.

## Component 3 — Infrastructure (`hireai-infrastructure` / `hireai-repository`)

- **`DisputeJpaRepository`**: add `@Query("SELECT d.id FROM DisputeDO d WHERE d.status = 'RULED' AND d.gmtModified < :cutoff") List<UUID> findStaleRuledIds(Instant cutoff);` + delegate in `DisputeRepositoryImpl`.
- **New `RulingAcceptSweeper`** (`@Component @Profile("!test")`, alongside `ArbitrationSweeper`): `@Scheduled(fixedDelayString="${hireai.arbitration.accept-sweep-interval:PT1M}")` → `cutoff = now − ruling-accept-after` → `disputeAppService.autoAcceptStaleRulings(cutoff)`. Registered via the existing `SchedulingConfig`.
- **`ArbitrationDlqListener`** — unchanged (still `escalate`, no settle).

## Component 4 — Controller + Security (`hireai-controller`)

### `DisputeController` — `/api/disputes/**` (client-owned)

- `POST /api/disputes/{id}/accept-ruling` → `acceptRuling(id, currentUserId)`; returns the refreshed dispute view.
- `POST /api/disputes/{id}/appeal` → `appeal(id, currentUserId)`; returns the refreshed dispute view. (No body in v1 — appeal note is out of scope.)
- `GET /api/disputes/mine` → `myDisputes(currentUserId)` — the client's dispute rows.
- `GET /api/disputes/by-task/{taskId}` — **extend** the existing `DisputeOutcomeDTO` with `disputeId` and keep `status`, so the panel can render the live state and target accept/appeal. (Client + owning-builder read already exists.)
- `currentUserId` from `CurrentUserProvider` (JWT); the app-layer ownership check is authoritative (Inv #5).

### `SecurityConfig`

`/api/disputes/**` already falls under `.anyRequest().authenticated()`. No new matcher — ownership is enforced in the app layer, not by role. (Admin ruling stays under `/api/admin/**` = `ROLE_ADMIN`.)

## Component 5 — Persistence + config + read

- **No migration.** All dispute states already exist (V17 CHECK); no new columns.
- **Config** (`application.yml`, `hireai.arbitration.*`): add `ruling-accept-after: ${RULING_ACCEPT_AFTER:PT2M}` and `accept-sweep-interval: ${RULING_ACCEPT_SWEEP_INTERVAL:PT1M}` (demo values; real deploy would set days).
- **Read DAO** (`JdbcDisputeQueryDao implements DisputeQueryPort`, mirroring `JdbcAdminQueryDao`): `myDisputes(clientId)` → join `disputes d` (raised_by = client) ⨝ `tasks t` for the title, project effective ruling category (highest-tier from `dispute_rulings`) and `updated_at`; returns `DisputeMineRow(disputeId, taskId, taskTitle, status, proposedCategory, updatedAt)` ordered action-needed (`RULED`) first.

## Component 6 — Frontend (`frontend/`)

Reuse the Mission-Control UI kit (Card / Badge / Button / Field) — no new visual language.

### `DisputeProgressPanel` (replaces `DisputeOutcomePanel`)

Renders by dispute `status` (from the extended by-task read):

| status | render |
|---|---|
| `ARBITRATING` (or `OPEN`) | live "⏳ Under review by an arbitrator…" |
| `RULED` | the **proposed** ruling (category label + rationale) + **Accept ruling** / **Appeal to a human** buttons |
| `ESCALATED` | "Escalated to a human administrator for final review…" |
| `RESOLVED` | final outcome: category, decided-by (arbitrator/admin), settlement summary |

Accept/Appeal POST to the new endpoints, then refetch. This replaces the current `return null` that left the client with no dispute feedback.

### `/client/disputes` list page

Lists `GET /api/disputes/mine`, grouped: **Awaiting your decision** (`RULED`) first, then **Under review** (`ARBITRATING`/`ESCALATED`), then **Resolved**. Each row links to the task detail (dispute panel). Mirrors the admin `/admin/disputes` queue components.

### Nav + task page

- **`Nav`**: add a CLIENT "Disputes" link → `/client/disputes` with a **count badge** = number of `RULED` disputes (sourced from `/api/disputes/mine`, via the auth/context layer or a small `useDisputeCount` hook).
- **Task detail page**: while `task.status === "DISPUTED"`, poll the by-task read and render `DisputeProgressPanel`; the result panel stays visible but is clearly non-actionable (the `ResultReviewBar` is already gone once past `PENDING_REVIEW`).

### Types + client

Extend `DisputeOutcomeDTO` (add `disputeId`); add `DisputeMineRowDTO`. New `api()` calls for accept-ruling / appeal / mine.

## Invariant check

- **#1 Escrow before execution** — unchanged; escrow frozen at submit, released only by an explicit settlement.
- **#2 Append-only money & audit** — money moves exactly once (accept / admin-rule / auto-accept). No reversal or compensating entries. Rulings are appended (arbitrator tier-1 proposal, admin tier-2), never replaced.
- **#3 Deterministic money path** — settlement computed in the domain/app from the effective ruling category; the LLM only proposes a category.
- **#5 Server-side identity** — accept-ruling / appeal / mine derive the client from the JWT and enforce `dispute.raisedBy == clientId`.
- **#6 Signed Agent I/O** — untouched.

## Testing

- **Domain** (`DisputeModelTest`): `appeal()` `RULED→ESCALATED`; `appeal()` from non-`RULED` throws; `recordRuling` still yields `RULED` and does **not** resolve.
- **App** (`DisputeAppServiceImplTest`): `applyRuling` records a proposal and leaves escrow **held** (assert no ledger settlement, dispute `RULED`); `acceptRuling` settles from the proposal (assert single settlement + `RESOLVED`); `appeal` → `ESCALATED`; `adminRule` after appeal settles from tier-2; `autoAcceptStaleRulings` settles; **ownership** — non-owner accept/appeal → 403/`DomainException`; **settle-exactly-once** across accept and admin paths.
- **Sweeper** (`RulingAcceptSweeperTest`): stale `RULED` → `autoAcceptStaleRulings` invoked; the query path.
- **Controller** (`DisputeControllerTest`, `@WebMvcTest`): accept-ruling / appeal 401 anon, 403 non-owner, 200 owner; `GET /api/disputes/mine` owner-scoped.
- **Integration** (Testcontainers): full flow reject → propose (`RULED`, escrow held) → appeal → admin override → **single** settlement + `RESOLVED`; and reject → propose → accept → settle.
- **Test migration (call out):** existing tests that assumed the arbitrator callback / synchronous stub *settles* now assert `RULED` + a follow-up `acceptRuling`. Affected: `AdminDisputeFlowIntegrationTest`, `ArbitrationTransportIntegrationTest`, the review-flow dispute tests.
- **Frontend** (vitest + MSW): `DisputeProgressPanel` each state; accept/appeal actions; `/client/disputes` grouping; nav badge count.
- **Live E2E** (on Supabase): irrelevant-article reject → arbitrator proposes → client **appeals** → admin overrides → wallet settles once; and the auto-accept timeout path.

## Build order (feeds writing-plans)

1. Domain: `appeal()` + `DisputeModelTest`.
2. App: split `settleAndResolve` → `recordProposedRuling` + `settleFromEffective`; rewire `applyRuling`/`openDispute`; add `acceptRuling`/`appeal`/`autoAcceptStaleRulings`; ownership + race lock; update app tests + migrate the settle-assuming tests.
3. Repo + sweeper: `findStaleRuledIds`; `RulingAcceptSweeper`; config keys.
4. Read: `DisputeQueryPort` + `JdbcDisputeQueryDao` + `myDisputes`.
5. Controller: `DisputeController` (accept-ruling/appeal/mine); extend by-task DTO.
6. Frontend: extend types/client; `DisputeProgressPanel`; `/client/disputes`; nav item + badge; task-page wiring; vitest.
7. Live E2E on the running Supabase stack.
```
