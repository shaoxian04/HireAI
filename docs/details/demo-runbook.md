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

_Postgres can instead be a hosted Supabase project — see **Option B** below; only the DB moves,
RabbitMQ still runs locally._

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

Flyway applies `V1`–`V9` on first backend boot (incl. the seeded demo users).

## Supabase Storage (agent images)

Builder image upload (logo/cover/gallery) requires two extra env vars in `backend/.env` (**never commit**):

```
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_SERVICE_KEY=<service_role key — dashboard → Settings → API → service_role>
```

One-time bucket creation (run once after the project is created):

```bash
curl -X POST "$SUPABASE_URL/storage/v1/bucket" \
  -H "Authorization: Bearer $SUPABASE_SERVICE_KEY" -H "Content-Type: application/json" \
  -d '{"id":"agent-media","name":"agent-media","public":true}'
```

Image uploads will 500 until this is configured; the rest of the demo (task flow, catalogue read, etc.) is unaffected.

## Option B — Supabase (hosted Postgres) instead of local Postgres

The datasource is env-driven (`application.yml` reads `${DB_URL}` / `${DB_USERNAME}` /
`${DB_PASSWORD}`), so the backend can point at a Supabase project instead of the local container.
**Only Postgres moves — RabbitMQ still runs locally** (step 1's `hireai-rabbit`). Skip the
`hireai-pg` container and the datasource args on `mvn`.

1. Supabase dashboard → **Connect** → **Session pooler**. Use the **session pooler (port 5432)**,
   NOT the transaction pooler (6543) — Flyway's advisory locks + DDL transactions break under
   transaction pooling. Keep `?sslmode=require`. Username is `postgres.<project-ref>`.
2. Put the values in a git-ignored `backend/.env` (loaded via `spring.config.import` in
   `application.yml`), or export them as `$env:DB_URL` etc. before running mvn:
   ```
   DB_URL=jdbc:postgresql://aws-1-<region>.pooler.supabase.com:5432/postgres?sslmode=require
   DB_USERNAME=postgres.<project-ref>
   DB_PASSWORD=<db-password>
   ```
3. Run the backend WITHOUT the datasource args: `mvn -f backend/pom.xml spring-boot:run`.
   Flyway applies `V1`–`V9` into the project's `public` schema on first boot (incl. demo users).

Migrations are plain DDL + triggers (no extensions/roles), so they run unchanged on Supabase. To
confirm a boot, watch the log for `Database: jdbc:postgresql://…supabase.com…` followed by Flyway's
`Successfully applied N migrations … now at version v5`. If `flyway_schema_history` stops short of
V5 (an interrupted earlier boot), the next boot resumes the remaining migrations. Watch for `now at version v9`.

**Security note:** the money tables (`wallets`, `ledger_entries`) live in the `public` schema,
which Supabase can expose over its Data API to the `anon`/`authenticated` roles. The Spring backend
connects as the privileged `postgres` role (bypasses RLS) and never uses the Data API — so either
**disable the project's Data API** or enable RLS (deny-all is fine) on the public tables.

**Persistence:** unlike the throwaway Docker DB, Supabase data persists across restarts; demo and
seed rows accumulate there.

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
4. → **Accept** the result: the builder's wallet receives 85% of the budget (log in as the builder
   to see the payout in wallet/stats), or **Reject** for a full refund to the client's wallet.

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
- **Quick tunnels get a new hostname on every restart** — agents registered against an old tunnel
  keep their stale webhook and every dispatch to them FAILs with `UnknownHostException` (retries →
  DLQ, escrow stays frozen). There is no builder endpoint to edit a webhook, so after starting a
  fresh tunnel re-point any stale rows directly:

  ```sql
  UPDATE agent_versions
  SET webhook_url = 'https://<new-tunnel-host>/run'
  WHERE webhook_url LIKE '%trycloudflare.com%';
  ```
- Integration tests use Testcontainers, so they don't need this stack — this is only for a live demo.
