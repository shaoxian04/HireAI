# HireAI demo stub Agent

A minimal stand-in for a third-party Agent. It receives a signed dispatch webhook, simulates about 2s of work, and POSTs a fixed result to the platform's callback using the same dispatch token. Full stack: [`demo-runbook.md`](../docs/details/demo-runbook.md).

## Run

```
cd demo-agent
python -m venv .venv
. .venv/Scripts/activate    # Windows PowerShell: .venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 9000
```

The agent listens on `http://localhost:9000/run`. With it running, `python demo-agent/smoke_test.py` from the repo root checks the callback round trip.

## What it returns

It ignores the task apart from `taskId` and `callbackUrl`, and always sends a **hard-coded** summary (`DEMO_SUMMARY` in `app.py`) of [`demo-article.txt`](demo-article.txt) as `{"summary": ...}` with `resultUrl: null`. Paste that article as the task description so the result reads as genuine.

It therefore passes `TEXT`, or `JSON` with no conflicting schema. It fails `FILE` format and any schema requiring other fields, ending in `SPEC_VIOLATION`.

Because it ignores the task, a demo with this stub cannot show whether real task content reaches an agent. See [Known gaps](../docs/details/build-status.md#known-gaps).

## HTTPS is required (Hard Invariant #6)

Registration rejects any non-`https://` webhook with `Webhook URL must be HTTPS`, unconditionally (`AgentVersionModel`). `DISPATCH_ALLOW_INSECURE_LOCALHOST` relaxes only the dispatch-time check in `AgentDispatchClient`, so `http://localhost:9000/run` can't be registered at all.

Expose the stub over a tunnel and register `https://<tunnel-host>/run`:

```
cloudflared tunnel --url http://localhost:9000     # -> https://<random>.trycloudflare.com
# or: ngrok http 9000
```

## Wire contracts

- **Receives:** `{ taskId, category, title, description, expectedDeliverable, outputSpec, callbackUrl }` with headers `Authorization: Bearer <token>` and `X-Correlation-ID: <id>`.
- **Sends** to `callbackUrl`: `{ agentStatus: "COMPLETED"|"FAILED", resultPayloadJson, resultUrl, message }` with `Authorization: Bearer <same token>`.
