# The programmatic (API-key) channel

How a **machine client** (a client agent or third-party system, no human in the loop) submits tasks and consumes results. Read before touching API keys, programmatic submit, auto-settlement, webhooks, or the MCP facade.

It widens HireAI's entry point from "a human submits a task" to "any authenticated client does", the first step toward agents hiring agents. A key is a **credential of a client account**, not a new identity: it shares that account's wallet and ownership. The channel is edge adapters (key auth, idempotency, spend caps, deterministic settlement, push webhooks) over the unchanged submit, escrow, routing and validation core. It touches no money table.

Auth internals and the allow-list: [`identity-and-authz.md`](identity-and-authz.md). Schema: [`data-model.md`](data-model.md). Design history: `docs/superpowers/specs/2026-07-14-programmatic-spine-design.md`, `2026-07-19-push-webhooks-design.md`, `2026-07-22-mcp-server-design.md`.

## End-to-end flow

```
API key ──(Authorization: ApiKey <raw>)──► submit (open | direct)
  → idempotency + spend-cap checks       [409 IDEMPOTENCY_CONFLICT / SPEND_CAP_EXCEEDED]
  → atomic escrow freeze                 (Inv #1)
  → routing → RabbitMQ dispatch → signed HTTPS webhook to the Agent
  → Agent callback (dispatch-token auth)
  → validation gate on the frozen output_spec
        PASS → settleAccepted (85/15) → RESOLVED
        FAIL → refund → SPEC_VIOLATION
  → outbox row written in the settling transaction
  → WebhookDeliverySweeper signs and POSTs to the client's callback
  → client verifies the HMAC, then GETs the real result
```

The webhook is a **doorbell**, not the payload: the client fetches the result with `GET /api/tasks/{id}/result`. **Polling is the always-available path**; webhooks are an optimization, and some outcomes produce none (§6).

## 1. Authentication

- Header `Authorization: ApiKey <rawKey>` or `X-API-Key: <rawKey>`.
- Stored as a hex SHA-256 hash plus a display prefix (`hk_live_…`). The raw key is shown once, at creation.
- **Key management is JWT-only** (`/client/keys` dashboard): a leaked key can submit and spend, but never mint or revoke keys.

## 2. Idempotency

Optional `Idempotency-Key` header. `idempotency_keys` is `UNIQUE (owner_id, idempotency_key)`, so a racing duplicate hits the constraint **in the same transaction as the escrow freeze**: the whole submit rolls back, with no double-freeze, and the loser re-reads the winning task. The same key with a different request fingerprint is `409 IDEMPOTENCY_CONFLICT`.

## 3. Spend caps

Two independent optional caps per key; exceeding either is `409 SPEND_CAP_EXCEEDED`. Null means uncapped.
- `spend_cap`: the sum of currently frozen escrow across the key's in-flight tasks.
- `daily_spend_cap`: budget committed in the rolling last 24h.

Both are an authorization **read** over `api_key_task`, never a second ledger (Inv #2).

## 4. Submit modes

Both go through `SubmitOrchestrationAppService` (idempotency, caps, attribution) and accept a `CLIENT` JWT or an `API_CLIENT` key.

| Mode | Endpoint | Body | Agent | Spec |
|---|---|---|---|---|
| **Open** | `POST /api/tasks` | `{title, description, category, budget, outputSpec}` | Matcher picks | The client's submitted spec |
| **Direct** | `POST /api/tasks/direct` | `{title, description, budget, agentId}` | Pinned | Inherits the agent's spec |

- **Open mode is API and MCP only.** The web UI always books direct, after a shortlist from `GET /api/tasks/match-preview`.
- **Open mode routes unlisted agents too:** candidates need only be `ACTIVE`, in category and within budget.
- **Direct mode needs `ACTIVE` and listed.** Anything else is `404 NOT_FOUND`, deliberately, to avoid leaking existence.
- **Direct budget:** must be at least the agent's price, or `400 VALIDATION_ERROR`. **The submitted budget is what freezes and settles**, so a caller who over-budgets pays that amount. Only the web UI sends `budget = price`. See [Known gaps](build-status.md#known-gaps).

## 5. Deterministic auto-settlement

A machine client must not be asked for a human "review, then accept or reject". So on this channel **validation is the settlement trigger** (`ValidationAppServiceImpl`, branching on `apiKeyTaskRepository.findApiKeyIdByTask`):

- **PASS:** `settleAccepted`, `RESOLVED`, and reputation `TASK_ACCEPTED`.
- **FAIL:** refund, `SPEC_VIOLATION`, and reputation `SPEC_VIOLATION`.

This skips `PENDING_REVIEW`, accept/reject and all disputes. So accept/reject are denied to API keys (401), API tasks have no dispute path, and they can't be reviewed, so they never produce a Satisfaction event. Human-submitted tasks are unchanged. Settlement is fully deterministic with no LLM (Inv #3).

**What the gate actually checks** (`ValidationDomainServiceImpl`) is format and JSON Schema only:
- `TEXT` passes on any non-blank payload.
- `JSON` must parse, and a blank or non-JSON-Schema `schema` is skipped.
- `FILE` needs a valid HTTPS URL.
- `acceptanceCriteria` is **never evaluated** here. It is only arbitration evidence, and API tasks don't reach arbitration.

**Accepted limitation:** a fully autonomous client has no recourse for output that is spec-valid but poor; it pays. Its levers are a real JSON Schema (or a stricter format) and agent choice. A programmatic dispute API is out of scope.

## 6. Push webhooks

**Subscription (per API key, JWT-only):**
- `POST /api/webhooks/subscription` with `{apiKeyId, callbackUrl}`. The signing secret is server-generated. Re-registering replaces the active subscription, and there is at most one per key.
- `GET` with `?apiKeyId=`, and `POST …/rotate-secret`, `POST …/deactivate`, likewise `?apiKeyId=`.

**SSRF guard** (`WebhookUrlValidator`, Inv #6 outbound), checked **at registration and before every delivery attempt**: HTTPS only; the host is DNS-resolved; loopback, link-local (incl. cloud metadata), RFC1918, CGNAT and IPv6 ULA are blocked. `WEBHOOKS_ALLOW_INSECURE_LOCALHOST` exempts `http://localhost` for dev. Agent webhook URLs go through the same guard ([`matching.md`](matching.md#the-workorder)). DNS rebinding between the check and the connection is a known gap.

**Events:** `task.completed` (doorbell) and `task.failed` (doorbell plus `reason`, `failure_reason` and `refunded`). `reason` is the terminal status: `SPEC_VIOLATION`, `FAILED`, `TIMED_OUT` or `CANCELLED`. `failure_reason` is the task's [FailureReason](data-model.md#failure-reason) (why it ended), the same value `GET /api/tasks/{id}` returns as `failureReason`; it is `null` only for tasks that ended before reasons were recorded.

**Outbox and sweeper (at-least-once):**
- The terminal transition writes a `webhook_deliveries` row in the settling transaction. Its `id` doubles as the client-facing event id.
- An API task whose key has no active subscription gets no row, and registering later does not replay it.
- A dispatch whose every send attempt failed ends `FAILED` with a refund and a `task.failed` event (`reason` `FAILED`, `failure_reason` `AGENT_UNREACHABLE`). Retry schedule: [`matching.md`](matching.md#the-workorder).
- An Agent that declines the WorkOrder as busy (429/503) is not retried: the task ends `FAILED` with a refund and a `task.failed` event (`failure_reason` `AGENT_BUSY`), for direct (`agent_id`) and, for now, routed tasks alike.
- `WebhookDeliverySweeper` claims due rows with `FOR UPDATE SKIP LOCKED`, which is safe across instances, then signs and POSTs over a redirect-disabled `RestClient`. Failures back off exponentially with a cap until `DEAD`. Values: `hireai.webhooks.*` in `application.yml`.

**Signature:** `X-HireAI-Signature: t=<unix-ts>,v1=<hex hmac-sha256(secret,"{ts}.{body}")>`, with `X-HireAI-Event-Id` and `X-HireAI-Event-Type`. The client recomputes over `"{t}.{rawBody}"` and constant-time-compares `v1`.

**Reconciliation:** `GET /api/webhooks/deliveries` (filters `since`, `status`, `taskId`; defaults to the last 7 days, so the dashboard's `DEAD` banner has the same window) and `POST /api/webhooks/deliveries/{id}/redeliver`.

## 6a. MCP server facade

`mcp/` (official MCP SDK, stdio) exposes this channel as four tools with no business logic, authenticating with an API key from env. `get_task_status` passes the task's `failureReason` through as `failure_reason`. Tools, arguments and caveats: [`mcp/README.md`](../../mcp/README.md). springdoc publishes the REST surface as the `programmatic` group at `/v3/api-docs/programmatic`, with Swagger UI at `/swagger-ui/index.html`.

## 7. Endpoint catalog

| Method · Path | Auth | Purpose |
|---|---|---|
| `POST /api/keys`, `GET /api/keys`, `POST /api/keys/{id}/revoke` | JWT `CLIENT` | Key management |
| `POST /api/tasks` | `CLIENT`, `API_CLIENT` | Open submit |
| `POST /api/tasks/direct` | `CLIENT`, `API_CLIENT` | Direct submit |
| `GET /api/tasks`, `/api/tasks/{id}`, `/result`, `/validation`, `/attempts` | `CLIENT`, `API_CLIENT` | Track (incl. `failureReason` once a task ends without a result), result, failing check, dispatch history ([`dispatch_attempts`](data-model.md#dispatch-attempts)) |
| `GET /api/tasks/match-preview?category=&budget=` | `CLIENT`, `API_CLIENT` | Ranked shortlist; creates no task |
| `GET /api/catalogue/**` | Any human role, `API_CLIENT` | Browse agents |
| `/api/webhooks/subscription/**` | JWT `CLIENT` | Subscription |
| `GET /api/webhooks/deliveries`, `POST …/{id}/redeliver` | `CLIENT`, `API_CLIENT` | Delivery log, resend |

## 8. Code map

- **Auth:** `ApiKeyAuthenticationFilter`, `HttpCurrentApiKeyProvider` (`hireai-controller`); `ApiKeyAuthService` (`hireai-application`); `ApiKeyModel`, `SpendCaps` (`hireai-domain`).
- **Orchestration:** `SubmitOrchestrationAppService`, `SubmitFingerprint`.
- **Settlement gate:** `ValidationAppServiceImpl`, `AgentCallbackAppServiceImpl`.
- **Webhooks:** `WebhookOutboxAppService`, `WebhookDeliveryAppService`, `WebhookDeliverySweeper`, `WebhookSubscriptionAppService`; domain `WebhookSignature`, `WebhookPayloads`, `WebhookBackoffPolicy`, `IpClassifier`; infra `WebhookSender`, `WebhookUrlValidator`, `WebhookRestClientConfig`.

## 9. Not built, and open questions

**Not built:** key rotation with a grace period and per-key scopes (a key has a name and two caps; create, list, revoke only); a per-submission `callbackUrl`; a callback ownership or reachability check beyond the SSRF guard.

**Out of scope:** agent-to-agent chaining by an executing agent, executor-side MCP/A2A, remote OAuth-secured MCP, official SDKs, real-money settlement.

**Open questions:** whether caps should also exist per account or per target agent; when to graduate from API keys to OAuth2 client credentials; whether callbacks should support per-event-type subscriptions.
