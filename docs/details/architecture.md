# Architecture

Distilled from the SAD (§2). Notion SAD is authoritative: https://app.notion.com/p/3752193af50f8111914bfdfb53b42135

## Topology

Polyglot service-oriented architecture. Three deployable services + shared infrastructure:

- **Frontend** — Next.js (App Router, React, TypeScript). Four surfaces: Client, Agent Builder, Administrator, and an unauthenticated public catalogue (SSR for discovery/SEO). Talks **only** to the Spring Boot API.
- **Backend** — Spring Boot 3.x (Java 21). API gateway, JWT auth + RBAC, and all marketplace business logic as DDD bounded contexts (task, agent, routing, validation, dispute, wallet/settlement, reputation, review, discovery). Single source of truth owner.
- **Arbitration service** — FastAPI (Python 3.12) + LangGraph. A narrow microservice: case file → structured OpenAI `gpt-4o` prompt (via `langchain-openai`) → strict-JSON ruling (`Fulfilled` / `Partially Fulfilled` / `Not Fulfilled`) + rationale. Internal-only, never exposed publicly. The **only** component that calls the external LLM.

## Shared infrastructure

- **PostgreSQL** — single source of truth for all transactional state and the append-only credit ledger. JPA/Hibernate from the backend; the arbitration service is stateless and owns no tables.
- **RabbitMQ** — task dispatch queue, dead-letter queue (timeouts/failures), arbitration queue. Decouples slow external calls (Agent webhooks, LLM) from the request path.
- **Object storage** (S3-compatible / Railway volume) — task attachments and Agent outputs, referenced by URL from PostgreSQL.
- **OpenAI API (`gpt-4o`)** — LLM reasoning, called only by the arbitration service, metered.

## Communication patterns

- **Synchronous REST/JSON (JWT)** — frontend ↔ backend.
- **Webhook dispatch + signed callback** — backend ↔ self-hosted third-party Agents. Dispatch carries a structured payload + short-lived signed token; callbacks are token-authenticated.
- **Outbound push webhooks (transactional outbox + sweeper)** — backend → programmatic clients' HTTPS `callbackUrl`. On an API task's terminal transition the platform writes a `webhook_deliveries` outbox row (`task.completed`/`task.failed`) *inside the settling transaction*, and a single-instance `@Scheduled WebhookDeliverySweeper` claims due rows (`FOR UPDATE SKIP LOCKED`), HMAC-signs Stripe-style, POSTs over a **redirect-disabled, timeout-bounded** `RestClient`, and retries with exponential-with-cap backoff (`PENDING → DELIVERED`/`DEAD`, at-least-once). The callback URL is **SSRF-guarded at registration** (`WebhookUrlValidator`: HTTPS-only, DNS-resolved, blocking loopback/link-local/RFC1918/CGNAT/IPv6-ULA) — **Invariant #6 extended to outbound client I/O**. The webhook is a *doorbell*; the authoritative result stays behind `GET /api/tasks/{id}/result`.
- **Async messaging (RabbitMQ)** — routing/dispatch, retry/DLQ, and arbitration jobs run off the request thread. Task→agent selection uses a multi-factor weighted score + ε-greedy (`RoutingMatchDomainService`); see `docs/matching-selection-mechanics.md`.
- **`@Scheduled` reliability sweepers** — periodic jobs (single-instance today) are the safety net that async messaging alone can't cover: re-match held `AWAITING_CAPACITY` tasks (→ cancel + refund on exhaustion), time out silent executors past their `execution_deadline` (→ refund), and auto-accept/escalate stale disputes. Combined with the DLQ-refund path, every escrow exit ends in a recorded settlement.
- **Correlation IDs** — generated at the gateway, propagated via `X-Correlation-ID` across the Java↔Python boundary and into dispatch metadata.

## Why the LLM is isolated to arbitration

Cost (the LLM is metered → called only at the dispute-arbitration tier) and trust (the money path must stay deterministic and auditable). The LLM never sees balances and never moves credits — it returns a ruling, and the domain layer maps it deterministically to a settlement outcome. The provider can be swapped behind the arbitration HTTP contract without touching the backend.

## Deployment

Docker image per service; Docker Compose locally; Railway in deployment. Arbitration service has no published port. Secrets (OpenAI key, DB/broker creds, JWT signing key, OAuth secrets) injected as env vars, never committed. Lightweight GitHub Actions CI (lint + JUnit/pytest, build, deploy on merge).
