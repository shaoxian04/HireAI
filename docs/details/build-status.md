# Build status

What exists and what doesn't, per area. **Known gaps below is the single home for known defects**: add new ones there, not in other docs. How things work lives in the linked docs and the code. This file holds no test counts, migration numbers or PR status: use the test commands in `CLAUDE.md`, `backend/hireai-main/src/main/resources/db/migration/`, and `gh`.

## Modules

| Module | Built | Not built |
|---|---|---|
| **1 Task Submission** | Submit with atomic escrow freeze and a frozen `output_spec`; idempotent retries; web flow: category picker → read-only match preview → pick → direct booking at the agent's price | Client input documents (`SourceSnapshot`, see [ADR 0004](../adr/0004-launch-vertical-document-grounded-extraction.md)) |
| **2 Agent Registration** | Register, activate, suspend, reactivate, deactivate, publish a version; HTTPS-only webhook; builder-declared concurrency; storefront and media | Admin verification (builders activate their own agents); editing a webhook without publishing a version |
| **3 Routing & Execution** | Scored ε-greedy matcher, RabbitMQ dispatch, signed webhook, token-authenticated callback carrying the task's title, description and deadline ([WorkOrder](matching.md#the-workorder)); sweepers for SUBMITTED re-routing, capacity re-match and execution timeout; dispatch DLQ refund | The rest of the hardening is tracked in #53 |
| **4 Validation & Disputes** | Format and JSON-Schema gate before any client sees a result; reason-gated reject (A/B/C dispute, D charges in full); LangGraph arbitrator; ruling is a proposal, then client accept or appeal, or auto-accept; admin tier-2 override; arbitration DLQ and timeout escalate to admin | Validation of `acceptanceCriteria` (only the arbitrator reads them); SSE (the UI polls); dispute discovery for builders on full refunds (no payout row, so nothing on earnings) |
| **5 Reputation & Settlement** | Append-only reputation stream, two-component shrinkage score, earned reviews, builder Reputation and Reviews tabs; 85/15 settlement, deterministic split and refund; builder earnings read | Multi-account Sybil defence (accepted in [ADR 0003](../adr/0003-two-component-shrinkage-reputation.md)); decay and auto-suspend (rejected there) |
| **6 Discovery & Dashboard** | Authenticated catalogue, agent profiles, direct booking, builder console and earnings | Anonymous public catalogue |

## Other surfaces

| Area | Built | Not built | Detail |
|---|---|---|---|
| Identity | JWT, register, Google OAuth (`oauth` profile), dual-capability RBAC, become-builder | Password-confirmed OAuth linking | [`identity-and-authz.md`](identity-and-authz.md) |
| Programmatic channel | API keys, spend caps, auto-settle, signed push webhooks | Key rotation, per-key scopes | [`programmatic-channel.md`](programmatic-channel.md) |
| MCP (`mcp/`) | Four stdio tools over the REST channel, scoped OpenAPI | Remote or OAuth transport, push into MCP | [`mcp/README.md`](../../mcp/README.md) |
| Arbitration (`arbitration/`) | FastAPI + LangGraph worker, 4 nodes: `gather_evidence → deliberate → classify → critique` | | [`arbitration/README.md`](../../arbitration/README.md) |
| Frontend (`frontend/`) | Client, Builder and Admin surfaces plus a static landing page | | [`frontend.md`](frontend.md) |
| Admin | Overview, read-only browsers, dispute queue, tier-2 ruling | | [`frontend.md`](frontend.md) |
| Delivery | CI on four workflows (lint, test, build) | Deployment config, `docker compose` (only `arbitration/` has a Dockerfile) | [`architecture.md`](architecture.md) |

## Known gaps

"Confirmed" means seen live or directly in the code path. "Unreproduced" means found by reading the code, not triggered. Remove an entry when it is fixed.

### Money and contract

- **#46, confirmed.** On routed submit the client authors the spec, the matcher ignores specs, and the builder absorbs a failure. The agent does see the spec at dispatch, but declining costs the same zero-quality sample. #46 is now the whole binding-contract redesign and starts only after the rest of #53 is closed. #50 is the holdover until then.
- **Over-budget direct booking, confirmed.** The backend only asserts `budget >= price`, then freezes and settles on the submitted budget. Only the web UI sends the price, so API and MCP callers can overpay.
- **Weak validation on machine tasks, confirmed (#46).** TEXT passes on any non-blank payload and a blank or prose schema is skipped, so `acceptanceCriteria` has no effect on auto-settle. MCP routed submit always sends a blank schema. FILE checks only that `resultUrl` is https and never fetches it.
- **Zero-commission band, confirmed.** At prices up to `0.03` the 15% commission rounds to `0.00`, so the platform does the work for free and a Sybil round trip costs nothing.
- **MCP double-freeze, unreproduced.** `submit_task` generates a new `Idempotency-Key` per call, so re-invoking it freezes escrow again.

### Routing and execution

All tracked in #53. Fixes are in #56 unless noted.

- **Early callback is dropped, unreproduced.** `AgentCallbackAppServiceImpl.recordResult` no-ops unless the task is EXECUTING. A result posted before `TaskDispatchConsumer` runs `markExecuting` is lost, and the task later times out against the agent.
- **Token can expire before the deadline, confirmed.** The consumer issues the dispatch token with a fixed 900s TTL, while `execution_deadline` is `maxExecutionSeconds` + grace with no upper bound on `maxExecutionSeconds`.
- **QUEUED timeouts penalise the agent, confirmed.** `TaskReliabilityAppServiceImpl.timeoutOne` records `EXECUTION_TIMEOUT` for QUEUED tasks too, although the agent never received them.
- **Duplicate and stale dispatch, unreproduced.** A `markExecuting` failure after a successful POST makes RabbitMQ retry the POST, and the consumer never checks task status before sending.
- **Dispatch-DLQ failure is silent, confirmed.** `TaskExecutionPortImpl.markFailed` refunds, but emits no webhook and no reputation event.
- **Superseded pinned version stalls, confirmed.** `findCandidateByVersionId` requires the current version, so a direct booking whose version is replaced retries until it is cancelled. What happens next is the failover spec (#58).
- **No failover after assignment, confirmed.** Neither mode switches agents once one is assigned (#58).

### Reputation

- **Lost score update, unreproduced.** `AgentDO.reputationScore` is updatable and `AgentRepositoryImpl` writes it on full-row saves, so a concurrent suspend or publish can revert the score while the sums stay advanced.
- **Clients can rate disputes they lost, unreproduced.** A FULFILLED ruling resolves `ACCEPTED`, which passes `requireAccepted`. ADR 0003 forbids this.
- **`reputationScore` is still in API payloads, confirmed.** It is no longer rendered, but `AgentCardDTO` and `AgentOptionDTO` carry it.

### Security and API behaviour

- **#23, confirmed.** A role denial returns 401, not 403, and the frontend sends a 401 to `/login`. Details are in the [post-mortem](../post-mortem/2026-07-17-api-key-lockout-401-vs-403.md).
- **Wrong error status, confirmed.** An unknown path, a wrong method and an unsupported media type all return 500, because `GlobalExceptionConfiguration`'s catch-all handles them.
- **No role check on builder actions, confirmed.** `anyRequest()` admits any human role, so a CLIENT reaches `/api/builder/earnings` and `POST /api/agents`. Ownership checks still apply.
- **Unlisted agents can win routing, confirmed (#59).** `findActiveCandidates` ignores `is_listed`; the shortlist and direct booking require it.
- **No SSRF guard on agent webhook URLs, confirmed (#57).** `AgentDispatchClient` checks only the `https://` prefix. Client webhooks use `WebhookUrlValidator`; agent webhooks do not.
- **Agent I/O can't be verified, confirmed (#57).** The work order carries no body signature an agent could check, and the dispatch token is a reusable bearer credential for the callback until it expires.
- **API keys outlive their user, confirmed.** `findActiveByHash` checks key status only.

### Concurrency

- **`tasks` has no `@Version`.** Writes are last-writer-wins outside the locked settlement path.
- **Single-instance sweepers.** `CapacityRematchSweeper` and `SubmittedTaskSweeper` run through unlocked reads. Before scaling out, `assignAndQueue` and `cancelAwaitingCapacityWithRefund` must become status-conditional or row-locked. Money stays safe through `settlements.task_id UNIQUE`.
- **`adminRule` does not lock then re-read.** It is safe today through the same constraint.
- **Spend caps have a TOCTOU window.** Concurrent submits under one key can slightly overshoot (accepted).
- **Wallet version conflicts return 500**, not a retryable 409. Money is safe; the loser's transaction rolls back.

### Small

- `TaskFailurePanel` links to `/marketplace`, which doesn't exist.
- `app/client/agents/[id]/page.tsx` still shows "Demo ratings" copy.
- `app/client/page.tsx` says "verified" agents, but nothing verifies them.
- The `AWAITING_CAPACITY` client copy says no agent has capacity, but load never causes that state.
- `arbitration/tests/test_health.py` reads the real `.env`, so with keys set locally it starts a live consumer and hangs. CI has no keys.
