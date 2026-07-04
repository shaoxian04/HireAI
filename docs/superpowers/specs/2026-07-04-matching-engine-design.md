# Matching Engine + Reliability Sweepers — Design Spec (Phase 1)

> **Status:** Approved design · **Date:** 2026-07-04 · **Owner:** Shaoxian
> Phase 1 of the roadmap implementing `docs/task-matching-design.md` (and unblocking
> `docs/programmatic-task-submission.md`). Supersedes the live single-winner `max(reputation)` matcher.
> Companion phases: 2 = shortlist (`AWAITING_SELECTION`), 3 = programmatic spine (API keys),
> 4 = push webhooks, 5 = MCP facade.

## 1. Summary

Replace the single-winner `max(reputation)` matcher with **one ranking engine**: a multi-factor
weighted score over eligible candidates plus **epsilon-greedy exploration**, consumed as `top-1`
now (all channels auto-route in this phase) and as `top-N` by the Phase 2 shortlist. Alongside it,
close the three reliability holes the design docs assume are already closed:

1. `AWAITING_CAPACITY` tasks are never re-matched (held forever, escrow frozen) → **attempt-bounded
   re-match sweeper**, exhaustion → `CANCELLED` + full refund.
2. `TIMED_OUT` has no production trigger (silent agents strand tasks) → **execution-timeout sweeper**.
3. **Stranded-escrow bug:** the dispatch-DLQ path marks tasks `FAILED` without refunding — the
   client's escrow stays frozen forever. Fixed here.

## 2. Scope

**In:** scorer + epsilon-greedy selection (domain); candidate query enrichment (`in_flight`,
`sample_count`, `max_concurrent`); builder-declared `maxConcurrent` (registration API + one
frontend form field); V24 migration; re-match sweeper; execution-timeout sweeper; DLQ refund fix;
category-casing normalization fix.

**Out (deferred):** shortlist + `AWAITING_SELECTION` + near-miss suggestions (Phase 2 — see §10);
API keys / idempotency / programmatic anything (Phase 3+); push webhooks (Phase 4); reputation
updates (Module 5); semantic matching; execution failover (§8.4); outbox / publisher confirms
(the timeout sweeper catches the lost-publish *symptom*, not the cause); event-triggered re-match
on `AgentActivatedDomainEvent` (future — only interesting if the re-match window is ever lengthened).

**Phase-1 channel note:** the shortlist does not exist yet, so *every* open task — including
frontend submissions — auto-routes via the new scorer. Frontend-visible flow changes arrive in
Phase 2. Direct booking is unchanged (bypasses matching; see §6.2 for the one addition).

## 3. Decisions ledger

| Decision | Choice | Why |
|---|---|---|
| valueFit direction | **Cheaper = better**: `(budget − price)/budget` | Rewards budget headroom; price-as-quality is unsafe while reputation is static |
| `maxConcurrent` source | **Builder-declared per agent version** (V24 column, registration field), default 5 | Realistic marketplace semantics; owner knows own capacity |
| Re-match bound | **Attempt-bounded: 3 attempts × 10s ≈ 30s**, then `CANCELLED` + full refund | Fast definitive feedback; user chose fail-fast over long holds |
| Scorer data source | **Counts computed inside the candidate query** (option A) | Derive-don't-duplicate; index makes it sub-ms; seam allows swap to projection later (Module 5 events) |
| Exploration measure | **`sampleCount` = terminal tasks of any outcome** (not "completed") | Bandit sampling semantics; failures are information, not under-exploration |
| Pinned re-match | **Strict version pin** — never substitute agent *or* version | Task's frozen spec/price contract is with that exact version |
| Execution failover | **None** — once dispatched, the outcome is that agent's outcome | Re-dispatch doubles wait without consent; failures should feed reputation |
| Near-miss suggestions | **Phase 2** (after refund, via direct-booking links) | Same UI surface as shortlist; Phase 1 only makes the failure fast + clean |

## 4. The scoring engine (domain layer)

All framework-free in `hireai-domain`, wired via `DomainServiceConfig` (same pattern as
`SettlementPolicy`).

### 4.1 Hard filter — unchanged
`findActiveCandidates`: ACTIVE agent + current version + category array-overlap + `price ≤ budget`.

### 4.2 Score
For each eligible candidate, factors each normalised to `[0,1]`:

```
score = w_rep     · reputation / 100                       // static 50.00 today → constant offset;
                                                           // activates when Module 5 ships
      + w_value   · (budget − price) / budget              // valueFit, clamped [0,1]
      + w_load    · max(0, 1 − inFlight / maxConcurrent)   // loadHeadroom — SOFT factor: at/over
                                                           // capacity ⇒ 0 but still eligible
      + w_explore · 1 / (1 + sampleCount)                  // exploration decay
```

Defaults: `w_rep=0.40, w_value=0.20, w_load=0.20, w_explore=0.20`. Weights live in an immutable
**`MatchingPolicy`** value object, bound from `hireai.matching.*` config and **validated at
construction** (sum to 1.0 ± epsilon-tolerance, ε ∈ [0,1], attempts ≥ 1, grace ≥ 0) — bad config
is a startup crash.

The `max(0, …)` clamp on loadHeadroom matters: `inFlight` can legitimately exceed `maxConcurrent`
(direct bookings bypass matching; a builder can lower capacity on a new version mid-flight).
Clamping keeps every factor in `[0,1]` so weights stay meaningful and factors never bleed into
each other.

### 4.3 Selection
`RoutingMatchDomainService` exposes:

- **`rank(view, candidates)`** — deterministic: filter → score → sort by score desc, tie-break
  price asc, then `agentVersionId` asc (total, reproducible order). This is also the Phase 2
  shortlist's `top-N`.
- **`selectOne(view, candidates)`** — epsilon-greedy over `rank`: probability `1−ε` take the top;
  probability `ε` (default 0.10) sample from the eligible set **weighted by each candidate's
  exploration term**, so under-sampled agents occasionally win a real job.

The RNG is a constructor-injected seedable interface. `ε=0` ⇒ pure argmax (unit-test mode);
exploration behaviour is tested with fixed seeds. The old `selectAgentVersion` implementation is
**deleted, not kept alongside** — one ranking engine, consumed two ways.

### 4.4 Invariants
Exploration randomises **selection only**. Settlement remains deterministic from task outcome
(Invariant #3). Escrow timing unchanged (Invariant #1). The dispatch payload still carries the
**task's** frozen `output_spec`, never the candidate's (Invariant #4).

## 5. Data layer

### 5.1 Candidate query (option A)
`findActiveCandidates` keeps its filters and gains three outputs; `findCandidateByVersionId`
gains the same three so `AgentCandidate` stays one shape:

```sql
SELECT v.agent_id, v.id AS agent_version_id, v.capability_categories, v.price,
       v.webhook_url, v.max_execution_seconds, a.reputation_score, v.output_spec,
       v.max_concurrent,
       (SELECT COUNT(*) FROM tasks t
          JOIN agent_versions av ON av.id = t.agent_version_id
         WHERE av.agent_id = a.id
           AND t.status IN ('QUEUED','EXECUTING'))                         AS in_flight,
       (SELECT COUNT(*) FROM tasks t
          JOIN agent_versions av ON av.id = t.agent_version_id
         WHERE av.agent_id = a.id
           AND t.status IN ('RESOLVED','FAILED','TIMED_OUT','SPEC_VIOLATION')) AS sample_count
FROM agent_versions v
JOIN agents a ON a.id = v.agent_id AND a.current_version_id = v.id
WHERE a.status = 'ACTIVE'
  AND v.capability_categories && ARRAY[:category]::text[]
  AND v.price <= :maxPrice
ORDER BY a.reputation_score DESC
```

Semantic pins:
- **Counts are per-agent across all its versions** (publishing a version never resets history /
  restarts the exploration boost — that would be gameable).
- **`sample_count` counts all terminal outcomes** except `CANCELLED` (a task cancelled from
  `AWAITING_CAPACITY` was never dispatched — `agent_version_id` is NULL, so it can't join anyway).
- **`in_flight` = `QUEUED` + `EXECUTING` only** (`PENDING_REVIEW` etc. consume no agent capacity).

`AgentCandidate` grows `maxConcurrent`, `inFlight`, `sampleCount`. **The record is the seam**: the
scorer cannot tell whether counts came from a live COUNT, a projection, or a cache — if counting
ever shows up in a slow-query log, swap the query implementation and touch nothing else. The
designated at-scale upgrade is a projection maintained from Module 5's reputation-event stream,
which will also serve the future public "completed tasks" display (a **successes-only** number —
deliberately a different concept from `sampleCount`; the storefront stats DAO already computes
display counts today).

### 5.2 Migration V24
1. `ALTER TABLE agent_versions ADD COLUMN max_concurrent INT NOT NULL DEFAULT 5
   CHECK (max_concurrent BETWEEN 1 AND 100)` — DEFAULT backfills existing agents.
2. `ALTER TABLE tasks ADD COLUMN match_attempts INT NOT NULL DEFAULT 0`.
3. `ALTER TABLE tasks ADD COLUMN execution_deadline TIMESTAMPTZ` — **stamped at assignment**
   (`assignAndQueue`): `now + max_execution_seconds + grace`. Stamping at assignment (not at
   `EXECUTING`) lets one sweep also catch tasks stuck in `QUEUED` by the lost-publish crash window.
4. `ALTER TABLE tasks ADD COLUMN pinned_agent_version_id UUID` — nullable; written at submit for
   direct bookings; lets the sweeper distinguish pinned from open tasks (§6.2).
5. `CREATE INDEX idx_tasks_agent_version_status ON tasks (agent_version_id, status)` — makes both
   candidate counts index range scans.
6. Backfill: lowercase all existing `agent_versions.capability_categories` values (casing fix).

### 5.3 Registration surface
- `RegisterAgentRequest` gains optional `maxConcurrent` (`@Min(1) @Max(100)`, default 5 when
  omitted). `AgentVersionModel.create` owns the invariant (same style as `requireHttps`) and
  carries it through `supersededBy`. Existing callers unaffected.
- **Category normalization fix:** categories are lowercased at registration in
  `AgentVersionModel.create` (query-side lowercasing stays), closing the silent
  `"Translation" ≠ "translation"` zero-candidates bug.
- Frontend: one numeric input ("max parallel tasks", default 5) on the builder agent-register form.

## 6. Sweepers & the money path

Both sweepers follow the proven `ArbitrationSweeper` / `RulingAcceptSweeper` pattern:
`@Scheduled` beans in `hireai-infrastructure`, `@Profile("!test")`, **one transaction per task**
with a status re-check inside — a poisoned task is logged and skipped (retried next tick), and
overlapping runs / second instances no-op on the status guard.

### 6.1 Re-match sweeper (`AWAITING_CAPACITY`)
Every `rematch-interval` (10s): for each held task — increment `match_attempts`, then:

- **Open task** (`pinned_agent_version_id` NULL): re-run the full `route()` path (fresh candidate
  query + scorer + epsilon-greedy — *not* "runner-up from the original ranking"; the world has
  changed). Match → `assignAndQueue` + dispatch as normal.
- **Pinned task** (direct booking parked by the deactivation race): retry **only**
  `findCandidateByVersionId(pinned)` — "is that exact version ACTIVE and current again?" Never
  substitute another agent (client intent; the task's frozen spec/budget are that version's
  contract) and never follow a superseding version (same betrayal, different axis — the pin is to
  the **version**; superseded-while-waiting exhausts to refund, the honest resolution).

No match and `match_attempts ≥ rematch-max-attempts` (3) → new `AWAITING_CAPACITY → CANCELLED`
transition on `TaskModel` + **full refund**.

**Naming note:** with load as a *soft* factor, `AWAITING_CAPACITY` really means "awaiting eligible
supply" (an agent activates / registers / a price drops into budget) — busy agents were never
excluded. The name stays (already in the enum + DB constraint); re-match rescues transient
unavailability, not marketplace growth: ~30s then fail-fast + refund, per the fail-fast decision.

### 6.2 Execution-timeout sweeper (`QUEUED` / `EXECUTING`)
Every `execution-sweep-interval` (30s): `status IN ('QUEUED','EXECUTING') AND
execution_deadline < now()` → `markTimedOut` (the dormant transition gains its production caller)
+ **full refund**. Covers both the silent executor and the lost-dispatch symptom with one query.

### 6.3 DLQ stranded-escrow fix
Today the two "agent failed" paths disagree about money: callback-reported failure refunds
(`markFailed` + `settleRejected`); **retry-exhausted dispatch (DLQ) only marks `FAILED`** —
escrow frozen forever. Fix: the DLQ dead-letter handler settles the same full refund.

### 6.4 Money rules (stated once)
Every new escrow exit — `CANCELLED`, `TIMED_OUT`, `FAILED`-via-DLQ — is a **full refund**,
computed deterministically from the task outcome and recorded through the existing settlement
write path (settlement row + append-only ledger entries), exactly like the callback-failure
refund today. `settlements.task_id UNIQUE` backstops double-settle races. The late-callback race
resolves cleanly: after a timeout refund the task is no longer `EXECUTING`, so the existing
first-result-wins guard drops the callback as a no-op.

## 7. Configuration

| Key | Default | Meaning |
|---|---|---|
| `hireai.matching.weight-reputation` | 0.40 | score weight |
| `hireai.matching.weight-value` | 0.20 | score weight |
| `hireai.matching.weight-load` | 0.20 | score weight |
| `hireai.matching.weight-exploration` | 0.20 | score weight |
| `hireai.matching.epsilon` | 0.10 | exploration rate (auto-route) |
| `hireai.matching.rematch-interval` | 10s | re-match sweep cadence |
| `hireai.matching.rematch-max-attempts` | 3 | attempts before cancel+refund (~30s window) |
| `hireai.matching.default-max-concurrent` | 5 | applied when registration omits the field |
| `hireai.execution.sweep-interval` | 30s | timeout sweep cadence |
| `hireai.execution.grace` | 60s | added to `max_execution_seconds` for the deadline |

All validated at startup (weights sum to 1.0; ε ∈ [0,1]; attempts ≥ 1; grace ≥ 0).

## 8. Error handling

1. **Sweepers:** per-task transactions; log-and-continue on per-task failure; natural retry next tick.
2. **Idempotency:** status guards inside each transaction; overlap/second-instance safe.
3. **Money:** three layers against double-settle — status transitions, single settlement path,
   `settlements.task_id UNIQUE`.
4. **Scorer:** never throws on data — empty in → empty out; division guards structural
   (budget > 0 at submit; `max_concurrent ≥ 1` by CHECK); over-capacity clamps to 0.
5. **No execution failover** (§3): once dispatched, terminal failure refunds; the client resubmits
   by choice. Re-matching only ever applies to tasks never dispatched to anyone.

## 9. Test plan

Regression bar: all ~398 backend + 83 frontend tests stay green throughout.

**Scorer (pure unit):**
1. Each factor in isolation decides the winner (rep / valueFit / load / exploration; 4 tests).
2. Composite score equals a hand-computed value.
3. `rank()` returns all eligible, sorted, scored.
4. ε=0 ⇒ `selectOne` is a deterministic argmax.
5. Empty / fully-filtered candidate list ⇒ empty result.
6. Category miss + over-budget still filtered (regression).
7. Exact tie ⇒ price asc, then agentVersionId asc; assert total order.
8. `inFlight == maxConcurrent` ⇒ headroom 0, still eligible; `inFlight > maxConcurrent` ⇒ clamped, no negative bleed.
9. `price == budget` ⇒ valueFit 0, still eligible.
10. Fixed-seed ε=1 ⇒ exploration-weighted sampling picks the under-sampled agent, reproducibly.
11. Single candidate ⇒ always chosen regardless of ε.
12. Invalid weights / ε ⇒ `MatchingPolicy` construction fails.

**Candidate query (Testcontainers):**
13. 2 QUEUED + 1 EXECUTING ⇒ `in_flight = 3`; terminal set ⇒ `sample_count`; CANCELLED + live excluded.
14. Counts aggregate across an agent's superseded versions.
15. Zero tasks ⇒ counts 0 (not null).
16. `max_concurrent` selected; pre-migration rows default 5.
17. "Translation" (stored) matches task "translation" after the casing fix.
18. `findCandidateByVersionId` carries the same new fields.

**Routing service (Mockito):** existing suite green (assign-before-publish, task-spec-not-candidate-spec, no-match ⇒ hold), plus:
19. `execution_deadline` stamped at assignment.
20. Direct booking persists `pinned_agent_version_id` at submit.

**Re-match sweeper:**
21. Held open task + newly-eligible agent ⇒ dispatched.
22. Held pinned task + same version reactivated ⇒ dispatched to that agent.
23. 3 attempts exhausted ⇒ `CANCELLED`, settlement row, wallet restored.
24. Pinned version superseded ⇒ never substitutes ⇒ exhausts ⇒ refund.
25. Pinned task never re-matched to a different agent even when others are eligible.
26. `match_attempts` increments per failed attempt.
27. Task already transitioned when processed (overlap) ⇒ no-op.
28. Exception on task #1 doesn't stop tasks #2..n.

**Timeout sweeper:**
29. `EXECUTING` past deadline ⇒ `TIMED_OUT` + full refund.
30. `QUEUED` past deadline (lost dispatch) ⇒ `TIMED_OUT` + refund.
31. Before deadline ⇒ untouched; `RESULT_RECEIVED`/`PENDING_REVIEW` never swept.
32. Late callback after timeout-refund ⇒ dropped by status guard, no double settle.

**DLQ fix:**
33. Dead-lettered dispatch ⇒ `FAILED` + refund (previously stranded).
34. Duplicate DLQ delivery ⇒ idempotent, single settlement.

**Integration (full spine, Testcontainers):**
35. 3 seeded agents of differing price/load/history, ε=0 ⇒ hand-computed winner dispatched ⇒ callback ⇒ `PENDING_REVIEW`.
36. No-match ⇒ hold ⇒ agent activates ⇒ sweeper rescues within a tick.
37. Exhaustion ⇒ wallet balance identical to pre-submit.
38. Registration round-trips `maxConcurrent`; omitted ⇒ default 5.

**Config/startup:** 39. Bad weights crash boot.
**Frontend (vitest):** 40. Register form sends `maxConcurrent`, defaults to 5.

## 10. Phase 2 commitments recorded here so they aren't lost

- **Shortlist:** `AWAITING_SELECTION` state + top-5 from `rank()` + selection endpoint +
  abandonment sweeper; instant shortlist at submit (matching is milliseconds when candidates exist).
- **Near-miss suggestions:** when matching exhausts ⇒ refund lands first ⇒ UI shows
  category-matching ACTIVE agents priced **above** budget (order price asc, limit 5) with
  **direct-booking links** — refund-then-rebook instead of budget-edit machinery.

## 11. Invariants check

| # | Invariant | Status |
|---|---|---|
| 1 | Escrow before execution | ✅ freeze-at-submit unchanged; every new exit is a recorded settlement |
| 2 | Append-only money/audit | ✅ refunds via existing settlement path + ledger entries |
| 3 | Deterministic money path | ✅ exploration randomises selection only |
| 4 | Output spec binding | ✅ task's frozen spec still threaded; pinned re-match refuses spec-changing substitution |
| 5 | Server-side identity | ✅ untouched |
| 6 | Signed, HTTPS-only I/O | ✅ untouched |
