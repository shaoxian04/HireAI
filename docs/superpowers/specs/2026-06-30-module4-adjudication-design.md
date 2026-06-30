# Module 4 — Adjudication (validation gate + tiered disputes, tier-1) — design

**Date:** 2026-06-30
**Status:** approved design, pending implementation plan
**Branch:** `feat/module4-adjudication` (off `main` @ `b5c0875`, after the capability re-division merged)
**Authoritative target:** SAD §6.4 (Quality Validation & Dispute Resolution), §3.3 (aggregate model — `ValidationReport`/`CheckResult`/`Dispute`/`Ruling`), §4.1 (Task + Dispute state machines), §5.3 (settlement rules). Conventions: `docs/details/ddd-conventions.md`.

## 1. Context & goal

The marketplace spine and the capability re-division are merged: the `adjudication` subdomain package is **reserved but empty**, the Task state machine defines `PENDING_REVIEW`/`SPEC_VIOLATION` as **unreachable**, the agent callback goes `EXECUTING → RESULT_RECEIVED` with **no validation gate**, and accept/reject settle straight off `RESULT_RECEIVED`. `SettlementType.SPLIT` exists but is unused. There is no `disputes`/`validation_reports` table, and `arbitration/` (the Python service) does not exist. Migrations stop at **V15**.

**Goal:** build Module 4's **tier-1** capability — the automated output-spec **validation gate**, the **dispute** flow (reason-gate → tier-1 LLM arbitration → deterministic settlement), and the external **Python LangGraph arbitrator** — populating the reserved `adjudication` subdomain and finally wiring `SettlementType.SPLIT`. This enforces Hard Invariant #4 at runtime (validation before the client sees a result) and Hard Invariant #3 (the LLM proposes a ruling; the domain disposes of money).

**Tier-2 (client appeals + Administrator review + Admin dashboard) is a separate future spec.** This design leaves a clean `ESCALATED` seam and self-terminates every dispute without a human in the loop.

This is a **feature build** across all backend layers plus a new polyglot service.

## 2. Constraints & decisions

| Decision | Choice | Rationale |
|---|---|---|
| **Scope** | Full Module 4 **tier-1** (gate + dispute + Python arbitrator); tier-2 deferred | User decision (2026-06-30). Tier-1 is self-terminating; tier-2 owns its own spec + dashboard. |
| **Validation strictness** | **Deterministic structural only** (format + JSON-Schema-when-present) | Keeps "is-it-valid" (machine) separate from "is-it-good" (client review + disputes). Free-text `acceptanceCriteria` is *never* machine-judged at the gate. |
| **Arbitrator implementation** | Python **FastAPI + LangGraph** + Claude | User decision; matches SAD/CLAUDE.md. Showcases agentic orchestration. |
| **Java↔Python transport** | **Async over RabbitMQ** (request queue + ruling callback), behind an ACL port | Arbitration is an LLM call (slow, rate-limited, failure-prone); a queue gives buffering + at-least-once + DLQ + independent worker scaling, and matches the SAD's `ARBITRATING` state. Reuses the existing dispatch→callback pattern. |
| **ACL port** | `ArbitrationGateway` interface; **stub** adapter (Phase 2) + **RabbitMQ** adapter (Phase 3) | The stub lets the whole dispute→ruling→settlement flow build and Testcontainer-test before the Python service exists; transport stays swappable. |
| **Browser updates** | **Keep polling** (existing pattern); SSE is a future, drop-in cross-cutting item | Waits are seconds, one event per task; polling is built, robust, stateless. The event-driven backend makes SSE a localized later add. |
| **Spec-violation retry** | On validation fail, **re-dispatch the same agent once** (configurable `adjudication.max-validation-retries`, default 1), no charge to the client | A transient agent hiccup gets a second chance → higher success rate, fairer to agents. Bounded to same-agent re-dispatch — not the full reliability net. |
| **`SPEC_VIOLATION` settlement** | After the retry is exhausted and validation still fails → **full 100% refund** (the SAD's 80/20 *fee* is removed; the *retry* is kept) | A client who never got a usable result must not lose credits; retaining a fee is bad UX. |
| **Auto-refund scope** | Wire deterministic `REFUND` into `SPEC_VIOLATION`, `TIMED_OUT`, **and** `FAILED` | Closes a real escrow-stranding gap cheaply via one path. Reputation events on these stay Module 5. |
| **Client inaction** | Auto-**accept** `PENDING_REVIEW` past a configurable deadline (settle 85/15) via a minimal `@Scheduled` sweeper | Silence on conformant, validated work = acceptance; prevents frozen escrow / free work. Only this one sweeper is pulled in — the rest of the reliability net stays deferred. |
| **Dispute termination (兜底)** | Tier-1 ruling auto-applied + **final** this cycle; arbitrator persistent failure → **DLQ → full refund to client** | Guarantees every dispute terminates and escrow is released without tier-2 and without a time-based sweeper. |
| **Migrations** | Additive **`V16` (validation_reports)**, **`V17` (disputes + `tasks.reject_reason_category`)** | Phase-aligned; `V1–V15` immutable. |
| **Money path** | Computed **deterministically** in `ledger.settlement` from the ruling category | Inv #3 — the LLM returns only a category + rationale; Python never sees balances or moves credits. |

## 3. Architecture & package map

The `adjudication` subdomain is populated across the COLA layers; a new `arbitration/` service is added at the repo root.

```
com.hireai.domain.biz.adjudication
│   ├─ model/      ValidationReport(root) + CheckResult(VO) + Verdict(enum)
│   │              Dispute(root) + Ruling(VO)
│   ├─ enums/      DisputeStatus, DisputeReason, RulingCategory
│   └─ service/    ValidationDomainService (deterministic checks; pure Java)
│
com.hireai.application.biz.adjudication
│   ├─ validation/ ValidationAppService            (runs the gate in the callback tx)
│   ├─ dispute/    DisputeAppService               (open / apply-ruling / fallback)
│   └─ port/       ArbitrationGateway              (ACL port — async requestRuling)
│
com.hireai.infrastructure  (tech-grouped, existing convention)
│   ├─ messaging/  RabbitArbitrationClient        (publishes task.dispute.requested)
│   │              ArbitrationDlqListener          (exhausted → refund-fallback)
│   └─ (stub)      StubArbitrationClient           (Phase 2 + tests; deterministic)
│
com.hireai.controller.biz.adjudication
│   └─ ArbitrationCallbackController (POST /api/arbitration-callbacks/{disputeId}/ruling;
│                                     shared-secret auth, permitAll in chain)
│   (reject is reason-gated on the existing POST /api/tasks/{id}/reject; the dispute +
│    ruling are surfaced on the existing task-detail read — no separate client dispute route)
│
com.hireai.repository.biz.adjudication
│   └─ DisputeRepository, ValidationReportRepository  (+ DO/JpaRepo/Impl)
│
ledger.settlement  (existing)  ← ruling category → SettlementType mapping; SPLIT wired
│
arbitration/  (NEW repo dir — Python)
    FastAPI consumer on task.dispute.requested → LangGraph graph → ruling callback
```

**Transport topology.** `task.dispute.requested` queue (Java→Python, durable) + a ruling **callback** `POST /api/arbitration-callbacks/{disputeId}/ruling` (Python→Java, HTTPS, shared-secret). `correlation_id` threads Java→queue→Python→callback. Structurally identical to the existing agent dispatch→callback.

**Invariant boundaries.** Validation runs before the client sees a result (Inv #4). The LLM returns only a ruling *category* + rationale (Inv #3 — settlement computed in `ledger.settlement`). Every client-facing endpoint derives identity from JWT + owner-checks (Inv #5). The callback's shared secret is a *third* auth system, distinct from the user JWT and the agent dispatch token.

## 4. Phase 1 — the validation gate

**Aggregate.** `ValidationReport` (root): `taskId`, `Verdict` (`PASS`/`FAIL`), `1..*` `CheckResult` VOs `(rule, passed, detail)`. One report per task. `Verdict = PASS` iff every check passed.

**Deterministic checks** (`ValidationDomainService`, pure, no I/O), off the frozen `OutputSpec(format, schema, acceptanceCriteria)`:
- **Precondition:** callback `agentStatus == COMPLETED`. A self-reported agent failure → `FAILED` (refund), not the gate.
- **`TEXT`** → payload carries non-empty, non-whitespace text.
- **`JSON`** → payload parses as valid JSON; **and** if `output_spec.schema` itself parses as a JSON Schema, validate the payload against it (`com.networknt:json-schema-validator`). If `schema` is free prose, emit a `SCHEMA_SKIPPED` check (passed, with detail); structural JSON validity still enforced.
- **`FILE`** → `resultUrl` present and a well-formed **HTTPS** URL (no network fetch — stays deterministic).
- `acceptanceCriteria` is never machine-judged here.

**Where it runs.** Synchronously inside the existing callback flow, in one `@Transactional`: `recordResult` (→ `RESULT_RECEIVED`) → `ValidationAppService.validate(task)` → persist `ValidationReport` → transition. No external I/O → inline is atomic.

**Transitions.** `RESULT_RECEIVED → PENDING_REVIEW` (PASS). On **FAIL**: if `validation_attempts < adjudication.max-validation-retries` (default 1), increment the counter and **re-dispatch the same agent version** — task `→ QUEUED`, re-published to the existing dispatch queue (which mints a fresh signed token → `EXECUTING`); the next result is revalidated. Once the retry is exhausted and validation still fails → `SPEC_VIOLATION` → auto-refund (full 100%, no fee). Each attempt records its own `TaskResult` + `ValidationReport` (`UNIQUE(task_id, attempt_no)`), so the attempt trail is auditable; the result read returns the latest attempt.

**Client-review guard change.** `TaskModel.accept()/reject()` move from `requireStatus(RESULT_RECEIVED)` to `requireStatus(PENDING_REVIEW)`. `SPEC_VIOLATION` never reaches the client.

**Auto-refund extension.** Reuse the deterministic `SettlementType.REFUND` path for `SPEC_VIOLATION`, `TIMED_OUT`, and `FAILED` (closes escrow-stranding). Reputation events on these → Module 5.

**Auto-accept on client inaction.** When a task enters `PENDING_REVIEW`, stamp `tasks.review_deadline = now + window` (configurable `adjudication.review-window`, default **48h**; set tiny for demos). A minimal `@Scheduled` sweeper claims due tasks (`status='PENDING_REVIEW' AND review_deadline < now()`, `FOR UPDATE SKIP LOCKED`), re-asserts `PENDING_REVIEW` under the lock, and **auto-accepts** them — settle 85/15 to the builder, `→ RESOLVED`/`ACCEPTED`, logged as auto-accept. It runs as a trusted internal job (no JWT owner-check) using the task's own client/builder ids. This is the **only** piece of the reliability net pulled into Module 4; the dispatch-timeout sweeper, outbox/relay, and matching-retry stay deferred.

## 5. Phase 2 — dispute core + reason-gate

The **accept** path is unchanged (`PENDING_REVIEW → RESOLVED`, settle 85/15). **Reject** now requires a `DisputeReason`, gated *before* spending arbitration:

| Reason | Outcome |
|---|---|
| `D_CHANGED_MIND` | **Deterministic charge** — settle 85/15 to builder, no refund, no dispute, no LLM. (Behavior change from today's unconditional refund.) |
| `A_MISMATCH` / `B_FACTUAL` / `C_INCOMPLETE` | **Open a `Dispute`** → arbitrate. |

`tasks.reject_reason_category` records `A/B/C/D` uniformly (so `D`, which has no dispute row, is captured).

**`Dispute` aggregate.** Root: `taskId` (unique), `raisedBy`, `reasonCategory` (A/B/C), `DisputeStatus`, inline `Ruling` VO (nullable until ruled). State machine `OPEN → ARBITRATING → RULED → RESOLVED`, with `ESCALATED` defined-but-unused (tier-2 seam) and a `RESOLVED`-via-fallback path. `Task` gains `DISPUTED` (`PENDING_REVIEW → DISPUTED` on open; `DISPUTED → RESOLVED` on ruling/fallback).

**`Ruling` VO.** `(tier, category, rationale, decidedBy)`. Category ∈ `FULFILLED / PARTIALLY_FULFILLED / NOT_FULFILLED`; `decidedBy` ∈ `ARBITRATOR / FALLBACK`.

**Ruling → settlement (deterministic, `ledger.settlement`, Inv #3):**

| Category | Money outcome | Ledger |
|---|---|---|
| `FULFILLED` | Full budget settled 85/15 | `PAYOUT` + `COMMISSION` |
| `PARTIALLY_FULFILLED` | Half the budget refunded; the other half settled 85/15 (builder nets 42.5% of budget, platform 7.5%, client refunded 50%) | `SPLIT` (`REFUND` + `PAYOUT` + `COMMISSION`) |
| `NOT_FULFILLED` | Full refund, zero commission | `REFUND` |

`SPLIT` math reconciles exactly (refund-half + payout + commission = budget; commission half-up to 2dp).

**ACL port.** `ArbitrationGateway.requestRuling(dispute, task, outputSpec)`. Phase 2 binds the **`StubArbitrationClient`** (deterministic ruling, synchronous) so the entire flow is built and Testcontainer-tested before any Python exists.

## 6. Phase 3 — async arbitration transport + the Python LangGraph service

**Outbound.** `RabbitArbitrationClient` publishes `{disputeId, taskId, correlationId, output_spec, result_payload, reason}` to `task.dispute.requested` (durable), moves the dispute to `ARBITRATING`.

**Inbound.** `ArbitrationCallbackController` — `POST /api/arbitration-callbacks/{disputeId}/ruling`. **First-ruling-wins** (a second callback on a non-`ARBITRATING` dispute → 200 no-op). **Shared-secret + correlation-id** over HTTPS; `permitAll` in the chain, gated solely by the secret. **Strict-JSON** parse against `{category, rationale}` (the only fields the domain `Ruling` consumes; `tier`/`decidedBy` are set Java-side); malformed → rejected, treated as arbitrator failure (Inv #3). The arbitrator reads the **full** `output_spec` including `acceptanceCriteria` — the subjective judgement the validation gate deliberately skipped.

**Python service (`arbitration/`).** FastAPI consumer on `task.dispute.requested` → a **LangGraph graph**: `ingest (task + spec + result)` → `analyse against output_spec` → `deliberate` → `emit structured ruling`. Each LLM step calls **Claude** with structured output; the final node enforces the strict-JSON ruling contract before posting back. Provider key via env only.

**Termination guarantee (兜底).** A worker merely *down* → the message waits and runs when it returns (delayed, not stuck). *Persistent* failure (provider outage, malformed output that never parses, poison message, callback-delivery failure, contract/auth drift) → message exhausts bounded retries → **dead-letter queue** → a Java **`ArbitrationDlqListener`** resolves the dispute with a **full refund to the client** (platform's fault → don't charge), marks it `RESOLVED`-by-fallback, logs for investigation. Every dispute has exactly two exits (ruling, or refund-fallback); escrow is always released. No sweeper needed. `ESCALATED` stays the tier-2 seam.

## 7. Data model & migrations (additive)

**`V16__validation_reports.sql`** (Phase 1):
- `validation_reports` — `id` PK, `task_id` FK, `attempt_no` int, `verdict` text (`PASS`/`FAIL`), `checks` **jsonb** (`[{rule, passed, detail}]` — VO, so JSONB not a child table), `gmt_create`/`gmt_modified`. `UNIQUE(task_id, attempt_no)` (one report per attempt).
- `tasks.validation_attempts` int NOT NULL DEFAULT 0 — the retry counter (bounded by `adjudication.max-validation-retries`, default 1).
- `tasks.review_deadline` (nullable timestamptz) — stamped on entering `PENDING_REVIEW`; drives the auto-accept sweeper. Partial index on `(review_deadline) WHERE status = 'PENDING_REVIEW'`.
- **Alter `task_results`** — add `attempt_no` int (default 1); drop the existing `UNIQUE(task_id)`, add `UNIQUE(task_id, attempt_no)` so each retry's result is retained (additive migration; `V4` itself untouched).

**`V17__disputes.sql`** (Phase 2/3):
- `disputes` — `id` PK, `task_id` FK **unique**, `raised_by`, `reason_category` (`A_MISMATCH`/`B_FACTUAL`/`C_INCOMPLETE`), `status` (`OPEN`/`ARBITRATING`/`RULED`/`RESOLVED`/`ESCALATED`), inline ruling: `ruling_category` (nullable), `ruling_rationale`, `decided_by` (`ARBITRATOR`/`FALLBACK`), `ruling_tier`, `correlation_id`, timestamps. Indexes: unique(`task_id`), index(`status`).
- `tasks.reject_reason_category` (nullable text).

**No status columns change** — `tasks.status` is text; `DISPUTED`/`PENDING_REVIEW`/`SPEC_VIOLATION` are new string values (enum changes, schema doesn't). **`settlements` (V14) reused** — `SPLIT` populated.

**Append-only intact (Inv #2):** `disputes`/`validation_reports` are mutable state records (no append-only trigger). The money audit trail stays the append-only `ledger_entries` + `settlements`. (Tier-2 may later promote the inline ruling to a `dispute_rulings` child table for tier-1+tier-2 history; YAGNI now.)

## 8. State machines

**Task** (additions in **bold**): `… EXECUTING → RESULT_RECEIVED → ` **`PENDING_REVIEW`** (validation PASS) ` → RESOLVED` (accept / D-charge / **auto-accept on `review_deadline`**) **`| DISPUTED`** (reject A/B/C) **`→ RESOLVED`** (ruling/fallback). `RESULT_RECEIVED → ` **`QUEUED`** (validation FAIL, retry left → re-dispatch same agent) `| ` **`SPEC_VIOLATION`** (validation FAIL, retry exhausted → refund). `TIMED_OUT`/`FAILED` → refund.

**Dispute:** `OPEN → ARBITRATING → RULED → RESOLVED`; `ARBITRATING → RESOLVED` (DLQ refund-fallback); `ESCALATED` reserved (tier-2).

## 9. Error handling & edge cases

- **Validation:** `agentStatus != COMPLETED` → `FAILED`+refund. `JSON` unparseable → `FAIL` → `SPEC_VIOLATION`+refund. JSON-Schema violated → `FAIL`. Free-prose schema → `SCHEMA_SKIPPED`.
- **Reject:** missing reason → `400`. `D` → terminal charge (no later dispute). Duplicate dispute open → blocked by unique(`task_id`).
- **Ruling callback:** bad/expired secret → `401`; unknown `disputeId` → `404`; not `ARBITRATING` → `200` no-op; malformed → rejected (arbitrator failure).
- **Arbitrator failures** → bounded retry → DLQ → refund-fallback. No hang.
- **Settlement:** deterministic; pessimistic task-row lock; state guard blocks double-settle; `SPLIT` reconciles exactly (reconstruction test).
- **Auto-accept sweeper:** `FOR UPDATE SKIP LOCKED` + re-assert `PENDING_REVIEW` under the lock → safe against concurrent manual accept/reject and multiple app instances; a client who acts just before the sweep wins (status no longer `PENDING_REVIEW` → row skipped).
- **Spec-violation retry:** bounded by `validation_attempts` (default max 1); re-dispatch reuses the existing signed-token dispatch path; per-attempt `task_results`/`validation_reports` keyed by `attempt_no`; result read returns the latest attempt. **Caveat:** a retried task whose agent never responds sits in `EXECUTING` — the dispatch-timeout sweeper is deferred (a pre-existing gap with no caller today, not a regression).
- **Escrow never stranded:** every terminal path releases escrow.

## 10. Testing strategy

- **Domain unit:** `ValidationDomainService` per format × (schema present/absent/violated) + verdict aggregation; ruling→settlement for all three categories incl. `SPLIT` reconciliation; reason-gate (D-charge vs A/B/C-dispute); `Dispute`/`Task` state-machine guards; the retry counter / max-retry guard.
- **Retry path (integration):** validation fail → re-dispatch → second result **passes** → `PENDING_REVIEW`; fail → retry → fail again → `SPEC_VIOLATION` + full refund; max-retry guard stops at the configured limit; per-attempt result/report rows retained.
- **App-layer (mocked gateway):** gate transitions + auto-refund; `DisputeAppService` open/apply-ruling/fallback; reject-reason validation; callback idempotency.
- **Integration (Testcontainers PG + RabbitMQ):** full gate in callback (pass→`PENDING_REVIEW`; fail→`SPEC_VIOLATION`+refund); dispute happy path via `StubArbitrationClient` for all three categories; real Rabbit adapter (publish → simulated worker callback → settle); **DLQ refund-fallback**; callback auth (bad secret→401, duplicate→no-op); **auto-accept sweeper** (short window → due task auto-accepted 85/15; not-yet-due untouched; a task accepted/rejected before the sweep is skipped).
- **Contract test** for the Java↔Python boundary (shared request/response JSON fixture; strict-ruling parser rejects malformed).
- **Python service:** graph nodes + strict-JSON enforcement + FastAPI consumer; **Claude mocked in CI**; a couple of canned-response cases.
- 80% bar held; Testcontainers auto-skip without Docker (existing pattern).

## 11. Out of scope (deferred to future specs)

- **Tier-2 Adjudication** — client appeals, Administrator review, the **Admin dashboard** (its own spec; the `ESCALATED` state + a `dispute_rulings` child table are the seams).
- **Reliability net (§6.3), *except* the review auto-accept sweeper and the same-agent spec-violation re-dispatch (both now in Module 4)** — the dispatch `execution_deadline`/timeout sweeper, outbox/relay, and **matching-retry** (re-matching to a *different* agent) stay deferred. The 80/20 *fee* is dropped entirely; only the same-agent retry is kept, with a full refund if it still fails.
- **Reputation engine / Module 5** — `reputation_events` (append-only + trigger), rolling-decay score, `ReputationDroppedBelowThreshold` → auto-suspend, **reputation events** on `SPEC_VIOLATION`/`TIMED_OUT`/`FAILED`/dispute-loss, earned-review write path.
- **SSE real-time push** to the browser (cross-cutting; would also improve result-polling).

## 12. SAD reconciliation (apply when revising Notion)

- **§5.3** — keep the single same-agent retry on spec-violation but **remove the 80/20 fee**: a spec-violation that persists after the retry = **full refund** (no fee).
- **§6.4** — mark tier-1 (gate + reason-gate + LLM arbitration + deterministic settlement) as the built scope; tier-2 (appeals + Administrator + dashboard) as a separate future spec; arbitration transport is **async over RabbitMQ**; the arbitrator-failure 兜底 is **DLQ → refund-to-client** (no sweeper this cycle). Validation gate is **deterministic structural only** — `acceptanceCriteria` is not machine-judged.
- **§4.1 / §5.3 (Task status)** — `DISPUTED` becomes built (no longer "design-only"); `PENDING_REVIEW`/`SPEC_VIOLATION` reachable; `TIMED_OUT`/`FAILED`/`SPEC_VIOLATION` now auto-refund.
- **§6.4 / §4.1** — add **auto-accept-on-inaction** for `PENDING_REVIEW` (configurable review window, default 48h) via a minimal scheduled sweeper; new `tasks.review_deadline` column. Note this is the one reliability-net piece pulled forward.
- **§5.1 (ERD)** — `disputes` columns refined: structured `ruling_category` + `ruling_rationale` + `decided_by` + `ruling_tier` replace free-text `llm_ruling`/`admin_ruling`; add `validation_reports`; `tasks.reject_reason_category`.
- **§3.2 (Ubiquitous language)** — `Ruling` categories spelled `FULFILLED`/`PARTIALLY_FULFILLED`/`NOT_FULFILLED`.
```
