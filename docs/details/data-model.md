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
| offering | `AgentEmbeddingModel` | `AgentProfileText` | `AgentEmbeddingRepository` | At most one vector per Agent, always 1536-dimensional and tagged with its model and text hash; replaced, never compared across models. |
| task | `TaskModel` | `TaskResultModel`, `OutputSpec` | `TaskRepository` | Legal transitions only; a result exists only after dispatch. |
| task | `TaskItems` ([Items](#items)) | `TaskItemModel`, `ItemCheck` | `TaskItemRepository` | 2..1000 Items numbered from 1; each Item settles once; changed only under the Task row lock. |
| ledger | `ItemSettlementModel` | | `ItemSettlementRepository` | One per Item (append-only); the money moves through the ledger. |
| task | `IdempotencyRecord` | | `IdempotencyRepository` | `UNIQUE (owner, key)` in the same transaction as the freeze, so a race rolls the freeze back. |
| task | `WebhookDeliveryModel` | | `WebhookDeliveryRepository` | One row per terminal event, written in the settling transaction and retried until delivered or dead. |
| task | `DispatchAttemptModel` | | `DispatchAttemptRepository` | Append-only; attempts numbered 1, 2, … per task under the task row lock. |
| adjudication | `DisputeModel` | `Ruling` | `DisputeRepository` | One dispute per task; settles exactly once. |
| adjudication | `ValidationReportModel` | `CheckResult` | `ValidationReportRepository` | PASS only if every check passes; runs before any client sees a result. |
| reputation | `ReputationEventModel` | | `ReputationEventRepository` | Append-only. There is no reputation aggregate: the score is a fold over this stream. |
| acceptance | `TemplateModel` | `TemplateParameter`, `ParameterBounds`, `TemplateRef` | `TemplateRepository` (read-only) | Platform-authored and versioned; a published version's definition never changes, only its status (`ACTIVE` → `RETIRED`). Only an ACTIVE version takes new Contracts. |
| reputation | `ReviewModel` | | `ReviewRepository` | One per accepted task; `respond()` sets or replaces the builder's reply. |

<a id="acceptance"></a>**Acceptance value objects** ([ADR 0009](../adr/0009-template-offer-contract-replaces-output-spec.md)), all in `domain/biz/acceptance`: a **`Contract`** (one `TemplateRef` plus `ParameterValue`s) is held by `TaskModel`, and an **`Offer`** (one `OfferTerm` per Template version, each with a `ParameterRange` per named parameter; its price is the version's `Pricing`) is held by `AgentVersionModel`. Each Template parameter declares bounds and one comparison, which fixes the value kind and how an Offer range reads: `max`/`min` (number; the range is a bound, covering Contract values ≤ / ≥ it), `subset` (set of strings; the range is a set, covering Contract sets inside it, or any value), `enum` (one string; a set or any value) and `flag` (boolean; whether `true` is accepted). A parameter an Offer term leaves out is accepted only when the Contract leaves it unset. The app checks every new Contract and Offer against its Template (`TemplateReadAppService.requireValid`), and that its terms can be checked (`ValidationDomainService.requireCheckable`: every schema is a valid JSON Schema, spec D8; `min_length` ≤ `max_length`). Today the Contract is derived from the output spec by `OutputSpec.toContract()`, and so is a new Agent's first Offer, by `toOwnOffer()` (spec D39: TEXT → `text_basic@1`, FILE → `file_basic@1`, JSON → `custom_json@1`, whose Offer accepts only the Agent's own schema). A Builder may replace the Offer when publishing a version (`AgentVersionModel.supersededBy(…, Offer)`); the new Offer must still name the Template of the Agent's own Contract and accept the values it sets, because direct bookings freeze that Contract. Automated validation reads the Contract (falling back to `output_spec` on a task without one); arbitration and matching still read `output_spec`, except that arbitration judges a FILE result's [DeliverySnapshot](#delivery-snapshots), not its link.

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
- `ledger_entries`: `entry_type`, `amount`, `balance_after`, `related_task_id`, `correlation_id`. **Append-only by trigger.** `ARBITRATION_FEE` and `APPEAL_FEE` rows are a loser's fee debit, recorded negated like `ESCROW_FREEZE` (the per-type rules are on `LedgerEntryModel`). `AI_REVIEW_FEE` releases a Client's frozen [AI Review](#ai-review) fee from escrow to the platform, like `COMMISSION`; its freeze, charge or refund carries correlation `ai-review-<task>`.
- `dispute_fees`: one row per dispute and fee type (`ARBITRATION_COST`, `APPEAL_FEE`): full cost, the 20%-of-price cap, what the Client and the Builder were charged and what the platform covered (they sum to the full cost). **Append-only by trigger.**
- `settlements`: `task_id` UNIQUE, `type`, `net`, `commission`. This UNIQUE is the backstop against double settlement.

**Offering**
- `agents`: `owner_id`, `name`, `status`, `current_version_id`, `reputation_score`, plus the cache columns `reliability_sum/count` and `satisfaction_sum/count`, and `signing_secret` (the AgentSigningSecret, stored recoverably like the client webhook secret; NULL for agents registered before it existed).
- `agent_versions`: `output_spec` (jsonb), `offer` (jsonb, NOT NULL since `V39`, which backfilled it from `output_spec` with the SQL function `offer_from_output_spec`; a row inserted without one gets the same mapping from a trigger), `capability_categories` (`text[]` of [category](#categories) slugs), `webhook_url`, `max_execution_seconds` (CHECK `> 0`, and CHECK `agent_versions_max_execution_seconds_cap` `<= 86400`, the 24-hour cap also enforced by `AgentVersionModel` and request validation), `price`, `max_concurrent`, `status` (one ACTIVE per agent, by partial unique index).
- <a id="categories"></a>`categories`: the platform-owned category list ([ADR 0006](../adr/0006-matching-category-filter-relevance-floor.md)): `slug` (PK, lowercase-hyphenated), `label`, `sort_order`. Seeded and changed **only by Flyway**; there is no write path in the app. It includes the launch vertical `document-extraction` ([ADR 0004](../adr/0004-launch-vertical-document-grounded-extraction.md)) and `other`. Every entry point that takes a category (registration, version publish, routed submit, a direct booking's optional category, match-preview) validates against it in the application layer (`CategoryList.requireKnown`); the database does not constrain `capability_categories` or `tasks.category`. `V35` mapped earlier free-text tags and task categories onto slugs (known spellings to their bucket, everything else to `other`).
- `category_suggestions`: a Builder's optional `suggestedCategory` free text from registration or publish, kept for admins: soft `agent_id`, `agent_version_id`, `owner_id`, `suggested_category` (1–100 chars), `gmt_create`. Never used for matching.
- `agent_profiles`: 1:1 with agents; storefront text, media URLs, `is_listed`, `is_featured`.
- <a id="text-embeddings"></a>`text_embeddings` (`V38`): cache of task-text vectors for [relevance matching](matching.md#relevance), PK (`text_hash`, `model`), `embedding vector(1536)`, `gmt_create`. The text is the task's title and description, trimmed, blanks dropped, joined by newlines (`TaskText`); only its SHA-256 hex is stored, never the text. Inserted once (`ON CONFLICT DO NOTHING`), never updated; a new model gets its own row.
- <a id="agent-embeddings"></a>`agent_embeddings`: the Agent's profile vector, needing the **`vector` extension (pgvector)**, which `V36` enables. `agent_id` (PK, FK to `agents`, cascade delete), `model`, `text_hash`, `embedding vector(1536)`, `updated_at`. The embedded text is the name, tagline and description, trimmed, blanks dropped, joined by newlines (`AgentProfileText`); `text_hash` is its SHA-256 hex. A row is stale when its hash no longer matches the current profile text or its model isn't the configured one (`hireai.embedding.model`); the re-embed sweeper replaces it. Mutable (upserted), not an audit trail. No ANN index.

**Acceptance**
- `contract_templates` (`V39`): PK (`template_key`, `version`), `status` (`ACTIVE`/`RETIRED`), `definition` (jsonb: title, description, output shape, Check Library entry names, parameters with bounds and comparison, expectation mode, the fraction of the price paid under each ruling), `published_at`. Seeded and changed **only by Flyway**; a trigger rejects deletes and any change but `status`. The one exception so far: `V41` lifted it for one statement to add `text_basic@1`'s optional `min_length`/`max_length`, which changed the meaning of no frozen Contract or Offer (the migration says why). The stored JSON shapes of Contracts and Offers are documented in the `V39` migration.
- `delivery_snapshot_contents` (`V44`): the snapshot bytes, PK `sha256` (content-addressed, so equal files are stored once), `content` BYTEA, `size_bytes`. Private: served only by the owner-checked `GET /api/tasks/{id}/deliverable` and the admin `GET /api/admin/disputes/{id}/deliverable` (attachment, `nosniff`, sandbox CSP, Content-Type from an allowlist), and inlined as base64 into the arbitration request (`ArbitrationDeliverable`, up to `hireai.arbitration.inline-deliverable-max-bytes`), where an image or PDF is shown to the model as content ([`arbitration/README.md`](../../arbitration/README.md#evidence)). UPDATE is refused by trigger; DELETE is left for D32 retention, which keeps the hash in `delivery_snapshots`. The `DeliveryContentStore` port owns it: moving the bytes to a private bucket replaces only `PostgresDeliveryContentStore`.

**Task**
- `tasks`: `client_id`, `title`, `description`, `budget`, `output_spec` (jsonb, frozen at submit), `contract` (jsonb, frozen at submit since `V39`; NULL on earlier tasks, which finish under their `output_spec`; a trigger rejects any change once set), `status`, `category`, `agent_version_id`, `resolution`, `resolved_at`, `rejection_reason`, `reject_reason_category`, `failure_reason`. Sweeper bookkeeping: `match_attempts`, `execution_deadline`, `pinned_agent_version_id`. `client_deadline` (nullable): the Client's deadline, fixed at submit from `deadlineSeconds` and applied at every selection ([eligibility](matching.md#client-deadline)). `review_deadline` (nullable): when the Client's review window closes, stamped as a web-channel task enters `PENDING_REVIEW`; `review_auto_accepted` marks a result the review-window sweeper accepted because that window closed unreviewed. `review_reason` (nullable): `ANOMALY` or `UNDECIDED` when the result awaits review because the [acceptance engine](#acceptance-engine) sent it there rather than because every check passed, or `AI_REVIEW_UNAVAILABLE` when a decisive [AI Review](#ai-review) gave no usable verdict; shown as `reviewReason` on `GET /api/tasks/{id}`. `failure_detail` (nullable, `V46`): why no Agent matched, in plain words, written by each no-match routing pass while the task waits `AWAITING_AGENT` and kept when it ends `NO_ELIGIBLE_AGENT` ([no-match explanation](matching.md#offer-coverage)).
- <a id="items"></a>`task_items` (multi-Item Tasks, spec S3): soft `task_id`, `item_no` (1-based, `UNIQUE (task_id, item_no)`), `input`, `price` (the Item's share of the escrow), `status`, the Agent's `agent_status`/`result_payload`/`result_url`, `checks` (jsonb: the Contract's checks as run on this Item), `auto_accepted`. A Task without rows is a single-deliverable Task, unchanged; a multi-Item Task's `tasks.budget` is the escrow of record (price × count), so every whole-Task refund path returns exactly what was frozen. Matching compares an Agent's price with `budget / count`. The per-Item price must be at least **0.04** (`TaskItems.MIN_ITEM_PRICE`, 400 below it), the lowest price whose 15% commission does not round to 0.00, because commission is rounded per Item; single-deliverable Tasks keep their own minimum. Each Item's release is net + commission = price and a refund is the price, so for any mix of outcomes the Items' ledger exits add up to exactly the frozen escrow. An Item's `result_url` must be an absolute https URL (the callback is a 400 otherwise).
- `task_item_settlements`: one row per settled Item, `item_id` UNIQUE (the backstop against settling an Item twice), `type` (`ACCEPT`/`REJECT`), `net`, `commission`, `refund`. **Append-only by trigger.** Its ledger entries carry `correlation_id = settle-<task id>-item-<item no>`. A multi-Item Task writes no `settlements` row.
- `receipts`: one signed receipt per settled subject (`task_id`, `item_no` NULL for a whole Task; unique on both), `key_id`, `body` (the exact RFC 8785 text that was signed, TEXT not jsonb), `signature`, `issued_at`. **Append-only by trigger.** Format and issuance: [`receipts.md`](receipts.md).
- `task_results`: `task_id` UNIQUE, `result_payload` (TEXT, stored inline), `result_url`, `agent_status`.
- `idempotency_keys`: `UNIQUE (owner_id, idempotency_key)`, `request_fingerprint`, `task_id`.
- `api_key_task`: one row per key-submitted task; soft task reference and the read source for spend caps.
- `webhook_deliveries`: the outbox. `id` is also the client-facing event id; `payload` is TEXT because the signed body is sent byte for byte; `attempts`, `next_attempt_at`, `status`.
- <a id="dispatch-attempts"></a>`dispatch_attempts`: the task's dispatch history, one row per Agent version the platform tried to hand the task to. Soft `task_id` and `agent_version_id` (no FK), `attempt_number` (1-based, `UNIQUE (task_id, attempt_number)`, numbered under the task row lock), `outcome`, `occurred_at` (when the outcome was known), `decline_reason` (only on `CONTRACT_DECLINED`, optional, at most 500 characters, since `V43`), `gmt_create`. **Append-only by trigger.** Outcomes (`DispatchOutcome`, CHECK since `V33`, last widened in `V43`):
  - `ACCEPTED`: the Agent replied 2xx to the WorkOrder (recorded by the dispatch consumer after the send).
  - `UNREACHABLE`: every send in the retry schedule failed (recorded in the DLQ transaction that fails the task over or ends it `FAILED`).
  - `BUSY`: the Agent replied 429/503 to the WorkOrder (recorded in the same transaction that fails the task over or ends it `FAILED` with `AGENT_BUSY`).
  - `CONTRACT_DECLINED`: the Agent replied 422 with `{"decline":"CONTRACT","reason":…}` (#93; recorded in the same transaction that fails the task over or ends it `FAILED` with `CONTRACT_DECLINED`). The reason is the Agent's text, trimmed, control characters turned to spaces, cut to 500 characters.
  - A routed task's history is also its failover exclusion list: no Agent in it is picked again ([routed failover](matching.md#routed-failover)).
  - `UNAVAILABLE`: the agent was not `ACTIVE`, or its version was superseded, at dispatch (recorded in the same transaction that ends a pinned task `CANCELLED` with `AGENT_UNAVAILABLE`, or that fails a routed task over).
  - Read-only to the task owner (JWT or API key) at `GET /api/tasks/{id}/attempts`: attempt number, `agentId` and `agentName` (joined from the version), `agentVersionId`, outcome, `occurredAt`, `declineReason`. Another client gets 404.
  - The **public contract-decline rate** is read from it (`DispatchAttemptRepository.declineRatesByAgentId`, `ContractDeclineRate`): per Agent over all its versions, `declined` = `CONTRACT_DECLINED` rows, `answered` = `ACCEPTED` + `CONTRACT_DECLINED` rows (busy, unreachable and unavailable say nothing about the Contract), `rate` null when `answered` is 0. Shown as `contractDeclines` on match-preview options and in the catalogue profile `stats`; it is not a reputation event and not a ranking input.

**Adjudication**
- `validation_reports`: soft task reference, `attempt_no`, `verdict` (`PASS`, `FAIL`, `REVIEW`), `checks` (each `{rule, passed, detail, outcome}`, outcome `PASS`/`REJECT`/`REVIEW`; rows written before outcomes existed have none and read it from `passed`), `findings` (`[{code, detail}]`) and `anomalies` (`[{check, description}]`, what the Agent self-reported with that result). See [Acceptance engine](#acceptance-engine).
- `task_rejections` (`V42`): one row per A/B/C rejection round, UNIQUE (`task_id`, `round`): `reason_category`, `reason`, `rejected_at`, `response_deadline`, `revision_allowed`, `response` (`REVISE`/`CONCEDE`/`STAND_BY`), `response_reason`, `response_silent`, `responded_at`, `decision_deadline`, `decision` (`ACCEPT`/`ARBITRATE`), `decision_silent`, `decided_at`; `replaced_result_*` (what a revision replaced); `notice_sent_at`/`notice_attempts` (notice delivery, retried up to 5 times while the window is open).
- `disputes`: `task_id` UNIQUE (soft reference), `raised_by`, `reason_category`, `status`, `correlation_id`, `resolved_at`.
- `dispute_rulings`: `tier`, `decided_by` (`ARBITRATOR`, `ADMINISTRATOR`, `FALLBACK`), `category`, `rationale`. **Append-only by trigger.**

**Reputation**
- `reputation_events`: `agent_id`, soft `task_id`, `event_type`, `quality`, `weight`, `occurred_at`. **Append-only by trigger.** Partial UNIQUE `(task_id, agent_id, event_type)` (since `V34`) makes each outcome exactly-once per Agent while allowing a later `RATING`; per Agent because routed failover can give one task several unreachable Agents, each with its own `EXECUTION_FAILED`.
- `reviews`: `task_id` NOT NULL UNIQUE, `client_id`, `agent_id`, `rating`, `review_text`, `builder_response`, `is_published`.

**Columns kept off the JPA entities on purpose:** the `tasks` sweeper columns, the `agents` sum/count columns and `agents.signing_secret` are unmapped and written only by targeted native `UPDATE`s, so a full-row save can't overwrite them. `AgentDO` sees only a read-only `@Formula` presence flag for the secret, so the secret never loads with an agent. `reputation_score` is mapped (see Known gaps).

## Acceptance engine

How a result is judged as it arrives (`ValidationAppServiceImpl.validateAndGate`, spec S5). Code: `domain/biz/acceptance/check/` (`CheckLibrary`) and `ValidationDomainServiceImpl`.

- **Checks** come from the Check Library only; a Template lists the entries it may use, and an entry runs only when the Contract sets its parameters (or always, like `IMAGE_FORMAT`). Each returns **pass**, **reject** or **send to review** (it could not decide, e.g. pixels of a WebP the platform cannot decode).
- **Verdict:** any reject → `FAIL`; else any review → `REVIEW`; else `PASS`. `PASS` and `REVIEW` both go to `PENDING_REVIEW` with a review window; only `PASS` auto-settles an API-key task.
- **Findings** (e.g. `DUPLICATE_BRIEF`: the Client sent the same title and description before) read other Tasks, so they are stored beside the verdict and never count towards it: no money moves on a Finding (D7).
- **Self-reported anomalies** (`anomalies` on the result callback) turn a rejection of a Template's *anomaly-eligible* check (its definition's `anomalyEligible`; `image_generation@1`: the background colour) into review; the Client's accept confirms it and pays. They never soften link, fetch, format or size checks. An anomaly naming no check of the result adds a `SELF_REPORTED_ANOMALY` review entry.
- **Rework (D17):** a `FAIL` goes back to the same Agent version (QUEUED, a new execution deadline, a WorkOrder carrying [`rework`](matching.md#the-workorder)) while the task's rejected results number at most `hireai.acceptance.max-reworks` (2). The next `FAIL` (or a version that can no longer take work) ends `SPEC_VIOLATION`: refund plus one `SPEC_VIOLATION` reputation event. Reworks move no money and write no reputation event. A check rejection is not appealable: the Client never sees the rejected result.
- **Pixels** are read by the `ImageDecoder` port (`ImageIoImageDecoder`): header first; over 4096 px a side (the Template's bound) only the size is read and `IMAGE_FORMAT` rejects it whatever the Contract asks (so a declared huge canvas cannot dodge the pixel checks), else just the edge, so memory stays bounded. It fails closed: a PNG or JPEG whose header or pixel data will not decode is rejected by `IMAGE_FORMAT` and the pixel checks (reworked, never reviewed), its size still judged from the header when readable; only WebP, allowed but not decodable, sends the pixel checks to review.
- **Expectation mode** is the Template's (`image_generation@1`: `BUSINESS_VALID`); a Contract-level override arrives with explicit Contracts.

## Status enums

- **Task:** `SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED → PENDING_REVIEW → RESOLVED`, with the rejection response path (`AWAITING_AGENT_RESPONSE`, `AWAITING_CLIENT_DECISION`, since `V42`) and `DISPUTED` between review and resolved. Off-path: `AWAITING_AGENT`, `TIMED_OUT`, `SPEC_VIOLATION`, `FAILED`, `CANCELLED`.
  - `AWAITING_AGENT`: no eligible agent for a routed task. Retried by a sweeper, then `CANCELLED` with a refund. Named `AWAITING_CAPACITY` before `V29`, which rewrote existing rows and the status CHECK.
  - `QUEUED → RESULT_RECEIVED`: a result is accepted from `QUEUED` as well as `EXECUTING`, because a fast agent can call back before the dispatch consumer marks the task `EXECUTING`.
  - Marking `EXECUTING` is idempotent: `QUEUED → EXECUTING`, and a no-op from any later state. It is illegal only from `SUBMITTED` or `AWAITING_AGENT`.
  - `SUBMITTED → CANCELLED`: routing kept failing after submit. The SUBMITTED sweeper retries it, then cancels with a refund.
  - `TIMED_OUT`: past `execution_deadline`, with a refund.
  - API-key tasks skip review: PASS goes to `RESOLVED`, FAIL to `SPEC_VIOLATION`.
  - `PENDING_REVIEW → RESOLVED` (`ACCEPTED`) without the Client: once `review_deadline` passes by the app's Clock, `ReviewWindowSweeper` settles the task exactly as a Client's accept would (85/15, `TASK_ACCEPTED`), under the task row lock, so it settles once. A reject after the deadline is refused (409).
  - <a id="rejection-response"></a>Rejection response path (#95, spec D20). An A/B/C reject moves `PENDING_REVIEW → AWAITING_AGENT_RESPONSE` and opens a `task_rejections` round; the Agent is sent a signed notice ([shape](matching.md#rejection-notice)). Within the Agent's window:
    - **revise** (once per task): the revision replaces the `task_results` row (the replaced one is kept on the round) and goes `RESULT_RECEIVED →` validation again (attempt 2), so back to `PENDING_REVIEW` with a fresh review window, or `SPEC_VIOLATION`. A later A/B/C reject opens round 2 with `revision_allowed = false`.
    - **concede**: `→ RESOLVED` (`REJECTED`), full refund, `REJECTION_CONCEDED` (quality 0).
    - **stand by**, or silence until `response_deadline` (`response_silent`): `→ AWAITING_CLIENT_DECISION`. The Client then accepts (`→ RESOLVED`, 85/15, `TASK_ACCEPTED`), requests arbitration (`→ DISPUTED`, the dispute opens with the round's reason; no deposit), or stays silent until `decision_deadline`, which accepts (`decision_silent`).
    Every transition takes the task row lock; a response or decision after its window closed is refused (409).
- **Task resolution:** `ACCEPTED`, `REJECTED`, `PARTIALLY_ACCEPTED` (a ruling split, or a multi-Item Task where some Items were refunded).
- **Item** (multi-Item Tasks): `AWAITING_RESULT → PENDING_REVIEW` (its checks passed) `→ ACCEPTED`; or `AWAITING_RESULT → SPEC_VIOLATION` (checks failed) / `FAILED` (the Agent reported it failed or left it out), both refunded at once. The Task aggregates them: the callback moves it to `PENDING_REVIEW` when any Item passed (an API task's passing Items are paid at once), else `SPEC_VIOLATION`; it is `RESOLVED` once every Item settled, `ACCEPTED` if all were accepted, else `PARTIALLY_ACCEPTED`. The Client accepts Items one at a time (`POST /api/tasks/{id}/items/{n}/accept`) or all at once (`POST /api/tasks/{id}/accept`); the review window, **counted in business days** (Monday to Friday, UTC: `ReviewWindow.closesAtCountingBusinessDays`), accepts the rest. Reject is refused (409). A Task that ends without a result (timeout, cancel, Agent failure) refunds its whole escrow at once and its Items stay `AWAITING_RESULT`.
- <a id="failure-reason"></a>**Failure reason** (`tasks.failure_reason`, `FailureReason`; CHECK since `V32`, last widened in `V43`): why a task ended without a delivered result. The terminal transition sets it, and `TaskModel` refuses to set it a second time. `NULL` on live and successful tasks, and on tasks that ended before `V32`. Where each value comes from today:
  - `AGENT_UNREACHABLE`: every dispatch send attempt to a direct booking's Agent failed (DLQ) → `FAILED`.
  - `AGENT_REPORTED_FAILURE`: the Agent's callback reported a non-`COMPLETED` status → `FAILED`.
  - `SPEC_VIOLATION`: the result failed validation → `SPEC_VIOLATION`.
  - `EXECUTION_TIMEOUT`: past the deadline while `EXECUTING` → `TIMED_OUT`.
  - `DISPATCH_ABANDONED`: past the deadline while still `QUEUED` → `TIMED_OUT`; or routing failed on every SUBMITTED sweep → `CANCELLED`.
  - `NO_ELIGIBLE_AGENT`: a routed task's re-match attempts ran out → `CANCELLED`; or a failover hop found untried Agents but the client deadline ruled them all out → `FAILED`.
  - `AGENT_UNAVAILABLE`: at dispatch (routing, or the check before any send), a direct booking's agent is not `ACTIVE` or its pinned version was superseded → `CANCELLED` at once, no reputation event.
  - `AGENT_BUSY`: a direct booking's Agent declined the WorkOrder with 429/503 → `FAILED`, no reputation event.
  - `CONTRACT_DECLINED`: a direct booking's Agent declined the task's Contract (422 with `{"decline":"CONTRACT"}`) → `FAILED`, no reputation event.
  - `NO_ALTERNATIVE_AGENT`: a routed task's Agent was busy, declined the Contract or was unreachable and no untried eligible Agent was left, or 3 Agents had been tried ([routed failover](matching.md#routed-failover)) → `FAILED`.
- **Agent:** `PENDING_VERIFICATION → ACTIVE ⇄ SUSPENDED`; `ACTIVE` or `SUSPENDED → DEACTIVATED`.
- **Agent version:** `DRAFT`, `ACTIVE`, `DEPRECATED`.
- **Reject reason:** `A_MISMATCH`, `B_FACTUAL`, `C_INCOMPLETE` start the [rejection response path](#rejection-response) (arbitration only if the Agent stands by and the Client asks for it); `D_CHANGED_MIND` charges in full with no dispute.
- **Dispute:** `OPEN → ARBITRATING → RULED → RESOLVED`, plus `ESCALATED` (appeal, DLQ or timeout). The effective ruling is the highest tier. At `RULED` only the side(s) the ruling goes against may act (`RulingCategory.partiesItGoesAgainst`: `FULFILLED` → Client, `NOT_FULFILLED` → Builder, a split → both): each accepts (`client_accepted_at` / `builder_accepted_at`) or one appeals (`appealed_by`, once). It settles when all of them have accepted.
- **Ruling:** `FULFILLED`, `PARTIALLY_FULFILLED`, `NOT_FULFILLED`.

## AI Review

An optional, Client-paid judgement of criteria code cannot check (spec S10, D5, D37, D38). Code: `domain/biz/acceptance/aireview/`, `AiReviewAppServiceImpl`, the `AiReviewerPort` (default adapter `OpenAiReviewerClient`).

- **Booking only, at submit:** `aiReview: {criteria: [1..5]}` on `POST /api/tasks/direct` (web or API key). It is offered only when the Agent's Offer has `acceptsAiReview` and the fee is at most `hireai.ai-review.max-fee-share` (10%) of the price; `POST /api/tasks/direct/ai-review-quote` says what it would cost and why it is not offered. Not on a multi-Item Task.
- **Fee** (`AiReviewPolicy`): `(base-fee + fee-per-criterion × criteria)`, × `media-multiplier` when the Template's output is a file. It is frozen in the Contract (`contract.aiReview {criteria, fee}`) and in escrow beside the price. It is charged (`AI_REVIEW_FEE`) once a verdict exists and refunded otherwise: the reviewer gave no usable verdict, the Client decided first, or the Task ended before any result passed its checks (`AiReviewSweeper` and the `SPEC_VIOLATION` path).
- **When it runs:** after a result passes or is sent to review by the deterministic checks, off the callback thread (`AiReviewListener`). The reviewer judges the frozen DeliverySnapshot (an image or PDF as model content) or the text result, fenced as untrusted evidence between markers carrying a fresh nonce, with forged markers redacted.
- **Verdict:** a pass or fail with a reason for every criterion, mapped in the domain: all pass → `FULFILLED`, some → `PARTIALLY_FULFILLED`, none → `NOT_FULFILLED`. **Web:** an advisory pre-review report (`GET /api/tasks/{id}/ai-review`); the Client still accepts or rejects. **API/MCP:** decisive and final; it settles through the same paths as a dispute ruling (85/15, split, refund) with reputation `AI_REVIEW_PASSED` / `AI_REVIEW_PARTIAL` / `AI_REVIEW_FAILED`, and nobody may accept or reject the task while it decides.
- **Fails closed:** an answer is used only if it judges every criterion exactly once, with a reason. An unusable answer (refusal, malformed, a criterion missing or repeated) is retried once; a transient failure is retried with a doubling backoff, `max-attempts` in all. Then the review is `FAILED`, the fee refunded, and nothing settles: on the web the Client reviews without a report; on API/MCP the result waits in `PENDING_REVIEW` with `review_reason = AI_REVIEW_UNAVAILABLE` and a review window.
- **Table** `ai_reviews` (one row per Task): status `FROZEN → REQUESTED → COMPLETED | FAILED | RELEASED`, `decisive`, `attempts`, `verdicts`, `ruling_category`, `model`.

## Settlement rules

`SettlementPolicy` and `SettlementDomainService` own the arithmetic. Commission is **15%**.

| Outcome | Client | Builder | Platform |
|---|---|---|---|
| Accept, `D_CHANGED_MIND`, API validation PASS, ruling or decisive AI Review `FULFILLED` | Pays 100% | 85% | 15% |
| Ruling or decisive AI Review `PARTIALLY_FULFILLED` | 50% refunded | 42.5% | 7.5% |
| Ruling or decisive AI Review `NOT_FULFILLED`, `SPEC_VIOLATION`, `FAILED`, `TIMED_OUT`, `CANCELLED` | 100% refunded | 0 | 0 |

An [AI Review](#ai-review) fee is separate from this table: the Client pays it to the platform once the review ran, whatever the verdict.

- **Rejecting does not refund by itself.** A/B/C moves no money until the Agent concedes, the Client accepts, or a ruling is final.
- **Disputes settle late.** The arbitrator's ruling is a proposal, and escrow holds at `RULED` until every side it goes against accepts it, one of them appeals, or a sweeper auto-accepts (`hireai.arbitration.ruling-accept-after`). `settleFromEffective` then moves money once, under the task row lock. The arbitrator callback never settles.
- **Loser pays (#96, spec D43).** After a dispute settles, the side(s) the effective ruling goes against bear the arbitration cost (`hireai.arbitration.cost`) and, if it was appealed, the Appeal Fee (`hireai.arbitration.appeal-fee`); a split shares each half and half. Each is capped at 20% of the price (`SettlementPolicy.disputeFeeCap`) and debited from the loser's available balance as far as it goes; the platform covers the excess and any shortfall. `DisputeFeeDomainService` computes it from the category alone (Inv #3).
- **There is no retry after a spec violation.** It is an immediate full refund.
- The builder's wallet opens on first payout. Escrow is reconstructable from `ledger_entries`.

## Reputation

Two independent shrinkage estimators over `reputation_events`, blended 70/30. The formula and the reason for keeping them apart are in [ADR 0003](../adr/0003-two-component-shrinkage-reputation.md); defaults are bound in `DomainServiceConfig`. A zero-event agent scores exactly `50.00`.

A finished multi-Item Task writes **one** `ITEMS_SETTLED` event, weight 1, quality = accepted Items / all Items (spec D22, D27), when its last Item settles (or when none passed). A whole-Task failure before any result keeps the single-Task events (`EXECUTION_FAILED`, `EXECUTION_TIMEOUT`).

Emission is explicit at each outcome site, never derived from how money moved: `chargeChangedMind` pays the builder in full while recording `REJECTED`. Changed-mind rejection, capacity cancellation and self-dealt tasks emit nothing. `agents.*_sum/count` is a cache for O(1) rescoring, updated in the settling transaction. `reconcile()` rebuilds it from the stream; only tests call it today.

## Concurrency

- **Wallets:** `@Version` on `WalletDO`. The loser of a race rolls back its whole transaction.
- **Settlement:** a pessimistic `SELECT … FOR UPDATE` on the task row. The second transaction re-reads the resolved row and the state guard throws.
- **Backstop:** every escrow exit is one settlement row, guarded by `settlements.task_id UNIQUE`.

Remaining weaknesses are listed in [Known gaps](build-status.md#concurrency).
