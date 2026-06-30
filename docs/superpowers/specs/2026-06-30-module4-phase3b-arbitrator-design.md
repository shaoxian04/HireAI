# Module 4 Phase 3b — Arbitrator Service + Ruling Transparency — Design Spec

**Date:** 2026-06-30
**Status:** design (awaiting user review → writing-plans)
**Builds on:** Module 4 Phase 3a (async RabbitMQ arbitration transport, merged via PR #15). The Java side already publishes a dispute to `task.dispute.requested` (dispute → `ARBITRATING`), exposes the shared-secret ruling callback, and dead-letters poison requests to a fallback refund. Phase 3b builds the **worker that was being awaited**, plus the transparency read it produces.

## Decisions locked in brainstorming
- **Same repo (monorepo).** New `arbitration/` service; separate process/deploy (own Railway service), not a separate repo. "Separate service ≠ separate repo."
- **Agent depth = deliberation + tools** (the richest option): multi-node LangGraph with tool nodes.
- **Tools incl. full-file read** with an SSRF guard.
- **Model = OpenAI `gpt-4o`** via `langchain-openai` (was Claude; the arbitrator is the platform's only LLM consumer, so the AI side is now OpenAI — docs/`CLAUDE.md`/memory to be updated).
- **Transparency folded in:** the arbitrator's justification must be visible to both the client and the agent's builder. Backend read + frontend display are part of Phase 3b.
- **Ruling history seam laid now:** an append-only `dispute_rulings` child table so an arbitrator ruling and a future Administrator override are stored *separately* (Invariant #2). Only the arbitrator/fallback write to it today; the Administrator (tier-2) path stays an unbuilt, documented seam.

## Goal
Make disputes actually adjudicated: a Python LangGraph worker consumes a dispute request, retrieves the evidence (incl. fetching FILE outputs), judges whether the agent's output meets the declared `acceptanceCriteria` and the client's complaint, and posts back a deterministic category + a human-readable rationale — which both parties can then see. The LLM produces only a *category + rationale*; the Java domain owns all money (Inv #3).

## Scope

**In:**
1. `arbitration/` — Python FastAPI + LangGraph + OpenAI worker (RabbitMQ in, shared-secret HTTP callback out).
2. Backend — append-only `dispute_rulings` ruling history (replaces the single inline ruling slot); a transparency read exposing the ruling(s) to the task's client **and** the agent's builder.
3. Frontend — show the arbitrator's decision + justification on the client's task/review view and the builder's task view.
4. CI — a path-filtered job that lints/tests `arbitration/`.

**Out (deferred, unchanged):** tier-2 appeals + the Administrator role/UI/write-path (the `dispute_rulings` table accommodates it; nothing writes an `ADMINISTRATOR` ruling yet); the arbitration timeout sweeper (a worker that ACKs but never rules and never dead-letters still strands a dispute — Plan 2); SSE push (browser keeps polling).

---

## Architecture

```
                         RabbitMQ
 Java backend  ──publish──▶  task.dispute.requested  ──consume──▶  Python arbitrator
 (Phase 3a)                       │ (DLQ on failure)                 (LangGraph + gpt-4o)
      ▲                           ▼                                        │
      │                  task.dispute.requested.dlq                        │ POST ruling
      │                           │                                        ▼
      └── ArbitrationDlqListener ◀─┘                 /api/arbitration-callbacks/{id}/ruling
          (fallback full refund)                     (shared secret; applyRuling; first-ruling-wins)
                                                                │
                                          dispute_rulings (append-only)  ──read──▶  client + builder UI
```

The worker is a **separate service** — its own process, language, and Railway deployment, coupled to the backend only through the RabbitMQ queue and the HTTP callback (the contracts Phase 3a already defined).

---

## Component 1 — The Python arbitrator service (`arbitration/`)

### Stack & layout
- **Python 3.12**, `uv` (deps/venv), `ruff` (lint), `pytest` (tests).
- **LangGraph** (graph orchestration) + **`langchain-openai`** (`ChatOpenAI`, `gpt-4o`) for tool-calling + structured output.
- **`aio-pika`** (async RabbitMQ consumer, fits asyncio).
- **FastAPI** — a `/health` (and `/ready`) endpoint for Railway; the consumer runs as an asyncio background task started on app startup.
- Layout (indicative):
  ```
  arbitration/
    app/
      main.py            # FastAPI app + lifespan: start/stop the consumer
      config.py          # pydantic-settings (env)
      consumer.py        # aio-pika consumer; ack-discipline
      callback.py        # HTTP client → ruling callback
      graph/
        state.py         # graph state model
        nodes.py         # ingest / gather_evidence / deliberate / classify / critique / respond
        tools.py         # fetch_result_content (guarded), validate_against_schema
        build.py         # assemble the LangGraph
      schemas.py         # ArbitrationRequestMessage, RulingResult (pydantic)
    tests/
    pyproject.toml  Dockerfile  README.md
  ```

### The LangGraph (deliberation + tools)
1. **`ingest`** — parse the inbound message into graph state.
2. **`gather_evidence`** (tool loop) —
   - **`fetch_result_content(url)`** — for `format == FILE`, fetch the **full** content over HTTPS. **SSRF guard:** HTTPS-only; resolve the host and reject private/loopback/link-local/metadata IPs; connect + read timeouts; a max-size cap (oversize → truncate with an explicit marker before it reaches the model); content-type handling (text/JSON inline; binary → describe/skip or pass to a vision-capable model when it's an image).
   - **`validate_against_schema(payload, schema)`** — re-run the JSON-Schema check as citable grounding evidence (the structural check the Java gate already passed; here it's evidence the model can reference, not a gate).
3. **`deliberate`** — the model reasons over `acceptanceCriteria` + the gathered evidence + the client's `reasonCategory` complaint. This is the **subjective** judgment the validation gate deliberately skipped. Tools are invoked here via the LangGraph tool loop.
4. **`classify`** — **structured output** → `{ category ∈ {FULFILLED, PARTIALLY_FULFILLED, NOT_FULFILLED}, rationale }`.
5. **`critique`** (self-check) — verify the category is consistent with the stated criteria and the evidence before finalizing; one bounded revision pass.
6. **`respond`** — POST `{category, rationale}` to the callback; **ack only after the callback returns 2xx** (ack-discipline).

### Inbound contract (consumed)
Queue `task.dispute.requested`. Body is JSON (Spring `Jackson2JsonMessageConverter`; a `__TypeId__` header is present and ignored). Fields (camelCase, from `ArbitrationRequestMessage`):
`disputeId, taskId, correlationId, format` (`TEXT|JSON|FILE`)`, schema` (nullable JSON-Schema)`, acceptanceCriteria` (nullable)`, resultPayloadJson` (inline for TEXT/JSON; may be null for FILE)`, resultUrl` (FILE url, nullable)`, reasonCategory` (`A_MISMATCH|B_FACTUAL|C_INCOMPLETE`).

The worker **does not redeclare** the topology — the Java backend owns it. It declares the queue **passively** (or with args identical to `RabbitArbitrationConfig`) so an arg mismatch can't brick the consumer; documented in both codebases.

### Outbound contract (produced)
`POST {BACKEND_BASE_URL}/api/arbitration-callbacks/{disputeId}/ruling`, header `Authorization: Bearer {ARBITRATION_CALLBACK_SECRET}`, body `{"category": "...", "rationale": "..."}`. Responses: `200` success/idempotent-noop, `401` bad secret, `400` bad category, `404` unknown dispute. The callback is idempotent (first-ruling-wins) so at-least-once delivery is safe.

### Config (env, via pydantic-settings)
`OPENAI_API_KEY`, `OPENAI_MODEL` (default `gpt-4o`), `RABBITMQ_URL`, `ARBITRATION_CALLBACK_SECRET`, `BACKEND_BASE_URL`, fetch limits (`FETCH_MAX_BYTES`, `FETCH_TIMEOUT_SECONDS`).

### Failure handling / ack-discipline
- Transient errors (OpenAI 429/5xx, network blips, callback 5xx/timeout) → a small bounded in-process retry with backoff.
- Persistent failure (graph error, repeated callback failure, un-fetchable evidence) → **nack(requeue=false)** → the message dead-letters to `task.dispute.requested.dlq` → the existing Java `ArbitrationDlqListener` resolves the dispute by **fallback full refund** (兜底). Every dispute still terminates.
- A malformed/un-parseable message → nack-to-DLQ (same fallback).

---

## Component 2 — Backend: append-only ruling history + transparency read

### Migration `V21__dispute_rulings.sql`
- Create `dispute_rulings` (append-only — DB trigger raising on UPDATE/DELETE, matching `ledger_entries`/`reputation_events`, per Inv #2):
  `id, dispute_id, tier INT, decided_by TEXT CHECK (ARBITRATOR|ADMINISTRATOR|FALLBACK), category TEXT CHECK (FULFILLED|PARTIALLY_FULFILLED|NOT_FULFILLED), rationale TEXT, decided_at TIMESTAMPTZ, gmt_create`.
- Migrate any existing inline ruling from `disputes` into a `dispute_rulings` row, then **drop** the inline `disputes.ruling_category / ruling_rationale / ruling_tier / decided_by` columns. The `disputes` row keeps `status` + `resolved_at`; the **effective ruling = the highest-tier `dispute_rulings` row**.

### Domain / app changes
- `DisputeModel` records a ruling **history** (append a `Ruling`); `effectiveRuling()` = highest tier. `RulingDecidedBy` gains `ADMINISTRATOR` (value only; no writer yet).
- `applyRuling(disputeId, RulingInfo)` **appends** a tier-1 `ARBITRATOR` ruling; `resolveByFallback` appends a `FALLBACK` ruling (category `NOT_FULFILLED`, system rationale). **Settlement is unchanged** — it is still computed at apply-time from the incoming ruling category (deterministic, Inv #3); it does not re-read.
- **Transparency read:** `GET /api/disputes/by-task/{taskId}` → `{ taskId, status, effectiveCategory, rulings: [{tier, decidedBy, category, rationale, decidedAt}] }`. **Authorization (Inv #5): the task's client OR the owning builder** (the builder of an agent that ran the task); anyone else → 403. `FALLBACK` rulings render as "auto-resolved (arbitrator unavailable) — full refund."

## Component 3 — Frontend
- **Client** task/review view: when a task went through a dispute, show the arbitrator's decision + rationale (and, in future, any administrator override beneath it).
- **Builder** task view (their routed tasks / earnings detail): the same dispute outcome for tasks done by their agents.
- Both read the new endpoint via the existing `api()` client; reuse the Mission-Control kit/tokens. New `PARTIALLY_ACCEPTED`/dispute states get explicit display handling (closes the frontend follow-up flagged in Phase 2).

## Component 4 — CI
A path-filtered job (`arbitration/**`) running `ruff` + `pytest` (and `uv` install), independent of the Java/Next jobs.

---

## Invariants & safety
- **#3 deterministic money** — the LLM returns only `{category, rationale}`; settlement is computed Java-side from the category. `rationale` is human-facing text, never in the money path.
- **#6 signed/secret service I/O** — the callback is shared-secret (constant-time compared, already built); the worker holds the secret in env and sends it as a bearer token.
- **#2 append-only audit** — `dispute_rulings` is append-only (trigger-enforced); an Administrator override never erases the arbitrator's ruling.
- **SSRF** — the fetch tool is HTTPS-only with private-IP/timeout/size guards.
- **Ack-discipline** — ack only after the ruling is accepted; otherwise nack → DLQ → fallback refund. Combined with the idempotent callback, this is safe under at-least-once delivery.

## Testing
- **Python (`pytest`):** each graph node with the model mocked / a fake LLM; the guarded fetch tool incl. **SSRF rejection** (private IP, non-https), size cap, timeout; message parsing (golden `ArbitrationRequestMessage` JSON); the callback client (mocked HTTP, incl. 401/404 handling); a **contract test** pinning the inbound message + outbound callback shapes to the Java side; optionally a Testcontainers-RabbitMQ end-to-end (publish → mocked OpenAI → assert callback posted, then ack).
- **Java:** `dispute_rulings` history (append, effective ruling, append-only trigger), `applyRuling`/`resolveByFallback` append correctly, settlement still computed from the effective ruling, the transparency endpoint's authz (client ✓, owning builder ✓, stranger 403), migration round-trip (Testcontainers).
- **Frontend:** vitest for the dispute-outcome display in both surfaces.

## Deployment (Railway)
A new Railway service from `arbitration/` (Dockerfile: `python:3.12-slim`, `uv` install, run `uvicorn` serving FastAPI + the consumer task). Env vars as above. Requires a RabbitMQ broker reachable by both the backend and the worker (Railway RabbitMQ plugin or CloudAMQP via `RABBITMQ_URL`).

## Open questions for review
1. **OpenAI model** — `gpt-4o` default confirmed? (o-series reasoning model is the alternative for deeper deliberation at higher cost.)
2. **Ruling-history seam now vs defer** — proceeding with "now" per the brainstorm; confirm.
3. **Transparency endpoint shape** — one shared `GET /api/disputes/by-task/{taskId}` (client-or-builder authz), or split client/builder endpoints? (Spec assumes the shared endpoint.)
4. **FILE binary handling** — for non-text files (images/PDF), use `gpt-4o` vision vs. describe-and-skip? (Spec leaves the tool extensible; default to text/JSON inline + best-effort for images.)
