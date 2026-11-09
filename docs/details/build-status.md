# Build status

What exists and what doesn't, per area. **Known gaps below is the single home for known defects**: add new ones there, not in other docs. How things work lives in the linked docs and the code. This file holds no test counts, migration numbers or PR status: use the test commands in `CLAUDE.md`, `backend/hireai-main/src/main/resources/db/migration/`, and `gh`.

## Modules

| Module | Built | Not built |
|---|---|---|
| **1 Task Submission** | Submit with atomic escrow freeze and a frozen `output_spec`; idempotent retries; web flow: category picker → read-only match preview → pick → direct booking at the agent's price | Client input documents (`SourceSnapshot`, see [ADR 0004](../adr/0004-launch-vertical-document-grounded-extraction.md)) |
| **2 Agent Registration** | Register, activate, suspend, reactivate, deactivate, publish a version; HTTPS-only webhook; builder-declared concurrency; storefront and media; categories picked from a platform-owned list, with an optional suggested category stored for admins; an AgentSigningSecret shown once at registration and rotatable by the owner; each Agent's name, tagline and description [embedded](data-model.md#agent-embeddings) (pgvector) on registration and profile edits, with a re-embed sweeper for missing or stale vectors | Admin verification (builders activate their own agents); editing a webhook without publishing a version; renaming an Agent; matching on the embeddings (the vectors are stored but no matcher reads them yet, #59) |
| **3 Routing & Execution** | Scored ε-greedy matcher, RabbitMQ dispatch, WorkOrders signed with the agent's secret, a callback that needs the dispatch token plus the agent's signature, WorkOrders carrying the task's title, description and deadline ([WorkOrder](matching.md#the-workorder)); sweepers for SUBMITTED re-routing, capacity re-match and execution timeout; delayed send retries, then `EXECUTION_FAILED`; a documented [acknowledgement contract](matching.md#the-workorder) under which a 429/503 busy decline is never retried or penalised; [routed failover](matching.md#routed-failover): a routed task whose Agent is busy or unreachable stays QUEUED and moves to the next untried eligible Agent (at most 3), else FAILED with `NO_ALTERNATIVE_AGENT` and a refund, while a direct booking ends FAILED (`AGENT_BUSY` / `AGENT_UNREACHABLE`) with a refund; a direct booking whose agent is no longer ACTIVE or whose version was superseded fails fast to CANCELLED with `AGENT_UNAVAILABLE` and a refund; every terminal failure records a [FailureReason](data-model.md#failure-reason), shown in the task API, the `task.failed` webhook, MCP and the web failure panel; an append-only [dispatch history](data-model.md#dispatch-attempts) per task, readable by its owner | The rest of the hardening is tracked in #53 |
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

### Reputation

- **Lost score update, unreproduced.** `AgentDO.reputationScore` is updatable and `AgentRepositoryImpl` writes it on full-row saves, so a concurrent suspend or publish can revert the score while the sums stay advanced.
- **Clients can rate disputes they lost, unreproduced.** A FULFILLED ruling resolves `ACCEPTED`, which passes `requireAccepted`. ADR 0003 forbids this.
- **`reputationScore` is still in API payloads, confirmed.** It is no longer rendered, but `AgentCardDTO` and `AgentOptionDTO` carry it.

### Security and API behaviour

- **#23, confirmed.** A role denial returns 401, not 403, and the frontend sends a 401 to `/login`. Details are in the [post-mortem](../post-mortem/2026-07-17-api-key-lockout-401-vs-403.md).
- **Wrong error status, confirmed.** An unknown path, a wrong method and an unsupported media type all return 500, because `GlobalExceptionConfiguration`'s catch-all handles them.
- **No role check on builder actions, confirmed.** `anyRequest()` admits any human role, so a CLIENT reaches `/api/builder/earnings` and `POST /api/agents`. Ownership checks still apply.
- **Unlisted agents can win routing, confirmed (#59).** `findActiveCandidates` ignores `is_listed`; the shortlist and direct booking require it.
- **DNS rebinding, accepted (#57).** The SSRF guard resolves the host, then the HTTP client resolves it again to connect, so a name that switches to an internal address between the two is not caught. This applies to agent and client webhooks alike; closing it means pinning the checked IP.
- **API keys outlive their user, confirmed.** `findActiveByHash` checks key status only.

### Concurrency

- **`tasks` has no `@Version`.** Writes are last-writer-wins outside the locked settlement path.
- **Single-instance sweepers.** `AwaitingAgentRematchSweeper` and `SubmittedTaskSweeper` run through unlocked reads. Before scaling out, `assignAndQueue` and `cancelAwaitingAgentWithRefund` must become status-conditional or row-locked. Money stays safe through `settlements.task_id UNIQUE`.
- **`adminRule` does not lock then re-read.** It is safe today through the same constraint.
- **Spend caps have a TOCTOU window.** Concurrent submits under one key can slightly overshoot (accepted).
- **Wallet version conflicts return 500**, not a retryable 409. Money is safe; the loser's transaction rolls back.

### Small

- `TaskFailurePanel` links to `/marketplace`, which doesn't exist.
- `app/client/agents/[id]/page.tsx` still shows "Demo ratings" copy.
- `app/client/page.tsx` says "verified" agents, but nothing verifies them.
- `arbitration/tests/test_health.py` reads the real `.env`, so with keys set locally it starts a live consumer and hangs. CI has no keys.
