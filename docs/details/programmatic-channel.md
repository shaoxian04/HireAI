# The programmatic (API-key) channel

How a **machine client** — a client agent or third-party system, no human in the loop — submits tasks and consumes results. It is a distinct channel that runs over the **unchanged submit / escrow / routing / validation core**; it adds only edge adapters (API-key auth, idempotency, spend caps, deterministic settlement, push webhooks). No money-table changes.

Two build phases, both live: **Phase 3** — the submission spine (API keys, idempotency, spend caps, `V25`); **Phase 4** — deterministic auto-settlement + signed push webhooks (`V26`). Live full-stack E2E verified 2026-07-21 (both event types, HMAC, SSRF guard, open + direct booking).

**Design depth (point-in-time specs):** `docs/superpowers/specs/2026-07-14-programmatic-spine-design.md` (Phase 3), `docs/superpowers/specs/2026-07-19-push-webhooks-design.md` (Phase 4), and the product/requirements roadmap `docs/programmatic-task-submission.md`. **Slices:** auth in `identity-and-authz.md`; schema in `data-model.md` (`V25`/`V26`).

## End-to-end flow (happy path)

```
API key  ──(Authorization: ApiKey <raw>)──►  submit (open|direct)
  → idempotency check + spend-cap check      [reject: 409 IDEMPOTENCY_CONFLICT / SPEND_CAP_EXCEEDED]
  → atomic escrow freeze                      (Inv #1)
  → routing/matching → RabbitMQ dispatch → signed HTTPS webhook to the Agent
  → Agent callback (POST /api/agent-callbacks/{taskId}/result, dispatch-token auth)
  → validation gate against the frozen output_spec   (Inv #4)
        PASS → settleAccepted (85/15 payout) → task RESOLVED
        FAIL → refund → task SPEC_VIOLATION
  → webhook enqueued to the outbox *in the same transaction as settlement*   (Inv #2)
  → @Scheduled WebhookDeliverySweeper signs + POSTs the event to the client's callbackUrl
  → client verifies the HMAC, then GETs the real result
```

The webhook is a **doorbell**, not the payload: the client always fetches the authoritative result through the existing `GET /api/tasks/{id}/result`. **Polling is the always-available reconciliation path underneath** — webhooks are an optimization, never the only way to learn an outcome.

## 1. Authentication — API keys

- **`ApiKeyAuthenticationFilter`** runs alongside the JWT filter and sets the **same UUID principal** under a single **`ROLE_API_CLIENT`** authority. It **never overrides an existing JWT auth** (a human session wins).
- Header: **`Authorization: ApiKey <rawKey>`**.
- Keys are stored as a **hex SHA-256 hash** + a display prefix (e.g. `hk_live_a1b2c3`); the raw key is shown **once** at creation and never again. `last_used_at` is bumped throttled.
- **Key management is JWT-only** (`POST`/`GET /api/keys`, `POST /api/keys/{id}/revoke`, owner-scoped; dashboard `/client/keys`) — a leaked key can submit/spend but can never mint or revoke keys. Revoking a key you don't own is `NOT_FOUND` (no existence leak).

See `identity-and-authz.md` for the filter internals and the full allow-list.

## 2. Idempotency (retry-safe submit)

Optional **`Idempotency-Key`** header. `idempotency_keys` has **`UNIQUE (owner_id, idempotency_key)`**: a duplicate submit racing under the same key hits the constraint **inside the same transaction as the escrow freeze**, so the whole submit (freeze included) rolls back — **no double-freeze** — and the loser re-reads the winning task in a fresh transaction and returns it. A same-key submit with a *different* request fingerprint (SHA-256 of the normalized payload) is a `409 IDEMPOTENCY_CONFLICT`. This is what makes Invariant #1 safe under client retries.

## 3. Spend caps (per key, optional)

Two **independent** optional caps on each API key, either exceeded → **`409 SPEND_CAP_EXCEEDED`**:
- **Concurrent frozen escrow** (`spend_cap`) — cap on the sum of currently-frozen escrow across the key's in-flight tasks.
- **Rolling-24h daily spend** (`daily_spend_cap`) — cap on budget committed in the last 24h.

Both are computed as an **authorization *read*** over `api_key_task` — never a second ledger (Invariant #2). Null = uncapped.

## 4. Submit modes — open vs direct

Both submit endpoints are fronted by **`SubmitOrchestrationAppService`** (which owns the idempotency + spend-cap + attribution logic) and are reachable by a `CLIENT` JWT **or** an `API_CLIENT` key.

| Mode | Endpoint | Body | Routing | Spec source |
|---|---|---|---|---|
| **Open** | `POST /api/tasks` | `{title, description, category, budget, outputSpec}` | matcher picks the agent | the **task's** submitted `output_spec` |
| **Direct** | `POST /api/tasks/direct` | `{title, description, budget, agentId}` | pinned to `agentId` | **inherits the agent's** registered `output_spec` |

Direct booking is **pay-the-price** and requires the target agent to be **`ACTIVE` + listed** — an unlisted or missing agent surfaces as **`404 NOT_FOUND`** (deliberate anti-enumeration, the Inv #5 no-leak pattern), *not* 403.

## 5. Deterministic auto-settlement (the design pivot)

A machine client must **not** be asked to do a human-style "review → accept/reject". So for the API channel, **validation is the settlement trigger** (`ValidationAppServiceImpl` detects the channel via `apiKeyTaskRepository.findApiKeyIdByTask`):

- **PASS → `settleAccepted` (85/15 payout) → `RESOLVED`.**
- **FAIL → refund → `SPEC_VIOLATION`.**

This **skips `PENDING_REVIEW`, accept/reject, and the entire dispute/arbitration/appeal path**. Consequences:
- `POST /api/tasks/*/accept`/`/reject` are **dropped from the API allow-list** (an `API_CLIENT` key hitting them is 401 — there is nothing to accept).
- There is **no dispute path for API-submitted tasks** (immediate auto-settle makes them terminal; post-settlement clawback is out of scope).
- **Human-submitted tasks are unchanged** — they keep `PENDING_REVIEW → RESOLVED`, accept/reject, and the full dispute flow.

**Honest limitation (accepted):** a fully autonomous client has **no recourse** for output that is spec-valid but semantically poor — it pays. Its levers are (a) a **stricter `output_spec`** and (b) **agent selection / reputation** over time. Subjective recourse needs a subject; the machine channel has none. A programmatic dispute API is explicitly future/out-of-scope.

This **reinforces Invariant #3** (settlement is 100% deterministic, no LLM) and makes **Invariant #4** (the frozen `output_spec`) the *sole* acceptance test for the channel.

## 6. Push webhooks

**Subscription (per API key, JWT-only):** `POST`/`GET /api/webhooks/subscription`, `POST /api/webhooks/subscription/rotate-secret`, `POST /api/webhooks/subscription/deactivate`. Registers one HTTPS `callbackUrl` + a `signingSecret`; **≤1 ACTIVE subscription per key** (partial UNIQUE index).

**SSRF guard at registration** (`WebhookUrlValidator`) — **Invariant #6 extended to outbound client I/O**: HTTPS-only, the host is **DNS-resolved**, and loopback / link-local (incl. cloud-metadata `169.254.169.254`) / RFC1918 / **CGNAT 100.64/10** / **IPv6-ULA** are blocked.

**Two events, both thin:** `task.completed` (doorbell) and `task.failed` (doorbell + inline `reason` + `refunded`).

**Transactional outbox + sweeper (at-least-once):**
- On the terminal transition the platform writes a `webhook_deliveries` row **inside the settling transaction** (Inv #1/#2 safe — an outbox row, never a second ledger). Its `id` doubles as the client-facing `event_id`.
- **`@Scheduled WebhookDeliverySweeper`** (PT5S) claims due rows `FOR UPDATE SKIP LOCKED`, signs, POSTs over a **dedicated redirect-disabled `RestClient`** with connect/read timeouts, then applies **exponential-with-cap backoff** → `DELIVERED` / `PENDING` / `DEAD`. Single-instance today (like the other reliability sweepers).

**Signature (Stripe-style):** header `X-HireAI-Signature: t=<unix-ts>,v1=<hex hmac-sha256(secret,"{ts}.{body}")>` alongside `X-HireAI-Event-Id` and `X-HireAI-Event-Type`. The client verifies by recomputing the HMAC over `"{t}.{rawBody}"` and constant-time-comparing `v1`. The platform authenticates *itself to the client* with the HMAC — no JWT, no dispatch token on the outbound call.

**Reconciliation & failure surface:** `GET /api/webhooks/deliveries` (log) + `POST /api/webhooks/deliveries/{id}/redeliver` (manual resend), both reachable by `CLIENT` or `API_CLIENT`; the dashboard shows per-delivery `DELIVERED`/`PENDING`/`DEAD` badges and an account-scoped `DEAD`-failure banner. A row goes `DEAD` after the retry window is exhausted; the client can always fall back to polling.

## 7. Endpoint catalog

| Method · Path | Auth | Purpose |
|---|---|---|
| `POST /api/keys`, `GET /api/keys`, `POST /api/keys/{id}/revoke` | JWT (`CLIENT`) | key management |
| `POST /api/tasks` | `CLIENT` or `API_CLIENT` | open submit (category → matcher) |
| `POST /api/tasks/direct` | `CLIENT` or `API_CLIENT` | direct submit (pin `agentId`) |
| `GET /api/tasks`, `/api/tasks/{id}`, `/api/tasks/{id}/result`, `/api/tasks/{id}/validation` | `CLIENT` or `API_CLIENT` | track / result / failing-check |
| `POST /api/webhooks/subscription`, `GET …`, `POST …/rotate-secret`, `POST …/deactivate` | JWT (`CLIENT`) | webhook subscription |
| `GET /api/webhooks/deliveries`, `POST /api/webhooks/deliveries/{id}/redeliver` | `CLIENT` or `API_CLIENT` | delivery log / resend |

`POST /api/tasks/*/accept`/`/reject` are **human-only** (`CLIENT`/`BUILDER`/`ADMIN`) — see §5.

## 8. Schema

`V25` — `api_keys`, `idempotency_keys`, `api_key_task` (attribution + spend-cap read source). `V26` — `client_webhook_subscriptions`, `webhook_deliveries` (the outbox). Full column-level detail in `data-model.md`.

## 9. Code map

- Auth: `ApiKeyAuthenticationFilter`, `ApiKeyAuthService`, `HttpCurrentApiKeyProvider` (`hireai-controller` config); `ApiKeyModel`/`SpendCaps` (`hireai-domain`).
- Orchestration: `SubmitOrchestrationAppService` (idempotency + spend caps + attribution), `SubmitFingerprint`.
- Settlement gate: `ValidationAppServiceImpl` (channel branch), `AgentCallbackAppServiceImpl`.
- Webhooks: `WebhookOutboxAppService` (enqueue), `WebhookDeliveryAppService` + `WebhookDeliverySweeper` (deliver), `WebhookSubscriptionAppService`; domain `WebhookSignature`/`WebhookPayloads`/`WebhookBackoffPolicy`/`IpClassifier`; infra `WebhookSender`/`WebhookUrlValidator`/`WebhookRestClientConfig`.

## 10. Invariant mapping

| Invariant | How this channel honours it |
|---|---|
| #1 escrow before execution | atomic freeze on submit; idempotency prevents double-freeze under retries |
| #2 append-only money | spend caps are reads; a webhook is an outbox row, never a ledger entry |
| #3 deterministic money path | auto-settlement is 100% deterministic on the validation result (no LLM) |
| #4 output-spec is the contract | the frozen `output_spec` is the *sole* acceptance test for the channel |
| #5 identity + ownership | UUID principal from the key; unlisted/foreign agent → `NOT_FOUND` |
| #6 signed, HTTPS-only I/O | outbound callback is SSRF-guarded + HMAC-signed (the outbound half of #6) |
