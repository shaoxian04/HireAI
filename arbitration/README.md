# HireAI Arbitration Worker

A Python service that consumes dispute requests from RabbitMQ, runs a LangGraph arbitration graph on OpenAI, and posts a structured ruling back to the Spring backend.

## How it works

**Inbound.** Consumes `task.dispute.requested`. The Java backend owns the topology; this service declares only the queue, passively, so it must start **after** the backend. If the queue is missing, the consumer task dies while `/health` keeps returning OK.

**Graph.** `gather_evidence → deliberate → classify → critique` (`app/graph/build.py`).

**Outbound.** `POST {BACKEND_BASE_URL}/api/arbitration-callbacks/{disputeId}/ruling` with `Authorization: Bearer <ARBITRATION_CALLBACK_SECRET>` and:

```json
{ "category": "FULFILLED | PARTIALLY_FULFILLED | NOT_FULFILLED", "rationale": "..." }
```

The arbitrator returns only a category and rationale. All credit movement is computed by the Java domain layer from the category; it never touches money (Invariant #3).

**Failure handling** (`app/consumer.py`). Up to 3 attempts with 1s and 2s backoff; a 4xx from the callback is not retried. The message is acked only after a 2xx. Persistent failure nacks to `task.dispute.requested.dlq`, and Java then escalates the dispute to the admin.

## Configuration

Read from the environment or a local `.env` in this directory (git-ignored). `OPENAI_API_KEY` and `ARBITRATION_CALLBACK_SECRET` are needed for the service to do anything: if either is empty the app still starts, logs a warning and **runs no consumer**, and `/health` stays OK.

| Variable | Default | Description |
|---|---|---|
| `OPENAI_API_KEY` | empty | OpenAI API key |
| `ARBITRATION_CALLBACK_SECRET` | empty | Must match the backend's value |
| `OPENAI_MODEL` | `gpt-4o` | Model for arbitration |
| `RABBITMQ_URL` | `amqp://guest:guest@localhost:5672/` | RabbitMQ connection URL |
| `BACKEND_BASE_URL` | `http://localhost:8080` | Backend base URL |
| `DISPUTE_QUEUE` | `task.dispute.requested` | Queue to consume from |
| `FETCH_MAX_BYTES` | `5000000` | Max bytes when fetching task results |
| `FETCH_TIMEOUT_SECONDS` | `10.0` | Fetch timeout |
| `CALLBACK_TIMEOUT_SECONDS` | `10.0` | Callback timeout |

## Development

```bash
uv sync --dev
uv run uvicorn app.main:app --reload    # /health at http://localhost:8000/health
uv run ruff check .
```

Run tests with the keys blanked:

```bash
OPENAI_API_KEY= ARBITRATION_CALLBACK_SECRET= uv run pytest
```

Plain `uv run pytest` hangs if `.env` holds real keys: `test_health_ok` assumes empty config, so it starts a live consumer against whatever broker `RABBITMQ_URL` points at. CI has no keys and doesn't hit this.
