# Push Webhooks + Deterministic Programmatic Settlement (Phase 4) — Design

> **Status:** Approved design, ready for an implementation plan. · **Date:** 2026-07-19 · **Owner:** Shaoxian
> **Roadmap:** Phase 4 of `docs/programmatic-task-submission.md` (§6.5–6.6, §8.7 "Push"). Phase 3 (the
> API-key submission spine — API keys, idempotency, spend caps) is **built and merged** (PR #22, `V25`).
> This spec supersedes the roadmap doc's Phase-4 sketch where they differ (notably: **RabbitMQ+DLQ → outbox+sweeper**,
> and the **deterministic-settlement** decision below, which was not in the original roadmap).

## 1. Summary

Phase 4 does two things that turned out to be inseparable:

1. **Deterministic programmatic settlement.** A task submitted through the API/MCP channel settles
   **automatically and immediately** on the objective output-spec validation result — no human review,
   no accept/reject, no dispute. Validation **pass → auto-settle (85/15 payout), task `RESOLVED`**;
   validation **fail → auto-refund**. The human (dashboard) channel is unchanged: it keeps review,
   accept/reject, and the full dispute/arbitration/appeal flow.

2. **Signed push webhooks.** A programmatic client registers an HTTPS `callbackUrl`; when its task
   reaches a terminal state the platform delivers a **thin, HMAC-signed, SSRF-guarded** event
   (`task.completed` / `task.failed`) with **at-least-once** semantics via a **transactional outbox +
   scheduled sweeper**. The webhook is a *doorbell* — the client fetches the actual result through the
   existing authenticated `GET /api/tasks/{id}/result`. Polling remains the always-available,
   authoritative reconciliation path underneath.

Delivered as **new edge adapters over the unchanged submit/escrow/routing/validation core**. No money
table changes. Invariant #6 (signed, HTTPS-only I/O) is **extended** to outbound client callbacks with a
**new SSRF guard**; Invariant #3 (deterministic money path) is **reinforced** (programmatic settlement is
100% deterministic, no LLM); Invariant #4 (output-spec is the binding contract) becomes the **sole
acceptance test** for the programmatic channel.

## 2. Why deterministic settlement (the brainstorming pivot)

The programmatic persona is a *client agent / third-party system* that submits a task and consumes a
result. Requiring it to perform a human-style "review → accept/reject" step is wrong on two counts:

- **It strands escrow.** If the client never calls accept/reject, credits freeze forever (violates the
  spirit of Invariant #1 — escrow must eventually settle).
- **Disputes don't fit a machine channel.** A dispute is a *human-judgment escalation* (LLM arbitrator +
  human admin backstop, async, multi-state `DISPUTED→RULED→ESCALATED→RESOLVED`, appealable). Forcing that
  lifecycle into a third-party integration would wreck its UX and predictability.

The platform already owns an **objective, machine-checkable acceptance test**: the frozen `output_spec`
validation gate (Invariant #4), which runs on every result before any client sees it. So for the
programmatic channel we make **validation the settlement trigger**: conformant → the builder is paid,
non-conformant → the client is refunded. Two deterministic outcomes, machine-speed, predictable — exactly
what an integration wants.

**Honest limitation (accepted):** a *fully autonomous* client (no human anywhere) has **no recourse** for
output that is spec-valid but semantically poor — it pays. Its levers are (a) writing a **stricter
`output_spec`** and (b) **agent selection/reputation** over time. Subjective recourse requires a subject;
there is none in the machine channel. A programmatic dispute API is explicitly **out of scope / future**.

**Disputes stay a human-channel capability.** Because immediate auto-settle makes an API task terminal
(`RESOLVED`, money released) the instant validation passes, there is **no dispute path for API-submitted
tasks** — neither programmatic nor human (a post-settlement clawback is out of scope). Human-*submitted*
tasks keep the full existing review + dispute flow, untouched.

## 3. Scope

**In scope**
- Deterministic auto-settlement for API/MCP-submitted tasks (immediate, on the validation result).
- Per-API-key **webhook subscription**: register/replace one HTTPS `callbackUrl` + a signing secret;
  rotate the secret; deactivate.
- Two terminal events — **`task.completed`** (thin ping) and **`task.failed`** (thin ping + inline
  reason/refund) — delivered via a **transactional outbox (`webhook_deliveries`) + `@Scheduled`
  `WebhookDeliverySweeper`**, HMAC-signed (Stripe-style), SSRF-guarded, at-least-once with `event_id`.
- **Reconciliation surface:** always-available poll (Phase-3, reused); a `GET /api/webhooks/deliveries`
  list endpoint; a `POST /api/webhooks/deliveries/{id}/redeliver` action; a long, config-driven retry
  window; dashboard visibility + a failure signal on sustained (`DEAD`) failures.
- Frontend `/client/webhooks` management page (register callback, reveal/rotate secret, delivery log,
  redeliver).
- **Allow-list tidy-up:** remove `accept`/`reject` from the API-key allow-list (programmatic channel is
  now submit + track + auto-settle only).

**Out of scope (unchanged or deferred)**
- MCP server facade + OpenAPI doc — **Phase 5**. (The deterministic model applies to MCP when built.)
- Programmatic dispute API / post-settlement clawback / subjective recourse for autonomous clients.
- Multiple webhook endpoints per key; per-event-type subscription filters (we always send both event
  types); dual-signing secret-rotation grace window.
- Any change to the human/dashboard review + dispute flow.
- Real-money settlement; hosted execution.

## 4. Key decisions (resolved in brainstorming)

| Decision | Choice | Why |
|---|---|---|
| Programmatic settlement | **Deterministic, automatic** on validation | Machine channel; output-spec is the contract (Inv #4) |
| Auto-settle timing | **Immediate** on validation pass (no review window) | User choice; simplest, most predictable machine contract |
| Disputes in API channel | **None** (human dashboard only, for human-submitted tasks) | Dispute = human judgment; doesn't fit a machine integration |
| Delivery reliability | **Transactional outbox + sweeper** (not RabbitMQ+DLQ) | Atomic with state change (no lost/phantom event); survives broker-down; matches the 4 existing sweepers; testable without Docker |
| Payload | **Thin ping**; client fetches result via `GET /result` | Result can be large; keeps sensitive data behind stronger auth; DEAD-safe; one client code path |
| Signing | **Stripe-style** HMAC-SHA256 over `"{ts}.{body}"`, `X-HireAI-Signature: t=,v1=` | Battle-tested; timestamp = replay guard; reuse existing HMAC primitive |
| Signing secret | **Retrievable** from dashboard + **rotate** action (not reveal-once) | It only lets a client *verify* our calls; losing it shouldn't brick verification |
| Subscription granularity | **Per API key**, ≤1 active endpoint | Key is the programmatic identity; enqueue resolves key→subscription via `api_key_task` |
| Retry window | **~24h**, config-driven, exponential backoff capped hourly (~25–30 attempts) | Self-heals realistic outages before `DEAD`; cheap under backoff; `DEAD` = genuine sustained failure |
| accept/reject over API | **Removed** from the API-key allow-list | Auto-settle makes them inapplicable; prevents stranding an A/B/C dispute |

## 5. Settlement model — WEB vs API branch

The only settlement-core change is a **branch in the validation gate on submission channel**:

```
Agent callback → record result → ValidationDomainService.validateAndGate
   │
   ├─ PASS
   │    ├─ WEB-submitted task  → PENDING_REVIEW      (unchanged; human reviews → accept/reject → dispute)
   │    └─ API-submitted task  → AUTO-SETTLE 85/15 → RESOLVED   (immediate, reuses the accept settlement)
   │
   └─ FAIL → SPEC_VIOLATION + auto-refund            (unchanged for both channels)
```

- **Channel detection:** a task is "API-submitted" iff it has an **`api_key_task` attribution row**
  (written by `SubmitOrchestrationAppService` for every API-key submission in Phase 3). This is the
  existing source of truth for programmatic submission — **no new task column required**.
- **Reuse:** auto-settle invokes the **same deterministic 85/15 settlement** the human `accept` path uses
  (`SettlementDomainService` / `SettlementWriteAppService`, already injected into
  `AgentCallbackAppServiceImpl`). It is not a new money path — only a new *trigger* for the existing one.
- **Terminal states that also refund an API task** (each a `task.failed` trigger): `SPEC_VIOLATION`
  (validation fail), `TIMED_OUT` (execution-timeout sweeper), `CANCELLED` (capacity-exhaustion sweeper),
  `FAILED` (non-`COMPLETED` agent status). All already refund today; Phase 4 only adds a webhook enqueue.

## 6. Event model

Two terminal event types (`result_ready` and `settled` collapse under immediate auto-settle):

**`task.completed`** — validated, auto-accepted, builder paid, result available. Thin ping:
```json
{ "event_id":"3f1c…", "type":"task.completed", "task_id":"b7f1…", "occurred_at":"2026-07-19T09:12:00Z" }
```
→ client verifies the signature, then `GET /api/tasks/{task_id}/result` for the deliverable.

**`task.failed`** — refunded. Thin ping **plus** the small terminal facts inline (nothing to fetch):
```json
{ "event_id":"9a2b…", "type":"task.failed", "task_id":"b7f1…",
  "reason":"SPEC_VIOLATION",  "refunded":120, "occurred_at":"2026-07-19T09:12:00Z" }
```
`reason ∈ { SPEC_VIOLATION, TIMED_OUT, CANCELLED, FAILED }`. For `SPEC_VIOLATION` the client can pull the
failing-check detail from the existing `GET /api/tasks/{id}/validation`.

**HTTP headers on every delivery:**
```
X-HireAI-Signature: t=<unix_ts>,v1=<hex hmac-sha256(secret, "{t}.{raw_body}")>
X-HireAI-Event-Id:  <delivery id — stable across retries, for client dedupe>
X-HireAI-Event-Type:<task.completed | task.failed>
Content-Type: application/json
```

## 7. Architecture

### 7.1 Shape — one new outbound adapter over the unchanged core

```
 validation PASS (API task) ─► [ TXN ] settle 85/15 · task RESOLVED · INSERT webhook_deliveries(task.completed, PENDING) ─commit┐
 validation FAIL / timeout / cancel ─► [ TXN ] refund · task <FAILED-state> · INSERT webhook_deliveries(task.failed, PENDING) ─commit┤
                                                                                                                                     ▼
                                                                                                                        webhook_deliveries (PENDING)
                                                                                                                                     │
                                   ── every ~5s, independently (own txn per row) ──                                                  │
                                   WebhookDeliverySweeper  ◄─────────────────────────────────────────────────────────────────────────┘
                                      pick PENDING & due  (FOR UPDATE SKIP LOCKED)
                                      resolve subscription (url + secret) ▸ SSRF-guard ▸ HMAC-sign ▸ POST callbackUrl
                                        2xx  → DELIVERED
                                        fail → attempts++, next_attempt_at = backoff ; ≥ MAX → DEAD, last_error
                                                    │
                                                    ▼
                                        client: verify sig ▸ dedupe on event_id ▸ GET /api/tasks/{id}/result
```

### 7.2 Producer — enqueue *inside* the settlement transaction (the outbox guarantee)

At each terminal-settlement transition for an API-submitted task, a delivery row is inserted **in the same
DB transaction as the money move + status change**. This is the whole point: the row commits atomically
with the state — an event can never fire for a rolled-back state (no *phantom*), and a committed state can
never lose its event (no *lost*). Mechanism:

- A single application helper, `WebhookOutboxAppService.enqueueTerminal(task, eventType)`, called
  synchronously within the settling transaction. It (a) resolves the submitting API key via
  `api_key_task`; (b) if that key has an **active** subscription, builds the **frozen** JSON payload and
  inserts a `PENDING` `webhook_deliveries` row (`next_attempt_at = now`); (c) otherwise no-ops (WEB tasks,
  or API tasks with no subscription — they reconcile by polling).
- Payload is frozen at enqueue so the exact signed bytes are identical on every retry.
- Implementation note: prefer the explicit in-txn call over an after-commit domain-event listener — the
  codebase publishes domain events *after* commit, which would break the atomic insert. (A
  `@TransactionalEventListener(BEFORE_COMMIT)` is an acceptable alternative; the plan decides.)

### 7.3 Consumer — `WebhookDeliverySweeper` (5th sweeper, existing pattern)

```
@Scheduled(fixedDelay = ${hireai.webhooks.sweep-interval:5s})  sweep():
  rows = SELECT … FROM webhook_deliveries
         WHERE status='PENDING' AND next_attempt_at <= now()
         ORDER BY next_attempt_at LIMIT ${batch}  FOR UPDATE SKIP LOCKED   -- multi-instance safe
  for each row (its own short transaction):
     sub = active subscription for the row  (url + signing secret; if gone/inactive → DEAD, last_error="subscription inactive")
     assertPublicHttps(sub.callbackUrl)     -- SSRF guard, re-checked HERE (DNS rebinding)
     ts = clock.now(); sig = hmacSha256Hex(sub.secret, ts + "." + row.payload)
     POST url  (bounded connect/read timeouts, no redirects)
     2xx      → status=DELIVERED, delivered_at=now, attempts++
     else     → attempts++;  if attempts >= ${max:28} → status=DEAD, last_error
                              else next_attempt_at = now + backoff(attempts);  last_error
```

- **Per-row transaction + `SKIP LOCKED`** → one dead endpoint never blocks others; safe under multiple app
  instances.
- **Delivery only reads the subscription and writes its own row — it never touches money/task tables.**
  That is what makes "a webhook failure can never affect the task or escrow" structural, not aspirational.
- Reuses a `RestClient` configured exactly like `AgentDispatchClient` (bounded timeouts via a
  `RestClientCustomizer`) — but **adds the SSRF guard** `AgentDispatchClient` omits (executor URLs are
  vetted at registration; client callback URLs are not).

### 7.4 Signing (Stripe-style)

- `signed_payload = "{unix_ts}.{raw_request_body}"`; `v1 = hex(HMAC_SHA256(subscription.secret,
  signed_payload))`; header `X-HireAI-Signature: t={ts},v1={v1}`.
- Sign the **exact raw bytes** we send (payload frozen at enqueue), so client verification over the raw
  body matches.
- Timestamp is the **replay guard**; document a client-side tolerance (e.g. 5 min).
- Reuse the HMAC-SHA256 primitive already implemented in `HmacDispatchTokenService` (extract a small shared
  `Hmac` utility if cleaner).

### 7.5 SSRF guard (new; ported from the arbitrator's `tools.py`)

A framework-free `WebhookUrlValidator`:
- scheme must be **HTTPS** (a dev-profile `allow-insecure-localhost` flag mirrors `AgentDispatchClient`);
- resolve the host (`InetAddress.getAllByName`) and **reject if any resolved IP is** private, loopback,
  link-local, wildcard/`0.0.0.0`, multicast, or otherwise reserved (covers `127.0.0.0/8`, `10/8`,
  `172.16/12`, `192.168/16`, `169.254/16`/metadata, `::1`, ULA, etc.);
- enforced **at registration** (fast feedback) **and again at send time** (DNS can rebind between the two).

### 7.6 Reconciliation — defense in depth (no silent loss)

A `DEAD` delivery means the *notification* failed; the task is correctly `RESOLVED`/refunded and the result
is retrievable regardless. Layers, weakest failure first:

1. **Long auto-retry (~24h)** self-heals realistic client outages before `DEAD`.
2. **Poll — always available, authoritative (Phase-3, reused).** The client already holds the task ID;
   `GET /api/tasks/{id}` (status), `GET /api/tasks/{id}/result` (deliverable, `NOT_FOUND` = not ready),
   `GET /api/tasks/{id}/validation` (failure detail) are all in the API-key allow-list. The webhook only
   changes *when* the client fetches, never *what* or *how*.
3. **Detect a miss:** `GET /api/webhooks/deliveries?since=…` lists recent deliveries + status
   (`DELIVERED`/`PENDING`/`DEAD`, attempts, last_error) — a missed event is queryable, not silent.
4. **Recover:** `POST /api/webhooks/deliveries/{id}/redeliver` flips a `DEAD`/failed row back to `PENDING`
   (dashboard "resend" button too).
5. **Human backstop:** the dashboard delivery log + a surfaced signal when a subscription is failing
   persistently (deliveries going `DEAD`).

We cannot force a client whose endpoint is permanently down and who never polls to notice — but we
guarantee the information is always **retrievable** and the human is **alerted**. That is the ceiling of
the pattern (Stripe/GitHub model: at-least-once push + always-available pull + event log + replay).

## 8. Data model additions — migration `V26` (additive; money tables untouched)

**`client_webhook_subscriptions`**
| column | notes |
|---|---|
| `id` uuid pk | |
| `api_key_id` uuid | FK → `api_keys.id`; **UNIQUE where active** (≤1 active endpoint per key) |
| `owner_id` uuid | denormalized client account, for owner-scoped reads |
| `callback_url` text | HTTPS; SSRF-validated at write |
| `signing_secret` text | generated server-side (`whsec_`-style); retrievable by owner; rotatable |
| `active` boolean | deactivate instead of hard delete |
| `created_at`, `updated_at` timestamptz | |

**`webhook_deliveries`** (the outbox = queue + delivery log + dedupe source)
| column | notes |
|---|---|
| `id` uuid pk | **doubles as `event_id`** (client dedupe) |
| `task_id` uuid | FK → tasks |
| `owner_id` uuid | owner-scoped reads |
| `subscription_id` uuid | FK → subscription (secret read at send; rotation applies) |
| `event_type` text | `task.completed` \| `task.failed` |
| `payload` jsonb | frozen at enqueue (stable signed bytes) |
| `target_url` text | snapshot at enqueue (robust to later URL change) |
| `status` text | `PENDING` \| `DELIVERED` \| `DEAD` |
| `attempts` int | default 0 |
| `next_attempt_at` timestamptz | sweeper claim key; index `(status, next_attempt_at)` |
| `last_error` text | nullable, for observability |
| `created_at`, `delivered_at` timestamptz | |

## 9. API surface

| Purpose | Endpoint | Auth |
|---|---|---|
| Register / replace callback | `POST /api/webhooks/subscription` (body: `callbackUrl`) → returns secret | **JWT (CLIENT)** |
| View subscription (+ secret) | `GET /api/webhooks/subscription` | JWT (CLIENT) |
| Rotate signing secret | `POST /api/webhooks/subscription/rotate-secret` | JWT (CLIENT) |
| Deactivate | `POST /api/webhooks/subscription/deactivate` | JWT (CLIENT) |
| List deliveries | `GET /api/webhooks/deliveries?since=&status=&taskId=` | JWT (CLIENT) **and** API key |
| Redeliver | `POST /api/webhooks/deliveries/{id}/redeliver` | JWT (CLIENT) **and** API key |
| (reused) Track / result | `GET /api/tasks/{id}`, `/result`, `/validation` | JWT + API key (unchanged) |

- **Subscription management is JWT-only** (like key management — a leaked API key must not be able to
  repoint the callback URL to exfiltrate results). Which key a subscription attaches to is chosen by the
  owner in the dashboard.
- **Deliveries read + redeliver** are reachable by the API key too, so an autonomous client can reconcile
  and replay without a human — they are owner-scoped and side-effect-limited (redeliver only re-enqueues an
  already-owned event).
- Ownership is enforced in the app services (Inv #5): a delivery/subscription for another owner is
  `NOT_FOUND`, not `403`.

### 9.1 Security allow-list change (`SecurityConfig.securedFilterChain`)

- **Remove** `POST /api/tasks/*/accept` and `/reject` from the `hasAnyRole("CLIENT","API_CLIENT")` block —
  they revert to the human-only default (`hasAnyRole("CLIENT","BUILDER","ADMIN")`). API clients no longer
  accept/reject (auto-settle handles it; disputes are human-only).
- **Add** `/api/webhooks/subscription/**` as `hasRole("CLIENT")` (JWT-only, mirrors `/api/keys/**`).
- **Add** `GET /api/webhooks/deliveries` and `POST /api/webhooks/deliveries/*/redeliver` as
  `hasAnyRole("CLIENT","API_CLIENT")`.
- ⚠️ **This is a `SecurityConfig` change** — heed `docs/post-mortem/2026-07-17-api-key-lockout-401-vs-403.md`:
  the full app returns **401** (not 403) for authenticated-but-forbidden; assert denied-status against the
  full app, and **run the entire backend suite** (a config change previously broke slice tests that load
  the secured chain — they need `@MockBean ApiKeyAuthService`).

## 10. Security & invariants

| # | Invariant | Effect |
|---|---|---|
| 1 | Escrow before execution | Unchanged. Auto-settle is still an explicit, recorded settlement of frozen escrow. |
| 2 | Append-only money/audit | Unchanged. `webhook_deliveries`/subscriptions are **not** money tables; no ledger writes on the delivery path. |
| 3 | Deterministic money path | **Reinforced.** Programmatic settlement is fully deterministic from the validation result; no LLM, no human, in the API money path. |
| 4 | Output-spec is the binding contract | **Central.** For the programmatic channel, validation *is* the acceptance test that releases or refunds escrow. |
| 5 | Server-side identity | Unchanged. Subscriptions and deliveries are owner-scoped; the API key resolves to the owning user as in Phase 3. |
| 6 | Signed, HTTPS-only I/O | **Extended** to outbound client callbacks (HMAC + HTTPS) **+ new SSRF guard** on the client-controlled URL. |

Additional: the signing secret is a credential (retrievable by the owner, rotatable); a leaked API key
cannot repoint the callback (subscription management is JWT-only); redeliver is idempotent-safe
(re-enqueues an owned event, never creates money movement).

## 11. Frontend

Two surfaces, both on the Mission-Control kit:

**(a) `/client/webhooks` — the webhooks console** (sibling to `/client/keys`):
- Register / replace the callback URL (choose which API key it attaches to); client-side + server-side
  HTTPS validation.
- Reveal the signing secret (retrievable) + **Rotate** action (confirm modal).
- Delivery log table: event type, task, status (`DELIVERED`/`PENDING`/`DEAD`), attempts, last error, time;
  a **Resend** button per row (→ `redeliver`).
- A banner/health flag when recent deliveries are failing.
- Nav link in the CLIENT surface next to *API keys*.

**(b) Delivery status *on the task views* (task-centric):**
- On the **task detail** page, a small **notification indicator** — *Delivered* / *Pending* / **Failed** —
  with a **Resend** action when failed, so a client sees a delivery problem in the context of the task
  without opening the console. Powered by `GET /api/webhooks/deliveries?taskId={id}` (empty ⇒ no webhook /
  WEB task ⇒ render nothing).
- Optionally a compact **Failed-delivery badge** on rows in the **tasks list** so failures are visible while
  browsing. (List badge is the trimmable part if the plan wants to keep scope tight; the detail indicator
  is the core.)

*(Frontend is a distinct workstream and can be split to a follow-up if the plan prefers; the API + backend
is the core of Phase 4.)*

## 12. Testing approach

Reuse the `test` profile + Testcontainers pattern; Docker-gated integration tests skip locally, run in CI.
- **Unit:** HMAC sign/verify (Stripe vectors); SSRF validator (accept a public host; reject
  loopback/private/link-local/metadata IPs, non-HTTPS); backoff schedule; payload freeze; channel-detection
  (api_key_task present ⇒ auto-settle).
- **Integration (Testcontainers Postgres):** validation-pass on an **API task** → `RESOLVED` + a `PENDING`
  `task.completed` row committed **in the same transaction**; validation-fail → `task.failed` row;
  **WEB task** → `PENDING_REVIEW`, **no** delivery row; sweeper delivers to a stub endpoint → `DELIVERED`;
  a 5xx stub → `attempts++`/backoff → `DEAD` after max; `redeliver` re-enqueues; `SKIP LOCKED` claim.
- **Full-app (RANDOM_PORT):** the allow-list change — API key **can** submit/track/list-deliveries/redeliver
  and **cannot** accept/reject (**401**) or manage the subscription (**401**); a signed delivery verifies
  end-to-end against a local receiver.
- **Frontend (vitest):** register form validation, reveal/rotate secret, delivery log render, resend; the
  task-detail delivery indicator (Delivered/Pending/Failed + Resend; renders nothing when no deliveries).
- **Live E2E (per [[verify-with-real-e2e]]):** boot against Supabase, submit via API key, point the
  callback at a local receiver (e.g. an HTTPS tunnel / request-bin), assert a valid signed `task.completed`
  arrives and the fetched result matches; kill the receiver to force `DEAD` → confirm poll + `redeliver`
  recover it. *(Confirm 401-vs-403 against the full app, per the post-mortem.)*

## 13. Open questions (small; sensible defaults in hand, none blocking)

- **Q1.** Exact backoff schedule + `MAX_ATTEMPTS` for the ~24h window (default `10s,30s,1m,5m,15m,30m,1h,
  then hourly → ~28 attempts`; all config-driven).
- **Q2.** Do human-channel tasks with (somehow) an associated subscription also emit `task.completed`?
  Default **no** — enqueue only when an `api_key_task` attribution + active subscription exist (webhooks
  are a programmatic-channel feature).
- **Q3.** Delivery-log retention (default: keep; add a pruning sweeper later if volume warrants).
- **Q4.** Frontend in this phase vs a fast-follow (default: in this phase).

## 14. Out of scope / future

MCP facade + OpenAPI (Phase 5); programmatic dispute API / post-settlement clawback; multiple endpoints per
key; per-event-type subscription filters; dual-signing secret-rotation grace; auto-disable of chronically
failing endpoints (we surface, we don't disable); official SDKs; real-money settlement.

## Appendix — decision ledger

| Decision | Choice |
|---|---|
| Programmatic settlement | Deterministic, immediate on validation |
| Disputes (API channel) | None; human dashboard only, human-submitted tasks |
| Delivery mechanism | Transactional outbox + `@Scheduled` sweeper |
| Events | `task.completed`, `task.failed` (terminal, thin) |
| Payload | Thin ping; client fetches result via existing `GET /result` |
| Signing | Stripe-style HMAC-SHA256 over `"{ts}.{body}"`; timestamp replay guard |
| Signing secret | Retrievable + rotate; dual-sign grace deferred |
| Subscription | Per API key, ≤1 active; JWT-only management |
| SSRF | HTTPS-only + resolve-and-reject-private, at registration **and** send |
| Retry | ~24h, config-driven exponential backoff, `DEAD` after max |
| Reconciliation | poll (reused) + deliveries-list + redeliver + dashboard/alert |
| accept/reject over API | Removed from the allow-list |
| Migration | `V26` (`client_webhook_subscriptions`, `webhook_deliveries`) |
