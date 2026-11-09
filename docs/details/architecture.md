# Architecture

Topology and communication, as built. The SAD is authoritative for design (see the link in `CLAUDE.md`).

## Services

- **Frontend** (`frontend/`): Next.js App Router. Client, Builder and Admin surfaces behind login, plus a static landing page. The catalogue is client-rendered and needs a login. Talks only to the Spring Boot API.
- **Backend** (`backend/`): Spring Boot, Java 21. JWT and API-key auth, and all marketplace logic as DDD subdomains. Owns all state.
- **Arbitration** (`arbitration/`): FastAPI + LangGraph worker. Consumes dispute jobs from RabbitMQ, calls OpenAI `gpt-4o`, posts a `{category, rationale}` ruling back over an authenticated HTTP callback. Stateless, and the only component that calls an LLM (the backend calls only OpenAI's embeddings endpoint).
- **MCP server** (`mcp/`): stdio facade translating four tools into API-key REST calls. No logic or storage of its own.
- **Demo agent** (`demo-agent/`): a stub third-party Agent for local demos. Not part of the product.

## Shared infrastructure

- **PostgreSQL** with the **pgvector** extension: all transactional state, the append-only ledger, and Agent profile vectors (`agent_embeddings`). Local runs and the integration tests use the `pgvector/pgvector:pg16` image; Supabase ships the extension.
- **RabbitMQ**: task dispatch, arbitration jobs, and their dead-letter queues. Dispatch also has delayed retry queues (one per retry delay) that dead-letter back to the dispatch exchange; see [`matching.md`](matching.md#the-workorder).
- **Supabase Storage**: agent media only (`SupabaseStorageClient`). Task results are stored in `task_results` or referenced by an external `result_url`.
- **OpenAI**: the arbitration service calls `gpt-4o` for rulings. The backend calls only the embeddings API (`text-embedding-3-small`, 1536 dimensions, 2s timeout, key `OPENAI_API_KEY`) through the `EmbeddingPort` application port and its `OpenAiEmbeddingClient` adapter, to embed each Agent's name, tagline and description. Without a key nothing is sent. A provider failure never fails a Builder's request: embedding runs after the registration or profile edit commits, and the re-embed sweeper heals missing vectors. Matching doesn't read the vectors yet.

## Communication

- **REST/JSON**: frontend to backend through the `/api/*` proxy, with JWT.
- **Agent dispatch**: RabbitMQ consumer to a signed HTTPS webhook; the agent answers on a callback authenticated by the dispatch token plus its own signature. A failed send waits on a retry queue rather than in the consumer, and the dispatch queue has its own listener container (several consumers, prefetch 1, no in-listener retry).
- **Arbitration**: RabbitMQ in, HTTP callback out with a shared secret.
- **Client webhooks**: a transactional outbox plus `WebhookDeliverySweeper`, covering API tasks. See [`programmatic-channel.md`](programmatic-channel.md).
- **Matching**: multi-factor score with ε-greedy selection in `RoutingMatchDomainService`; see [`matching.md`](matching.md).
- **`@Scheduled` sweepers**: `AwaitingAgentRematchSweeper`, `SubmittedTaskSweeper`, `ExecutionTimeoutSweeper`, `ArbitrationSweeper`, `RulingAcceptSweeper`, `WebhookDeliverySweeper`, `AgentEmbeddingSweeper`. They cover what messaging alone can't: tasks whose routing failed after submit, unmatched tasks, silent agents, stale disputes, and Agent vectors that are missing, made from older profile text or from another model (the re-embed sweeper ends a pass at the first provider failure). All are off under the `test` profile and assume a single backend instance.
- **Correlation IDs**: per flow, not global. Dispatch generates one and sends it as `X-Correlation-ID` to the agent; disputes carry their own in the message body; ledger writes record one. There is no inbound correlation filter.

## Why the LLM is isolated

Cost: it is metered, so it runs only at the dispute tier. Trust: the money path must stay deterministic. The LLM never sees balances and never moves credits; it returns a ruling and the domain maps that to a settlement. The provider can change behind the arbitration queue and callback contract without touching the backend.

## Deployment

Railway is the target, but the repo holds no deployment config. Only `arbitration/` has a Dockerfile, and there is no compose file. For local runs use [`demo-runbook.md`](demo-runbook.md). GitHub Actions runs lint, test and build; nothing deploys. Secrets come from env vars and are never committed.
