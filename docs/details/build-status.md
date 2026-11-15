# Build status

What exists and what doesn't, per area. **Known gaps below is the single home for known defects**: add new ones there, not in other docs. How things work lives in the linked docs and the code. This file holds no test counts, migration numbers or PR status: use the test commands in `CLAUDE.md`, `backend/hireai-main/src/main/resources/db/migration/`, and `gh`.

## Modules

| Module | Built | Not built |
|---|---|---|
| **1 Task Submission** | Submit with atomic escrow freeze and a frozen `output_spec`, beside which every new Task freezes the equivalent [Contract](data-model.md#acceptance) (shown on the task API and the web task page; read by validation, not yet by matching or arbitration); a non-blank schema that is not a valid JSON Schema is a 400 at submit, registration and booking; idempotent retries; web flow: category picker → read-only match preview → pick → direct booking at the agent's price | Client input documents (`SourceSnapshot`, see [ADR 0004](../adr/0004-launch-vertical-document-grounded-extraction.md)) |
| **2 Agent Registration** | Register, activate, suspend, reactivate, deactivate, publish a version; HTTPS-only webhook; builder-declared concurrency; storefront and media; categories picked from a platform-owned list, with an optional suggested category stored for admins; an AgentSigningSecret shown once at registration and rotatable by the owner; every version carries an [Offer](data-model.md#acceptance), derived from its output spec at registration (backfilled for older versions) and editable when publishing a version (`offer` on `POST /api/agents/{id}/versions`, checked against the Templates' bounds and required to keep accepting the Agent's own output contract; the builder's Pricing tab has the editor). The Offer is shown on the agent API, the public agent page and the Pricing tab, and the platform's versioned Templates are readable at `GET /api/catalogue/templates`; each Agent's name, tagline and description [embedded](data-model.md#agent-embeddings) (pgvector) on registration and profile edits, with a re-embed sweeper for missing or stale vectors | Admin verification (builders activate their own agents); editing a webhook without publishing a version; renaming an Agent |
| **3 Routing & Execution** | Scored ε-greedy matcher sharing [one eligibility and score](matching.md#one-ranking-engine-three-uses) with the web shortlist (only listed Agents are ever matched; the Builder dashboard warns about unlisted ones), RabbitMQ dispatch, WorkOrders signed with the agent's secret, a callback that needs the dispatch token plus the agent's signature, WorkOrders carrying the task's title, description and deadline ([WorkOrder](matching.md#the-workorder)); sweepers for SUBMITTED re-routing, awaiting-agent re-match and execution timeout; delayed send retries, then `EXECUTION_FAILED`; a documented [acknowledgement contract](matching.md#the-workorder) under which a 429/503 busy decline and a 422 ContractDecline (`CONTRACT_DECLINED`, with the Agent's reason) are never retried or penalised, the latter feeding a public contract-decline rate shown on the shortlist and the agent page; [routed failover](matching.md#routed-failover): a routed task whose Agent is busy, unreachable or no longer available at a send stays QUEUED and moves to the next untried eligible Agent (up to the failover limit), else FAILED with `NO_ALTERNATIVE_AGENT` and a refund, while a direct booking ends FAILED (`AGENT_BUSY` / `AGENT_UNREACHABLE`) with a refund; a direct booking whose agent is no longer ACTIVE or whose version was superseded fails fast to CANCELLED with `AGENT_UNAVAILABLE` and a refund; every terminal failure records a [FailureReason](data-model.md#failure-reason), shown in the task API, the `task.failed` webhook, MCP and the web failure panel; an append-only [dispatch history](data-model.md#dispatch-attempts) per task, readable by its owner; an optional [client deadline](matching.md#client-deadline) on submit, booking and match-preview that every selection (shortlist, routing, failover hop) respects; [relevance](matching.md#relevance) between the task text and each Agent's profile: a raw-cosine floor τ plus a weighted, normalised term, falling back to the other factors when the embedding provider is down, with a "no close match" shortlist state | See Known gaps |
| **4 Validation & Disputes** | Format and JSON-Schema gate before any client sees a result; a FILE result is fetched once (pinned-DNS SSRF guard, no redirects, size-capped) and frozen as a [DeliverySnapshot](data-model.md#delivery-snapshots) that the `file_basic` checks, the client's preview and download, the admin and the arbitrator all read instead of the agent's link; an [acceptance engine](data-model.md#acceptance-engine) of code-defined Check Library entries that pass, reject or send to review, with Findings (facts about the brief, never moving money) and Agent-reported anomalies shown on the inspection report; a check rejection goes back to the same Agent with the failing checks up to `hireai.acceptance.max-reworks` (2) times, then ends `SPEC_VIOLATION` with a refund, and is not appealable; the first vertical Template, `image_generation@1` (format, exact size and background colour judged on the snapshot's pixels, a duplicate-brief Finding); a review window (`hireai.review.window`, 24h) after which `ReviewWindowSweeper` accepts and settles an unreviewed result, with a countdown on the task page; reason-gated reject (D charges in full; A/B/C takes the rejection response path: the Agent gets a signed notice and within `hireai.rejection.agent-response-window` (2h) revises once, concedes with a refund, or stands by with a reason, silence counting as standing by; after a stand-by the Client has `hireai.rejection.client-decision-window` (24h) to accept or request arbitration, silence counting as acceptance; `RejectionWindowSweeper` applies both silences and retries undelivered notices); LangGraph arbitrator; ruling is a proposal, then client accept or appeal, or auto-accept; admin tier-2 override; arbitration DLQ and timeout escalate to admin | Validation of `acceptanceCriteria` (only the arbitrator reads them); SSE (the UI polls); dispute discovery for builders on full refunds (no payout row, so nothing on earnings) |
| **5 Reputation & Settlement** | Append-only reputation stream, two-component shrinkage score, earned reviews, builder Reputation and Reviews tabs; 85/15 settlement, deterministic split and refund; builder earnings read | Multi-account Sybil defence (accepted in [ADR 0003](../adr/0003-two-component-shrinkage-reputation.md)); decay and auto-suspend (rejected there) |
| **6 Discovery & Dashboard** | Authenticated catalogue, agent profiles, direct booking, builder console and earnings | Anonymous public catalogue |

## Other surfaces

| Area | Built | Not built | Detail |
|---|---|---|---|
| Identity | JWT, register, Google OAuth (`oauth` profile), dual-capability RBAC, become-builder | Password-confirmed OAuth linking | [`identity-and-authz.md`](identity-and-authz.md) |
| Programmatic channel | API keys, spend caps, auto-settle, signed push webhooks | Key rotation, per-key scopes | [`programmatic-channel.md`](programmatic-channel.md) |
| MCP (`mcp/`) | Five stdio tools over the REST channel, scoped OpenAPI | Remote or OAuth transport, push into MCP | [`mcp/README.md`](../../mcp/README.md) |
| Arbitration (`arbitration/`) | FastAPI + LangGraph worker, 4 nodes: `gather_evidence → deliberate → classify → critique` | | [`arbitration/README.md`](../../arbitration/README.md) |
| Frontend (`frontend/`) | Client, Builder and Admin surfaces plus a static landing page | | [`frontend.md`](frontend.md) |
| Admin | Overview, read-only browsers, dispute queue, tier-2 ruling | | [`frontend.md`](frontend.md) |
| Delivery | CI on four workflows (lint, test, build) | Deployment config, `docker compose` (only `arbitration/` has a Dockerfile) | [`architecture.md`](architecture.md) |

## Known gaps

"Confirmed" means seen live or directly in the code path. "Unreproduced" means found by reading the code, not triggered. Remove an entry when it is fixed.

### Money and contract

- **#46, confirmed.** On routed submit the client authors the spec, the matcher ignores specs (and, since #88, Offers: coverage is not yet a filter), and the builder absorbs a failure. The agent does see the spec at dispatch and can refuse it at no reputation cost with a ContractDecline (#93), which counts in its public decline rate. #46 is now the whole binding-contract redesign; #50 is the stopgap.
- **Paid the budget, not the price, confirmed.** The backend only asserts `budget >= price`, then freezes and settles on the submitted budget (`SettlementDomainService` takes `task.budget()`). On a direct booking only the web UI sends the price, so API and MCP callers can overpay; on a routed submit the chosen Agent is always paid the whole budget, even when its price is lower.
- **The arbitrator doesn't see the rejection exchange, confirmed (#95).** A dispute opened after a stand-by is judged on the task, its output spec and the (possibly revised) result; the Agent's stand-by reason and any replaced result (kept on `task_rejections`) are not in the arbitration request.
- **Rejection notices are at-least-once, unreproduced.** The after-commit send and a `RejectionWindowSweeper` retry can overlap, so an Agent may get the same notice twice; a second response gets 409.
- **Weak validation on machine tasks, confirmed (#46).** TEXT passes on any non-blank payload (length bounds exist on `text_basic` but no submit path sets them yet), so `acceptanceCriteria` has no effect on auto-settle. FILE's `media_types` / `max_bytes` checks exist but no submit path sets them yet (#91/#100).
- **Agents registered before #90 may hold a schema that is not a valid JSON Schema.** Registration now refuses one, but an older version keeps it, and booking that Agent is refused with a 400 naming the Agent's schema until its Builder registers a fixed one (a new version carries the output spec over).
- **Zero-commission band, confirmed.** At prices up to `0.03` the 15% commission rounds to `0.00`, so the platform does the work for free and a Sybil round trip costs nothing.
- **MCP double-freeze, unreproduced.** `submit_task` generates a new `Idempotency-Key` per call, so re-invoking it freezes escrow again.

### Reputation

- **Lost score update, unreproduced.** `AgentDO.reputationScore` is updatable and `AgentRepositoryImpl` writes it on full-row saves, so a concurrent suspend or publish can revert the score while the sums stay advanced.
- **Clients can rate disputes they lost, unreproduced.** A FULFILLED ruling resolves `ACCEPTED`, which passes `requireAccepted`. ADR 0003 forbids this.
- **`reputationScore` is still in API payloads, confirmed.** It is no longer rendered, but `AgentCardDTO` and `AgentOptionDTO` carry it.

### Security and API behaviour

- **#23, confirmed.** A role denial returns 401, not 403, and the frontend sends a 401 to `/login`. Details are in the [post-mortem](../post-mortem/2026-07-17-api-key-lockout-401-vs-403.md).
- **Wrong error status, confirmed.** An unknown path, a wrong method and an unsupported media type all return 500, because `GlobalExceptionConfiguration`'s catch-all handles them.
- **No role check on builder actions, confirmed.** `anyRequest()` admits any human role, so a CLIENT reaches `/api/builder/earnings` and `POST /api/agents`. Ownership checks still apply.
- **DNS rebinding, accepted (#57).** The SSRF guard resolves the host, then the HTTP client resolves it again to connect, so a name that switches to an internal address between the two is not caught. This applies to agent and client webhooks alike; closing it means pinning the checked IP, as the DeliverySnapshot fetch already does (`DeliverableFetchClient`).
- **DeliverySnapshot fetch runs under the task row lock, confirmed.** The callback's validation gate fetches the file inside its transaction, so a slow file host holds the lock and a DB connection for up to `hireai.delivery.timeout`. One attempt only: a transient failure on the agent's host is a `SPEC_VIOLATION`.
- **An anomaly-forced review still auto-accepts on silence, confirmed (#98, owner decision pending).** A result sent to review by a self-reported anomaly (`tasks.review_reason = ANOMALY`) or an undecidable check (`UNDECIDED`) waits in `PENDING_REVIEW` on both channels, but the review-window sweeper accepts it on silence like any other, so an anomaly the Client never looked at is paid. The marker is recorded so the sweeper can treat it differently once decided.
- **A late duplicate first callback can count as the rework, accepted (#98).** A rework re-queues the task for the same Agent, whose first dispatch token is still valid; a retried first callback arriving after the rework WorkOrder is taken as the reworked result and judged (and may spend a rework).
- **No submit path names `image_generation@1` yet (#98).** Only an explicit Contract (#91's booking contract, #100's routed one) can freeze it; the WorkOrder still carries only `outputSpec`, so the Agent learns the image terms from the brief, not the Contract.
- **DeliverySnapshot bytes live in Postgres, confirmed.** `delivery_snapshot_contents` (BYTEA) caps a file at `hireai.delivery.max-bytes`, below `file_basic`'s own 100 MiB bound, and nothing deletes content yet (D32 retention).
- **API keys outlive their user, confirmed.** `findActiveByHash` checks key status only.

### Concurrency

- **`tasks` has no `@Version`.** Writes are last-writer-wins outside the locked settlement path.
- **Single-instance sweepers.** `AwaitingAgentRematchSweeper` and `SubmittedTaskSweeper` run through unlocked reads. Before scaling out, `assignAndQueue` and `cancelAwaitingAgentWithRefund` must become status-conditional or row-locked. Money stays safe through `settlements.task_id UNIQUE`.
- **`adminRule` does not lock then re-read.** It is safe today through the same constraint.
- **Spend caps have a TOCTOU window.** Concurrent submits under one key can slightly overshoot (accepted).
- **Wallet version conflicts return 500**, not a retryable 409. Money is safe; the loser's transaction rolls back.

### Matching

- **τ not tuned on live data, confirmed.** The relevance floor (`hireai.matching.relevance-floor`) is provisional. On the recorded test set (`TauTestSetTest`) it removes off-bucket Agents but cuts within a bucket on both sides: a general summariser misses a lease summary while a medical summariser passes.
- **`text_embeddings` grows without bound, confirmed.** One row per distinct task text and model; nothing evicts old rows.
- **A failover hop ranks without relevance when the first routing pass had no vector, confirmed.** The hop runs under the task's row lock, so it reads only the cache and never calls the provider.

### Small

- `TaskFailurePanel` links to `/marketplace`, which doesn't exist.
- `app/client/agents/[id]/page.tsx` still shows "Demo ratings" copy.
- `app/client/page.tsx` says "verified" agents, but nothing verifies them.
- `arbitration/tests/test_health.py` reads the real `.env`, so with keys set locally it starts a live consumer and hangs. CI has no keys.
