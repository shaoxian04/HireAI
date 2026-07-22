# Data model

Distilled from SAD §2.3–2.4. Notion SAD has the full schema table + ERD: https://app.notion.com/p/3752193af50f8111914bfdfb53b42135

## Aggregates (fine-grained, one repository per root)

| Aggregate root (`XxxModel`) | Children / value objects | Repository | Key invariant |
|---|---|---|---|
| **TaskModel** | TaskAttachmentModel, TaskResultModel; ExpectedDeliverable (VO) | TaskRepository | Legal status transitions only; result exists only after dispatch. |
| **AgentModel** | AgentVersionModel; OutputSpec, Pricing (VO) | AgentRepository | Exactly one active version; only ACTIVE agents receive tasks. |
| **WalletModel** | LedgerEntryModel (append-only) | WalletRepository | Balances never negative; escrow = sum of open freezes; ledger immutable. |
| **DisputeModel** | RulingModel (LLM + admin) | DisputeRepository | One dispute per task; settlement happens exactly once. |
| **ReputationModel** | ReputationEventModel (append-only) | ReputationRepository | Score in [0,100]; computed only from recorded events with decay. |
| **ReviewModel** | BuilderResponse (VO) | ReviewRepository | One review per resolved task; published only after content check. |

## Core tables (3NF; `gmt_create`/`gmt_modified` on every table)

- **users** — id, email (UK), password_hash, role (CLIENT/BUILDER/ADMIN), oauth_*, is_active.
- **wallets** — id, user_id (UK), available_balance, escrow_balance. Money is `NUMERIC`, never float.
- **ledger_entries** — append-only. entry_type (TOPUP/ESCROW_FREEZE/PAYOUT/REFUND/COMMISSION/SPLIT), amount, balance_after, related_task_id, correlation_id. **DB triggers raise on UPDATE/DELETE.**
- **agents** — id, owner_id, name, status, current_version_id, reputation_score.
- **agent_versions** — id, agent_id, version_number, output_spec (jsonb), capability_categories (text[]), webhook_url, max_execution_seconds, price, `max_concurrent` (builder-declared parallel-task cap 1–100, default 5, V24 — feeds the matcher's `loadHeadroom` factor). UNIQUE(agent_id, version_number).
- **tasks** — id, client_id, agent_version_id, category, expected_deliverable (jsonb), status, estimated_cost, retry_count, timestamps; `resolution` (ACCEPTED/REJECTED, V9), `resolved_at`, `rejection_reason` — set exactly once by client review (pessimistic row lock serializes concurrent attempts); `match_attempts`, `execution_deadline`, `pinned_agent_version_id` (V24 — the reliability-sweeper bookkeeping columns; deliberately **unmapped on the JPA entity**, written only by targeted native UPDATEs so a full-row save never nulls them).
- **task_attachments** / **task_results** — children of tasks; binaries in object storage, rows store the URL reference.
- **disputes** — task_id (UK), raised_by, reason_category, status, llm_ruling, llm_rationale, admin_ruling, admin_rationale, admin_agreed_with_llm.
- **reputation_events** — append-only. agent_id, event_type (TASK_SUCCESS/SPEC_VIOLATION/TIMEOUT/DISPUTE_LOSS), weight, occurred_at.
- **reviews** — task_id (UK), client_id, agent_id, rating (1–5), review_text, builder_response, is_published.

## Implemented so far (vs the design above)

The schema above is the **design target**. What's actually in Flyway today:

- **V1** — `users`, `wallets`, `ledger_entries` (append-only triggers). Wallet aggregate.
- **V2** — `tasks` MVP subset: `id, client_id (FK users), title, description, budget NUMERIC(14,2) CHECK > 0, output_spec JSONB NOT NULL, status, gmt_create/gmt_modified`, index `(client_id, gmt_create DESC)`. `output_spec` holds `{ format, schema, acceptanceCriteria }` (the binding contract, Invariant #4). Submit freezes `budget` in escrow **atomically** with the row insert. Still deferred: `estimated_cost`, `retry_count`, `task_attachments`.
- **V3** — `agents` (owner_id, name, status, current_version_id, reputation_score) + `agent_versions` (output_spec jsonb, capability_categories `text[]` with a GIN index, webhook_url, max_execution_seconds, price; `UNIQUE(agent_id, version_number)`). **Module 2 — Agent Registration.**
- **V4** — `ALTER tasks ADD agent_version_id UUID` (unconstrained — cross-track FK deliberately omitted) `+ category TEXT`; `task_results` (task_id UNIQUE FK, result_payload jsonb, result_url, agent_status, received_at). **Module 3 — Routing & Execution.** Task lifecycle now reaches `QUEUED → EXECUTING → RESULT_RECEIVED` (off-path `AWAITING_CAPACITY`/`TIMED_OUT`/`FAILED`).
- **V5** — seeds two demo users (`client@hireai.local` CLIENT, `builder@hireai.local` BUILDER; throwaway password `DemoPass123!`, BCrypt-hashed) + their wallets (client funded), for the thin JWT auth slice — `users.password_hash` is now used by `POST /api/auth/login`.
- **V6** — `agent_profiles` (1:1 with `agents`): `tagline, description, sample_output, logo_url, cover_url, gallery_urls text[], is_listed, is_featured`; backfills `is_listed=true` for already-ACTIVE agents. Catalogue visibility rule: `agents.status = ACTIVE AND agent_profiles.is_listed = true`. **Module 6 — Discovery.**
- **V7** — `reviews`: `task_id UUID NULL` (nullable so seeded demo rows need not reference a resolved task; UNIQUE deferred until the real review flow), `client_id, agent_id, rating (1–5), review_text, builder_response, is_published`. Seeds 3 reviews per demo agent via the demo client. Index `(agent_id, gmt_create DESC)`.
- **V8** — index `idx_tasks_agent_version ON tasks(agent_version_id)` to speed up per-agent stats queries joining tasks to agent versions.
- **V9** — `tasks.resolution TEXT CHECK (IN ('ACCEPTED','REJECTED'))`, `tasks.resolved_at TIMESTAMPTZ`, `tasks.rejection_reason TEXT`. **Module 5 settlement core — client review.**
- **V10–V23** — auth/RBAC (`user_roles`, `user_identities`, `users.display_name`), wallet `@Version`, `settlements` (`task_id` UNIQUE), agent-version status, `validation_reports`, partial resolution, `DISPUTED` status, result-payload text, `dispute_rulings` history, admin seed. (See `docs/details/build-status.md` for the per-migration narrative; these predate the matching-engine branch.)
- **V24** — **Matching engine + reliability.** `agent_versions.max_concurrent INT NOT NULL DEFAULT 5 CHECK (1..100)`; `tasks.match_attempts INT NOT NULL DEFAULT 0`, `tasks.execution_deadline TIMESTAMPTZ`, `tasks.pinned_agent_version_id UUID`; index `tasks(agent_version_id, status)` (backs the candidate query's per-agent in-flight/sample counts); one-time lowercase backfill of `agent_versions.capability_categories`. Enables the multi-factor scored matcher + the re-match / execution-timeout sweepers (see `docs/details/architecture.md` and `docs/matching-selection-mechanics.md`).
- **V25** — **Programmatic submission spine (API keys).** Three additive tables; `tasks`/`wallets`/`ledger_entries` untouched (Invariant #2 — spend caps are an authorization *read*, never a second ledger):
  - **`api_keys`** — `id, user_id (FK users), key_hash TEXT UNIQUE` (hex SHA-256 of the raw key — only the hash + a display prefix are ever stored), `display_prefix` (e.g. `hk_live_a1b2c3`), `name`, `spend_cap NUMERIC(18,2) NULL` (uncapped if null — max concurrent frozen escrow), `daily_spend_cap NUMERIC(18,2) NULL` (uncapped if null — max committed per rolling 24h), `status` (ACTIVE/REVOKED), `last_used_at`, `created_at`, `revoked_at`; index on `user_id`.
  - **`idempotency_keys`** — `id, owner_id, idempotency_key, request_fingerprint` (SHA-256 of the normalized submit payload), `task_id`, `created_at`, **`UNIQUE (owner_id, idempotency_key)`** — the concurrency arbiter: a duplicate submit racing under the same key hits this constraint inside the same transaction as the escrow freeze, so the whole submit (including the freeze) rolls back — no double-freeze — and the loser re-reads the winning row in a fresh transaction. Mirrors `settlements.task_id UNIQUE` (V14).
  - **`api_key_task`** — `task_id PK, api_key_id (FK api_keys), budget, created_at` — one row per task submitted via a key; a **soft `task_id` reference** (like `validation_reports`), not a Task-aggregate column, so the Task aggregate stays untouched; also the read source for both per-key spend-cap checks (concurrent + rolling-24h). Index on `api_key_id`.

  See `docs/details/identity-and-authz.md` for the API-key auth filter and `docs/programmatic-task-submission.md` for the feature design.
- **V26** — **Push webhooks (Phase 4).** Two additive tables; `tasks`/`wallets`/`ledger_entries` untouched — a webhook is enqueued as an outbox row written *inside the settling transaction*, never a second ledger (Invariant #2):
  - **`client_webhook_subscriptions`** — `id, api_key_id (FK api_keys), owner_id (FK users), callback_url, signing_secret, active, created_at/updated_at`. Partial UNIQUE `(api_key_id) WHERE active` enforces **≤1 ACTIVE subscription per key**; index on `owner_id`.
  - **`webhook_deliveries`** — the transactional outbox. `id` (PK, doubles as the client-facing `event_id`), `task_id (FK), owner_id (FK), subscription_id (FK), event_type` (`task.completed`/`task.failed`), `payload TEXT` (the whole signed body is built/stored/sent as a string — same TEXT-not-jsonb rationale as V20 `task_results.result_payload`), `target_url, status` (`PENDING`/`DELIVERED`/`DEAD`), `attempts, next_attempt_at, last_error, created_at, delivered_at`. Index `(status, next_attempt_at)` is the sweeper claim key (`FOR UPDATE SKIP LOCKED`); `(owner_id, created_at DESC)` backs the delivery-log read; `(task_id)` the per-task lookup.

  See `docs/details/architecture.md` (outbound webhook outbox + sweeper) and `docs/superpowers/specs/2026-07-19-push-webhooks-design.md` for the design.

## Status enums

- **Task:** SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED → PENDING_REVIEW → RESOLVED. Off-path: AWAITING_CAPACITY, TIMED_OUT, SPEC_VIOLATION, FAILED, CANCELLED. As of V24 the off-path states have live sweeper triggers: **AWAITING_CAPACITY** (no eligible agent) is re-matched every 10s and, after 3 exhausted attempts, transitions **→ CANCELLED + full refund**; a `QUEUED`/`EXECUTING` task past its `execution_deadline` transitions **→ TIMED_OUT + full refund**. Both were previously dormant/reserved. **Channel split (Phase 4):** a task submitted through the **API-key channel auto-settles on the validation result** — PASS → `RESOLVED` (85/15 payout), FAIL → `SPEC_VIOLATION` (refund) — *skipping* `PENDING_REVIEW` and the human accept/reject + dispute path entirely; a human-submitted task keeps `PENDING_REVIEW → RESOLVED` and the full review/dispute flow.
- **Agent:** PENDING_VERIFICATION → ACTIVE → SUSPENDED / DEACTIVATED.
- **Dispute reason:** A_MISMATCH, B_FACTUAL, C_INCOMPLETE, D_CHANGED_MIND. Reason D → deterministic no-refund; A/B/C → LLM arbitration.
- **Dispute ruling:** Fulfilled → release; Partially Fulfilled → 50/50 split; Not Fulfilled → full refund.
- **Dispute status:** OPEN → ARBITRATING → **RULED** (arbitrator ruling = *proposal*, escrow still held) → RESOLVED, plus **ESCALATED** (stranded via DLQ/sweeper, or the client's **appeal** of a RULED proposal). The effective ruling is the highest-tier `dispute_rulings` row (arbitrator tier-1, admin override tier-2).

## Settlement rules

- Platform commission: **15%**, deducted on release to the Agent. **IMPLEMENTED** by `SettlementPolicy` + `SettlementDomainService`:
  - **Accept** → client escrow releases `PAYOUT` (net 85%) + `COMMISSION` (15%); builder wallet credited net amount (wallet opened on first payout). Endpoint: `POST /api/tasks/{id}/accept`.
  - **Reject** → client escrow releases full `REFUND`. Endpoint: `POST /api/tasks/{id}/reject`.
  - Both paths are owner-gated (`TaskReviewAppService`), atomic, and protected against double-settlement by pessimistic task-row lock (`SELECT … FOR UPDATE`).
- **Disputes settle late (delayed settlement).** An `A/B/C` reject opens a dispute; the arbitrator's ruling is only a *proposal* (dispute rests at `RULED`, escrow held). The client then `POST /api/disputes/{id}/accept-ruling` or `/appeal` (→ admin tier-2), or an `@Scheduled RulingAcceptSweeper` auto-accepts a stale `RULED` proposal after `ruling-accept-after`. `settleFromEffective` moves money **exactly once**, from the highest-tier ruling, under the same `SELECT … FOR UPDATE` task lock — the arbitrator callback never settles. Reuses existing dispute statuses (no migration).
- Spec-violation after one retry: **80% refund to client, 20% platform fee** (pending — Module 4).
- Escrow invariant is reconstructable from `ledger_entries` at any time (used by the settlement reconstruction test).
- Reputation: rolling 30-day window — success rate (50%), spec-violation (−20%), timeout (−20%), dispute-loss (−10%) with temporal decay. Below threshold (or >30% dispute-loss) → auto-suspend via `ReputationDroppedBelowThresholdDomainEvent`.

## Known concurrency backlog

Neither `wallets` nor `tasks` carry an `@Version` column, so saves are last-writer-wins outside the locked path. The settlement path (accept/reject) is protected against double-payout by a pessimistic `SELECT … FOR UPDATE` on the task row: the second concurrent transaction blocks on the lock, re-reads the already-RESOLVED row, and the domain state guard throws before any money moves. Cross-task wallet mutations (e.g. two simultaneous top-ups, or accepts of two different tasks sharing the same wallet) remain last-writer-wins — tracked as platform hardening: add `@Version` to `WalletDO` and `TaskDO`. The V24 reliability sweepers (`CapacityRematchSweeper`, `ExecutionTimeoutSweeper`) drive their transition writes through unlocked reads and therefore assume a **single backend instance** (`@Scheduled(fixedDelay)` serializes within one JVM); before scaling to >1 replica, `assignAndQueue` / `cancelAwaitingCapacityWithRefund` must be made status-conditional or row-locked. Money is safe regardless — every escrow exit is a single recorded settlement backstopped by `settlements.task_id` UNIQUE. Likewise, the V25 per-key spend caps (`SubmitOrchestrationAppService.checkSpendCap`) read committed/daily spend with no per-key lock — a documented, accepted TOCTOU where two concurrent submits under the same key can slightly overshoot the cap, matching the matcher sweepers' single-instance assumption above.
