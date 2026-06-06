# Demo runbook — full local end-to-end stack

How to stand up the HireAI marketplace spine locally and run the Client→Builder happy-path in a
browser (submit → route → dispatch → execute → result). Verified live on 2026-06-06.

## What runs

| Component | Port | How |
|---|---|---|
| Postgres (local, not Supabase) | 5432 | Docker `postgres:16-alpine` |
| RabbitMQ | 5672 / 15672 | Docker `rabbitmq:3.13-management-alpine` |
| Stub agent (FastAPI) | 9000 | `uvicorn` (the `demo-agent/`) |
| HTTPS tunnel → stub | — | `cloudflared` (registration requires `https://`) |
| Backend (Spring Boot) | 8080 | `mvn spring-boot:run` against the local DB/broker |
| Frontend (Next.js) | 3000 | `npm run dev` (proxies `/api/*` → `:8080`) |

## Steps

```bash
# 1. Infrastructure (local Postgres + RabbitMQ — keeps Supabase untouched)
docker run -d --name hireai-pg     -e POSTGRES_DB=hireai -e POSTGRES_USER=hireai -e POSTGRES_PASSWORD=hireai -p 5432:5432 postgres:16-alpine
docker run -d --name hireai-rabbit -p 5672:5672 -p 15672:15672 rabbitmq:3.13-management-alpine

# 2. Stub agent  (one-time:  python -m pip install -r demo-agent/requirements.txt)
python -m uvicorn app:app --app-dir demo-agent --host 127.0.0.1 --port 9000

# 3. HTTPS tunnel to the stub — registration enforces https:// (see demo-agent/README.md).
#    Note the printed https://<random>.trycloudflare.com URL.
cloudflared tunnel --url http://localhost:9000
#    (get cloudflared:  curl -L -o cloudflared.exe https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe)

# 4. Backend against the LOCAL DB + broker. (allow-insecure-localhost is NOT needed when the
#    webhook is the https tunnel; included only if you dispatch to an http://localhost target.)
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.arguments='--spring.datasource.url=jdbc:postgresql://localhost:5432/hireai --spring.datasource.username=hireai --spring.datasource.password=hireai --spring.rabbitmq.host=localhost --spring.rabbitmq.port=5672 --spring.rabbitmq.username=guest --spring.rabbitmq.password=guest'

# 5. Frontend  → open http://localhost:3000
npm --prefix frontend run dev
```

Flyway applies `V1`–`V5` on first backend boot (incl. the seeded demo users).

## Demo script (in the browser)

Seed logins (from Flyway `V5`), password `DemoPass123!`:
- `builder@hireai.local` (BUILDER)
- `client@hireai.local` (CLIENT, wallet pre-funded 1000)

1. **Builder:** log in → **Register agent** — name, category `summarisation`, **webhook =
   `https://<tunnel-host>/run`**, price 10 → **Activate** (→ `ACTIVE`).
2. **Client:** log in → optionally top up → **Submit task** with category `summarisation`,
   budget ≥ 10 (e.g. 50). Submitting freezes the budget in escrow.
3. Watch the task detail page poll `SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED`, then render
   the agent's `COMPLETED` result. The wallet shows the budget moved into escrow.

## Teardown

```bash
docker rm -f hireai-pg hireai-rabbit
# stop the uvicorn / cloudflared / mvn / npm processes (Ctrl-C, or kill the PIDs on 9000/8080/3000)
```

## Gotchas

- **Registration requires `https://`** (unconditional, invariant #6). A pure `http://localhost`
  webhook is rejected at registration regardless of `DISPATCH_ALLOW_INSECURE_LOCALHOST` — hence the
  tunnel. See `demo-agent/README.md`.
- The tunnel URL is a **temporary public** address forwarding to your local stub (token-gated).
- Integration tests use Testcontainers, so they don't need this stack — this is only for a live demo.
