# HireAI Arbitration Worker

A standalone Python microservice that consumes dispute requests from RabbitMQ,
runs a LangGraph arbitration graph (backed by OpenAI), and posts a structured
ruling back to the Spring backend via a shared-secret callback.

## Architecture

### Inbound queue

- **Queue:** `task.dispute.requested`
- **Topology owner:** The Java backend declares the exchange and queue; this
  service declares them *passively* (consume-only, no declare). The worker must
  start **after** the backend has already declared the topology.

### Outbound callback

`POST {BACKEND_BASE_URL}/api/internal/disputes/{disputeId}/ruling`

Headers:
- `X-Arbitration-Secret: <ARBITRATION_CALLBACK_SECRET>`
- `Content-Type: application/json`

Body (JSON):
```json
{
  "ruling": "REFUND_FULL | RELEASE_PAYMENT | SPLIT",
  "reason": "...",
  "confidence": 0.0-1.0
}
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `OPENAI_API_KEY` | *(required)* | OpenAI API key |
| `OPENAI_MODEL` | `gpt-4o` | Model for arbitration |
| `RABBITMQ_URL` | `amqp://guest:guest@localhost:5672/` | RabbitMQ connection URL |
| `ARBITRATION_CALLBACK_SECRET` | *(required)* | Shared secret sent to backend callback |
| `BACKEND_BASE_URL` | `http://localhost:8080` | Backend base URL |
| `DISPUTE_QUEUE` | `task.dispute.requested` | Queue to consume from |
| `FETCH_MAX_BYTES` | `5000000` | Max bytes for fetching task results |
| `FETCH_TIMEOUT_SECONDS` | `10.0` | HTTP fetch timeout (seconds) |
| `CALLBACK_TIMEOUT_SECONDS` | `10.0` | Callback HTTP timeout (seconds) |

Copy `.env.example` → `.env` and fill in the required values before running locally.

## Development

### Setup

```bash
uv sync --dev
```

### Run tests

```bash
uv run pytest
```

### Lint

```bash
uv run ruff check .
```

### Run locally

```bash
uv run uvicorn app.main:app --reload
```

The `/health` endpoint will be available at `http://localhost:8000/health`.
