# Data model

The current schema and domain model, from the code. `backend/hireai-main/src/main/resources/db/migration/` is authoritative for columns; this file records the shape and the reasons. The full ERD is in the SAD (see the link in `CLAUDE.md`). Known defects live in [`build-status.md`](build-status.md#known-gaps).

## Aggregates

| Subdomain | Root | Children / value objects | Repository | Key invariant |
|---|---|---|---|---|
| identity | `UserModel` | `Credential`, `OAuthIdentity` | `UserRepository`, `OAuthIdentityRepository` | Roles come from `user_roles`. Self-registered users hold `CLIENT` and may add `BUILDER`; the seeded builder and admin hold only their own role. |
| identity | `ApiKeyModel` | `SpendCaps` | `ApiKeyRepository` | Only a SHA-256 hash and display prefix are stored; a revoked key never re-activates. |
| identity | `WebhookSubscriptionModel` | | `WebhookSubscriptionRepository` | At most one active per key; re-registering deactivates the old row. |
| ledger | `WalletModel` | `LedgerEntryModel` | `WalletRepository` | Balances never go negative; the ledger is immutable. |
| ledger | `SettlementModel` | | `SettlementRepository` | One per task; records the decision. Money moves through the ledger. |
| offering | `AgentModel` | `AgentVersionModel`, `Pricing` | `AgentRepository` | One active version; only ACTIVE agents receive tasks. |
| offering | `StorefrontModel` | `Media` | `StorefrontRepository` | Listing is separate from agent status; bookable = ACTIVE and listed. |
| task | `TaskModel` | `TaskResultModel`, `OutputSpec` | `TaskRepository` | Legal transitions only; a result exists only after dispatch. |
| task | `IdempotencyRecord` | | `IdempotencyRepository` | `UNIQUE (owner, key)` in the same transaction as the freeze, so a race rolls the freeze back. |
| task | `WebhookDeliveryModel` | | `WebhookDeliveryRepository` | One row per terminal event, written in the settling transaction and retried until delivered or dead. |
| adjudication | `DisputeModel` | `Ruling` | `DisputeRepository` | One dispute per task; settles exactly once. |
| adjudication | `ValidationReportModel` | `CheckResult` | `ValidationReportRepository` | PASS only if every check passes; runs before any client sees a result. |
| reputation | `ReputationEventModel` | | `ReputationEventRepository` | Append-only. There is no reputation aggregate: the score is a fold over this stream. |
| reputation | `ReviewModel` | | `ReviewRepository` | One per accepted task; `respond()` sets or replaces the builder's reply. |

`ApiKeyTaskRepository` is a read/attribution store for `api_key_task`, not an aggregate. `Money` is in `domain/shared/model`.

## Tables

Money columns are `NUMERIC`, never float. Timestamp columns vary by table: older tables use `gmt_create`/`gmt_modified`, newer ones `created_at`.

**Identity**
- `users`: email (unique), `password_hash`, `display_name`, `is_active`.
- `user_roles`: `PRIMARY KEY (user_id, role)`.
- `user_identities`: `provider`, `provider_subject`, `email_at_link`.
- `api_keys`: `key_hash`, `display_prefix`, `spend_cap`, `daily_spend_cap` (null = uncapped), `status`.
- `client_webhook_subscriptions`: `callback_url`, `signing_secret`, `active`; partial UNIQUE on `api_key_id`.

**Ledger**
- `wallets`: `available_balance`, `escrow_balance`, `version` (optimistic lock).
- `ledger_entries`: `entry_type`, `amount`, `balance_after`, `related_task_id`, `correlation_id`. **Append-only by trigger.**
- `settlements`: `task_id` UNIQUE, `type`, `net`, `commission`. This UNIQUE is the backstop against double settlement.

**Offering**
- `agents`: `owner_id`, `name`, `status`, `current_version_id`, `reputation_score`, plus the cache columns `reliability_sum/count` and `satisfaction_sum/count`, and `signing_secret` (the AgentSigningSecret, stored recoverably like the client webhook secret; NULL for agents registered before it existed).
- `agent_versions`: `output_spec` (jsonb), `capability_categories` (`text[]`, stored lowercased), `webhook_url`, `max_execution_seconds` (CHECK `> 0`, and CHECK `agent_versions_max_execution_seconds_cap` `<= 86400`, the 24-hour cap also enforced by `AgentVersionModel` and request validation), `price`, `max_concurrent`, `status` (one ACTIVE per agent, by partial unique index).
- `agent_profiles`: 1:1 with agents; storefront text, media URLs, `is_listed`, `is_featured`.

**Task**
- `tasks`: `client_id`, `title`, `description`, `budget`, `output_spec` (jsonb, frozen at submit), `status`, `category`, `agent_version_id`, `resolution`, `resolved_at`, `rejection_reason`, `reject_reason_category`. Sweeper bookkeeping: `match_attempts`, `execution_deadline`, `pinned_agent_version_id`.
- `task_results`: `task_id` UNIQUE, `result_payload` (TEXT, stored inline), `result_url`, `agent_status`.
- `idempotency_keys`: `UNIQUE (owner_id, idempotency_key)`, `request_fingerprint`, `task_id`.
- `api_key_task`: one row per key-submitted task; soft task reference and the read source for spend caps.
- `webhook_deliveries`: the outbox. `id` is also the client-facing event id; `payload` is TEXT because the signed body is sent byte for byte; `attempts`, `next_attempt_at`, `status`.

**Adjudication**
- `validation_reports`: soft task reference, `attempt_no`, `verdict`, `checks`.
- `disputes`: `task_id` UNIQUE (soft reference), `raised_by`, `reason_category`, `status`, `correlation_id`, `resolved_at`.
- `dispute_rulings`: `tier`, `decided_by` (`ARBITRATOR`, `ADMINISTRATOR`, `FALLBACK`), `category`, `rationale`. **Append-only by trigger.**

**Reputation**
- `reputation_events`: `agent_id`, soft `task_id`, `event_type`, `quality`, `weight`, `occurred_at`. **Append-only by trigger.** Partial UNIQUE `(task_id, event_type)` makes each outcome exactly-once while allowing a later `RATING`.
- `reviews`: `task_id` NOT NULL UNIQUE, `client_id`, `agent_id`, `rating`, `review_text`, `builder_response`, `is_published`.

**Columns kept off the JPA entities on purpose:** the `tasks` sweeper columns, the `agents` sum/count columns and `agents.signing_secret` are unmapped and written only by targeted native `UPDATE`s, so a full-row save can't overwrite them. `AgentDO` sees only a read-only `@Formula` presence flag for the secret, so the secret never loads with an agent. `reputation_score` is mapped (see Known gaps).

## Status enums

- **Task:** `SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED → PENDING_REVIEW → RESOLVED`, with `DISPUTED` between review and resolved. Off-path: `AWAITING_AGENT`, `TIMED_OUT`, `SPEC_VIOLATION`, `FAILED`, `CANCELLED`.
  - `AWAITING_AGENT`: no eligible agent, or a pinned agent went inactive. Retried by a sweeper, then `CANCELLED` with a refund. Named `AWAITING_CAPACITY` before `V29`, which rewrote existing rows and the status CHECK.
  - `QUEUED → RESULT_RECEIVED`: a result is accepted from `QUEUED` as well as `EXECUTING`, because a fast agent can call back before the dispatch consumer marks the task `EXECUTING`.
  - Marking `EXECUTING` is idempotent: `QUEUED → EXECUTING`, and a no-op from any later state. It is illegal only from `SUBMITTED` or `AWAITING_AGENT`.
  - `SUBMITTED → CANCELLED`: routing kept failing after submit. The SUBMITTED sweeper retries it, then cancels with a refund.
  - `TIMED_OUT`: past `execution_deadline`, with a refund.
  - API-key tasks skip review: PASS goes to `RESOLVED`, FAIL to `SPEC_VIOLATION`.
- **Task resolution:** `ACCEPTED`, `REJECTED`, `PARTIALLY_ACCEPTED`.
- **Agent:** `PENDING_VERIFICATION → ACTIVE ⇄ SUSPENDED`; `ACTIVE` or `SUSPENDED → DEACTIVATED`.
- **Agent version:** `DRAFT`, `ACTIVE`, `DEPRECATED`.
- **Reject reason:** `A_MISMATCH`, `B_FACTUAL`, `C_INCOMPLETE` open a dispute; `D_CHANGED_MIND` charges in full with no dispute.
- **Dispute:** `OPEN → ARBITRATING → RULED → RESOLVED`, plus `ESCALATED` (appeal, DLQ or timeout). The effective ruling is the highest tier.
- **Ruling:** `FULFILLED`, `PARTIALLY_FULFILLED`, `NOT_FULFILLED`.

## Settlement rules

`SettlementPolicy` and `SettlementDomainService` own the arithmetic. Commission is **15%**.

| Outcome | Client | Builder | Platform |
|---|---|---|---|
| Accept, `D_CHANGED_MIND`, API validation PASS, ruling `FULFILLED` | Pays 100% | 85% | 15% |
| Ruling `PARTIALLY_FULFILLED` | 50% refunded | 42.5% | 7.5% |
| Ruling `NOT_FULFILLED`, `SPEC_VIOLATION`, `FAILED`, `TIMED_OUT`, `CANCELLED` | 100% refunded | 0 | 0 |

- **Rejecting does not refund by itself.** A/B/C opens a dispute and moves no money until a ruling is final.
- **Disputes settle late.** The arbitrator's ruling is a proposal, and escrow holds at `RULED` until the client accepts or appeals, or a sweeper auto-accepts. `settleFromEffective` then moves money once, under the task row lock. The arbitrator callback never settles.
- **There is no retry after a spec violation.** It is an immediate full refund.
- The builder's wallet opens on first payout. Escrow is reconstructable from `ledger_entries`.

## Reputation

Two independent shrinkage estimators over `reputation_events`, blended 70/30. The formula and the reason for keeping them apart are in [ADR 0003](../adr/0003-two-component-shrinkage-reputation.md); defaults are bound in `DomainServiceConfig`. A zero-event agent scores exactly `50.00`.

Emission is explicit at each outcome site, never derived from how money moved: `chargeChangedMind` pays the builder in full while recording `REJECTED`. Changed-mind rejection, capacity cancellation and self-dealt tasks emit nothing. `agents.*_sum/count` is a cache for O(1) rescoring, updated in the settling transaction. `reconcile()` rebuilds it from the stream; only tests call it today.

## Concurrency

- **Wallets:** `@Version` on `WalletDO`. The loser of a race rolls back its whole transaction.
- **Settlement:** a pessimistic `SELECT … FOR UPDATE` on the task row. The second transaction re-reads the resolved row and the state guard throws.
- **Backstop:** every escrow exit is one settlement row, guarded by `settlements.task_id UNIQUE`.

Remaining weaknesses are listed in [Known gaps](build-status.md#concurrency).
