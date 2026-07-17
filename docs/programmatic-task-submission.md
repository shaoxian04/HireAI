# Programmatic Task Submission (Client Agents) — Design & Requirements

> **Status:** Phase 3 (the API-key submission spine) **BUILT**; Phase 4 (push webhooks) and Phase 5 (MCP
> server + OpenAPI) remain draft/deferred — see §0. · **Date:** 2026-06-24 (build status added 2026-07-16)
> · **Owner:** Shaoxian
> This is a design & requirements alignment document, **not** an implementation spec. It captures *what* we are
> adding and *how it fits the architecture* — not class-level implementation detail. It feeds the PRD update.

## 0. Build status (2026-07-16)

**Phase 3 — the API-key programmatic submission spine — is BUILT** (migration `V25`, over the unchanged
submit/escrow/routing/settlement core): API-key authentication (`ApiKeyAuthenticationFilter` sets the
same UUID principal as the JWT filter, under a single `ROLE_API_CLIENT` authority), a submit-scoped
security allow-list (submit/track/settle only — key management stays JWT-only), owner-scoped **idempotent
submission** (`Idempotency-Key`, same-transaction dedupe), and **two** per-key spend caps (concurrent
frozen escrow + rolling-24h daily spend, either exceeded → 409 `SPEND_CAP_EXCEEDED`). Key management
(create/list/revoke) is live at `POST/GET /api/keys` and the `/client/keys` dashboard page. Full detail:
`CLAUDE.md` (build-status paragraph), `docs/details/identity-and-authz.md` (the auth filter + allow-list),
`docs/details/data-model.md` (`V25` — `api_keys`/`idempotency_keys`/`api_key_task`).

**Not built — still future work, unchanged from the design below:**
- **Phase 4 — signed push webhooks** (§6.5–6.6, the "Push" half of §8.7, `callbackUrl` + HMAC + the SSRF
  guard, `client_webhook_subscriptions` / `webhook_deliveries`). Today a client agent can only **poll**
  (§8.7 "Poll" — this half is built, reusing the existing `GET` endpoints under API-key auth).
- **Phase 5 — the MCP server facade + an OpenAPI document** (§6.8, the "MCP" row in §7, §8.8).
- **Key rotation-with-grace and per-key scopes** (§6.1–6.2, §8.3) — a key today carries a name and the two
  spend caps only; there is no scope concept and no rotate endpoint (create / list / revoke only).

The rest of this document is the original design and requirements — read it as the roadmap the build
above implements the first slice of, not as a build log.

## 1. Summary

Today the only way to submit a task is interactively, through the HireAI web application, authenticated by a
user JWT. This expansion adds a **programmatic submission channel** so that an external **client agent** — often
itself an AI agent — can submit and track tasks via an **API key**, and let the platform route the work to a
suitable registered third-party executor agent.

It is delivered as **new adapters at the edges** over an **unchanged submission/escrow/routing/settlement core**:

- a **REST API** path authenticated by API keys (the spine),
- **idempotent** submission and **two result-delivery modes** (polling + signed push webhook), and
- a thin **MCP server facade** (local/stdio) for MCP-compatible client agents.

No SDK is in scope. All six hard invariants are preserved; two are extended.

## 2. Motivation & vision

HireAI remains a neutral broker, but the entry point widens from *"a human submits a task"* to *"any
authenticated client — human or autonomous agent — submits a task."* This is the first concrete step toward
**agents hiring agents**: a client's own AI agent can delegate a well-specified sub-task to a vetted specialist on
the marketplace.

This is framed as **promoting part of the already-deferred PRD §7.3** (programmatic/MCP integration) into scope —
not a new product direction.

## 3. Persona

**Client Agent** *(a.k.a. programmatic client)* — an external automated system, often itself an AI agent, that
submits and tracks tasks via the API or MCP server on behalf of an accountable **client/org account**. It is a
**credential of that account** (it shares the account's wallet, ownership, and dispute rights), **not** a new
free-floating identity. The existing personas (interactive client, agent builder, admin) are unchanged.

## 4. Scope

**In scope**
- Programmatic task submission via REST — both **routed** (`POST /api/tasks`, the headline) and **direct booking**
  (`POST /api/tasks/direct`).
- **API-key** authentication (hashed at rest, scopes, per-key spending caps, rotation, revocation).
- **Idempotent** submission (machine-safe retries).
- **Result delivery**: synchronous **polling** (reuse) + asynchronous **signed HMAC push webhook** (new).
- **MCP server facade** (local/stdio), API-key authenticated, polling for results.
- An **OpenAPI document** as API documentation (docs only — not an SDK pipeline).

**Out of scope (clarified, not removed)**
- **Agent-to-agent chaining** (a registered *executor* agent delegating sub-tasks mid-execution) — stays future
  (PRD §7.4). An external client agent submitting from outside is a *different* thing and is in scope.
- **Executor-side** MCP / A2A transport — executors still integrate via HTTPS webhook only (PRD §7.3).
- Full **MCP / A2A wire-protocol** compliance and **remote (hosted, OAuth-secured) MCP** servers — future.
- **Official client SDKs** (hand-written or generated) — future; raw REST + OpenAPI + MCP suffices for now.
- Real-money settlement; hosted agent execution (unchanged out-of-scope items).

## 5. User stories (Programmatic Client / Client Agent)

- **US-P1** — As an integrating client, I generate/rotate/revoke **API keys** so my systems submit tasks without a
  human login.
- **US-P2** — As a client agent, I submit a task in **one authenticated API call** (description, category,
  `output_spec`, budget) and receive a task ID.
- **US-P3** — As a client agent, my submission is **idempotent** (idempotency key) so retries never double-submit
  or double-freeze escrow.
- **US-P4** — As a client agent, I receive the result via a **signed webhook callback** to my registered URL when
  the task settles.
- **US-P5** — As a client agent, I can still **poll** status/result as a fallback.
- **US-P6** — As an integrating client/org, I set a **per-key spending cap** and revoke keys, so an autonomous
  agent can't overspend and a leaked key is contained.
- **US-P7** — As a client agent, I can let the platform **route** to the best match (headline) or **pin a specific
  agent** (direct booking).

## 6. Functional requirements

1. A client/org user can create, list, rotate, and revoke API keys; only a hash and a display prefix are stored.
2. Each key carries scopes and an optional per-key spending cap (an authorization guard at submit time).
3. The REST submit endpoints accept an API key as an alternative credential to a user JWT; the owning user is
   resolved server-side from the key. Path/body identity is never trusted.
4. Submission accepts an `Idempotency-Key`; replay with the same key + payload returns the same task; replay with a
   different payload under the same key is rejected.
5. A client may provide a `callbackUrl` (per submission, or a per-key default) that must be HTTPS and pass an
   ownership/reachability check and an SSRF (private-range) check.
6. On `task.result_ready` and `task.settled`, the platform delivers a signed, typed event to the `callbackUrl`
   with at-least-once semantics and a unique `event_id`.
7. Status and result remain pollable under API-key auth.
8. An MCP server exposes `submit_task`, `get_task_status`, `get_task_result`, and `list_agents`, authenticated by
   an API key, returning results via polling.

## 7. API surface (conceptual)

| Purpose | Surface |
|---|---|
| Key management | Dashboard (and optionally API): create / list / revoke keys, set scope + spend cap |
| Submit (routed) | `POST /api/tasks` — API-key auth, `Idempotency-Key`, optional `callbackUrl` |
| Submit (direct) | `POST /api/tasks/direct` — same, pins a chosen agent |
| Track | `GET /api/tasks/{id}` (status), `GET /api/tasks/{id}/result` (payload) |
| Settle | `POST /api/tasks/{id}/accept` · `POST /api/tasks/{id}/reject` (existing) |
| Result callback | Platform → client `callbackUrl`, HMAC-signed, on settlement |
| MCP | Tools: `submit_task`, `get_task_status`, `get_task_result`, `list_agents` |

## 8. Architecture

### 8.1 Shape — two new inbound adapters + one new outbound adapter over an unchanged core

```
   Human (browser) ──JWT──┐
                          ├─► [Auth seam: CurrentUserProvider] ─► Task/Wallet/Routing core (UNCHANGED)
   Client agent (REST) ─API key─┤        (resolves owning user)        │
   Client agent (MCP) ──API key─┘                                      │
                                                                       ▼
                                          [Outbound callback adapter] ──HMAC-signed──► client callbackUrl
```

### 8.2 Authentication seam (the key insight)
A new **API-key authentication filter** runs alongside the existing JWT filter. On a valid key it populates the
*same* security context, so `CurrentUserProvider.currentUserId()` returns the owning user exactly as for JWT. The
controllers, application services, and ownership checks **do not change**. Identity is resolved server-side from
the credential — never from the request body (REST) or tool arguments (MCP).

### 8.3 API key model
Store only `SHA-256(key)` plus a short display prefix (e.g. `hk_live_a1b2…`), with: owning `user_id`, scopes,
`spend_cap`, status, `last_used_at`, `revoked_at`. Created/revoked/rotated from the dashboard. The spend cap is an
**authorization guard at submit time** (reject if this key's frozen escrow would exceed the cap) — **not** a second
ledger, so the append-only money invariant stays clean and the ledger remains the single source of truth.

### 8.4 Identity & wallet
The key maps to its owning client/org account. Escrow draws on **that account's existing wallet**; ownership and
dispute rights are the account's. No new wallet or account type.

### 8.5 Idempotency
`Idempotency-Key` header → an idempotency record (unique per owner) maps key + request fingerprint → the created
`task_id`. Replay returns the same task (no second freeze); a conflicting payload under the same key is rejected.
This protects "escrow before execution" against machine retries.

### 8.6 Submission flow — pure reuse
From the controller inward, both routed and direct submission follow the **existing path**: submit → escrow freeze
in the same transaction → `TaskSubmitted` domain event after commit → routing/dispatch. Nothing new in the core.

### 8.7 Result delivery
- **Poll:** reuse `GET /api/tasks/{id}` + `/result` under API-key auth (`NOT_FOUND` = "not ready, keep polling").
- **Push:** a new outbound callback adapter publishes typed events (`task.result_ready`, `task.settled`) to the
  client's `callbackUrl`, **HMAC-signed** (`X-HireAI-Signature: t=<ts>,v1=<hmac>` over `ts.body`, timestamp blocks
  replay), with **at-least-once** delivery (retry + backoff via the existing RabbitMQ + DLQ machinery) and an
  `event_id` for client-side dedupe. **SSRF guard** rejects private/internal target URLs.
- The callback fires on result-ready; the client agent then calls the existing `accept`/`reject` to settle.

This mirrors the existing outbound dispatch (platform → executor agent), pointed the other way (platform →
client agent).

### 8.8 MCP facade
A thin MCP server (local/stdio) exposing the four tools, authenticated by an **API key in env**, calling the
**same application services** (or the REST API) underneath. Results via **polling** (`get_task_result`), since push
does not map cleanly onto MCP's request/response model. No new business logic — protocol translation only.

### 8.9 Data-model additions (conceptual)
`api_keys`, `idempotency_keys`, `client_webhook_subscriptions` (per-key default callback + signing secret;
per-submission override on the task), and a `webhook_deliveries` log. Money tables (`ledger_entries`,
`reputation_events`) are **not** touched — append-only invariants intact.

## 9. Security & invariants

| # | Invariant | Status |
|---|---|---|
| 1 | Escrow before execution | Unchanged; idempotency prevents double-freeze |
| 2 | Append-only money/audit | Spend cap is an authorization check, not a ledger |
| 3 | Deterministic money path | Unchanged |
| 4 | Output spec is the binding contract | Unchanged (routed = client spec, direct = agent spec) |
| 5 | Server-side identity | **Extended** — derived from API-key principal, never from body/tool args |
| 6 | Signed, HTTPS-only I/O | **Extended** to outbound client callbacks; **+ new SSRF guard** |

## 10. Error handling
Errors surface via the existing `WebResult` / `ResultCode` envelope:
- `401` for missing/invalid/revoked key;
- rejection for spend-cap exceeded or insufficient escrow;
- `409` for idempotency conflict (same key, different payload);
- webhook delivery failures go to the delivery log + DLQ and **never block task progress**;
- MCP tool errors map to MCP error responses.

## 11. Testing approach
Reuse the existing `test` profile + Testcontainers pattern.
- **Unit:** key hash/verify, HMAC sign/verify, idempotency dedupe, SSRF URL validation.
- **Integration:** submit-via-key → escrow freeze → route; idempotent retry returns same task; callback delivery on
  a Testcontainers RabbitMQ.
- **MCP:** tool → application-service mapping.

## 12. Out of scope & future work
Agent-to-agent chaining; executor-side MCP/A2A transport; full MCP/A2A wire protocols; remote OAuth-secured MCP;
official client SDKs; OAuth2 client-credentials auth tier; real-money settlement; hosted execution.

## 13. Open questions
- **Q-A.** Should spending caps be enforced per key, per client/account, or per target agent?
- **Q-B.** When should the programmatic channel graduate from API keys to OAuth2 client-credentials (and a remote,
  OAuth-secured MCP server)?
- **Q-C.** Should result callbacks support per-event-type subscriptions, or always deliver both event types?

## Appendix — decision ledger

| Decision | Choice |
|---|---|
| Scope altitude | Channel + A2A vision (new persona; no chaining) |
| Programmatic auth | Static API keys (hashed, scopes, spend caps, rotate/revoke) |
| Identity | Key owned by a client/org account; acts as that user |
| Submission modes | Routed (headline) + direct booking, via API |
| Idempotency | Required (`Idempotency-Key`) |
| Result delivery | Poll (reuse) + signed HMAC push webhook (new) |
| Settlement loop | Client agent calls existing `accept`/`reject` after result |
| MCP | Local/stdio facade, API-key auth, polling; remote-OAuth = future |
| SDK | Out of scope; OpenAPI as docs only; SDK = future |
