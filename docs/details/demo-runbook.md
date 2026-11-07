# Demo runbook: full local stack

How to stand up HireAI locally and run the end-to-end demo. Commands are for Git Bash from the repo root. Integration tests use Testcontainers and don't need this stack.

| Component | Port | How |
|---|---|---|
| Postgres | 5432 | Docker `postgres:16-alpine` |
| RabbitMQ | 5672, 15672 | Docker `rabbitmq:3.13-management-alpine` |
| Backend | 8080 | `spring-boot:run` on `hireai-main` |
| Arbitrator | 8000 | `uvicorn` in `arbitration/` |
| Frontend | 3000 | `npm run dev` (proxies `/api/*` to `:8080`) |
| Stub agent | 9000 | `uvicorn` in `demo-agent/` |
| HTTPS tunnel to the stub | n/a | `cloudflared` (registration requires `https://`) |

## Start the stack

**1. Infrastructure.** `--user 100:101` avoids a RabbitMQ startup crash (see Troubleshooting).

```bash
docker run -d --name hireai-pg -e POSTGRES_DB=hireai -e POSTGRES_USER=hireai -e POSTGRES_PASSWORD=hireai -p 5432:5432 postgres:16-alpine
```

```bash
docker run -d --name hireai-rabbit --user 100:101 -p 5672:5672 -p 15672:15672 rabbitmq:3.13-management-alpine
```

**2. Backend.** Install once, then boot the `hireai-main` module directly. Booting the reactor parent fails with `Unable to find a suitable main class`. Env vars must be exported: `backend/.env` isn't loaded from the module directory, and it points at Supabase anyway. Flyway migrates and seeds on first boot.

```bash
mvn -f backend/pom.xml -pl hireai-main -am -DskipTests install
```

```bash
export DB_URL=jdbc:postgresql://localhost:5432/hireai DB_USERNAME=hireai DB_PASSWORD=hireai
export ARBITRATION_CALLBACK_SECRET="$(grep '^ARBITRATION_CALLBACK_SECRET=' arbitration/.env | cut -d= -f2- | tr -d '\r')"
mkdir -p "$HOME/jt" && export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$(cygpath -w "$HOME/jt")"   # Windows only, see Troubleshooting
mvn -f backend/hireai-main/pom.xml spring-boot:run
```

The secret must match the arbitrator's, or its rulings are rejected with 401. Image upload additionally needs `SUPABASE_URL` and `SUPABASE_SERVICE_KEY` (see below).

**3. Arbitrator.** Start it **after** the backend, which declares the queue. It needs `arbitration/.env` (see [its README](../../arbitration/README.md)).

```bash
cd arbitration && uv run uvicorn app.main:app
```

`/health` stays OK even if the consumer failed, so confirm `task.dispute.requested` shows 1 consumer at http://localhost:15672 (`guest`/`guest`). Without an arbitrator, disputes escalate to the admin once `hireai.arbitration.stale-after` passes.

**4. Stub agent and tunnel.** One-time: `python -m pip install -r demo-agent/requirements.txt`. The tunnel is a temporary **public** address forwarding to the stub; note the printed `https://<random>.trycloudflare.com`.

```bash
python -m uvicorn app:app --app-dir demo-agent --host 127.0.0.1 --port 9000
```

```bash
./cloudflared.exe tunnel --url http://localhost:9000
```

`cloudflared.exe` is git-ignored. To fetch it: `curl -L -o cloudflared.exe https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe`

**5. Frontend**, then open http://localhost:3000.

```bash
npm --prefix frontend run dev
```

## Seed logins

Password `DemoPass123!`: `client@hireai.local` (CLIENT, wallet pre-funded), `builder@hireai.local` (BUILDER), `admin@hireai.local` (ADMIN).

## Demo script (browser)

1. **Builder: register.** Register agent: category `summarisation`, webhook `https://<tunnel-host>/run`, price 10. Leave the output spec at format `JSON` with no schema, since the stub returns `{"summary": ...}` and a `FILE` format or a stricter schema fails validation.
2. **Builder: publish.** **Activate** the agent, open it to manage, then on **Storefront** tick **Listed on marketplace** and save. An unlisted agent never appears in the shortlist.
3. **Client: book.** **Submit open task**: enter a title, paste `demo-agent/demo-article.txt` as the description, choose `summarisation` and a budget of at least 10. **Find agents**, pick one, and book. Nothing is charged until the booking, and escrow is the **agent's price (10)**, not the typed budget.
4. **Watch** the task page poll `QUEUED`, `EXECUTING`, then `PENDING_REVIEW` with the result. Validation runs inside the callback, so `RESULT_RECEIVED` isn't a state you wait on.
5. **Client: review.**
   - **Accept:** the builder receives 85% (8.50), and the client is offered a rating.
   - **Reject** with reason A, B or C: opens a dispute, and no money moves yet. The arbitrator proposes a ruling and escrow holds until the client accepts or appeals, or the proposal auto-accepts after `hireai.arbitration.ruling-accept-after`. `FULFILLED` pays 85/15, `PARTIALLY_FULFILLED` splits, and `NOT_FULFILLED` refunds.
   - Reason D (`D_CHANGED_MIND`) is API-only: no dispute, and the client is charged in full at 85/15.
6. **Admin:** an appealed or escalated dispute appears at `/admin/disputes` for a final ruling.

The stub ignores task content and returns a fixed summary of that article. A real agent currently receives no task content at all. See [Known gaps](build-status.md#known-gaps).

## MCP / programmatic demo

The infrastructure is identical; the client is `mcp/` rather than the browser. The flow is in [`programmatic-channel.md`](programmatic-channel.md).

1. Mint a key as the client at `/client/keys` and copy the raw `hk_live_…` once.
2. Register, activate and list an agent as above.
3. Run the server under MCP Inspector, or wire up Claude Desktop per [`mcp/README.md`](../../mcp/README.md):

   ```bash
   HIREAI_API_BASE_URL=http://localhost:8080 HIREAI_API_KEY=hk_live_… npx @modelcontextprotocol/inspector uv --directory mcp run hireai-mcp
   ```

4. The task goes `QUEUED`, `EXECUTING`, `RESOLVED` and auto-settles with no accept or reject step.

## Option: Supabase instead of local Postgres

Only Postgres moves; RabbitMQ stays local. Supabase data persists, so demo rows accumulate. Skip `hireai-pg` and export the session-pooler values (**port 5432, not 6543**, because Flyway's advisory locks and DDL break under transaction pooling):

```bash
export DB_URL='jdbc:postgresql://aws-1-<region>.pooler.supabase.com:5432/postgres?sslmode=require' DB_USERNAME='postgres.<project-ref>' DB_PASSWORD='<db-password>'
```

The backend connects as a privileged role and never uses the Data API. Because money tables live in `public`, disable the project's Data API or enable deny-all RLS on them.

## Supabase Storage (agent images)

Logo, cover and gallery upload needs `SUPABASE_URL` and `SUPABASE_SERVICE_KEY` exported (service-role key, never committed); uploads return 500 without them, and nothing else is affected. Create the bucket once:

```bash
curl -X POST "$SUPABASE_URL/storage/v1/bucket" -H "Authorization: Bearer $SUPABASE_SERVICE_KEY" -H "Content-Type: application/json" -d '{"id":"agent-media","name":"agent-media","public":true}'
```

## Teardown

```bash
docker rm -f hireai-pg hireai-rabbit
```

Then stop the uvicorn, cloudflared, mvn and npm processes.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Docker Desktop: `running wsl-bootstrap: exit status 1` | Stuck `docker_data.vhdx` mount | Quit Docker, `wsl --shutdown`, relaunch |
| Docker backend crashes on `userAnalyticsOtlpHttp.sock: The file cannot be accessed by the system` | Stale socket after a forced quit; it can't be deleted | Quit Docker, rename `%LOCALAPPDATA%\Docker\run` aside, create an empty `run`, relaunch |
| `docker info` succeeds but containers fail | It exits 0 with the daemon down | Poll `docker ps` with a timeout |
| RabbitMQ exits: `.erlang.cookie: eacces` | Cookie created root-owned | Recreate with `--user 100:101` |
| JVM: `Unable to establish loopback connection`, `Invalid argument: connect` | AF_UNIX sockets fail under `AppData\Local\Temp` on this Windows build | `-Djdk.net.unixdomain.tmpdir=<dir under the user profile>` via `JAVA_TOOL_OPTIONS`; needed for `mvn test` too |
| `uv run pytest` hangs in `arbitration/` | `test_health_ok` reads the real `.env` and starts a live consumer | `OPENAI_API_KEY= ARBITRATION_CALLBACK_SECRET= uv run pytest` |
| Registering a webhook fails: `Webhook URL must be HTTPS` | Unconditional at registration (Inv #6) | Use the tunnel URL |
| Every dispatch fails after restarting the tunnel | Quick tunnels get a new hostname, and no endpoint edits a webhook. Retries dead-letter, and the task ends `FAILED` and refunded | Repoint with the SQL below |

```sql
UPDATE agent_versions SET webhook_url = 'https://<new-tunnel-host>/run' WHERE webhook_url LIKE '%trycloudflare.com%';
```
