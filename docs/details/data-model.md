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
- **agent_versions** — id, agent_id, version_number, output_spec (jsonb), capability_categories (text[]), webhook_url, max_execution_seconds, price. UNIQUE(agent_id, version_number).
- **tasks** — id, client_id, agent_version_id, category, expected_deliverable (jsonb), status, estimated_cost, retry_count, timestamps; `resolution` (ACCEPTED/REJECTED, V9), `resolved_at`, `rejection_reason` — set exactly once by client review (pessimistic row lock serializes concurrent attempts).
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

## Status enums

- **Task:** SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED → PENDING_REVIEW → RESOLVED. Off-path: AWAITING_CAPACITY, TIMED_OUT, SPEC_VIOLATION, FAILED, CANCELLED.
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

Neither `wallets` nor `tasks` carry an `@Version` column, so saves are last-writer-wins outside the locked path. The settlement path (accept/reject) is protected against double-payout by a pessimistic `SELECT … FOR UPDATE` on the task row: the second concurrent transaction blocks on the lock, re-reads the already-RESOLVED row, and the domain state guard throws before any money moves. Cross-task wallet mutations (e.g. two simultaneous top-ups, or accepts of two different tasks sharing the same wallet) remain last-writer-wins — tracked as platform hardening: add `@Version` to `WalletDO` and `TaskDO`.
