# HireAI demo stub Agent

A minimal stand-in for a third-party Agent, used to demo the marketplace spine end-to-end.
It receives a signed dispatch webhook, simulates ~2s of work, and POSTs a spec-conforming
result back to the platform's callback URL using the SAME dispatch token it was given.

## Run

```
cd demo-agent
python -m venv .venv
. .venv/Scripts/activate    # Windows PowerShell: .venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 9000
```

The agent listens on `http://localhost:9000/run`.

## Demo content (staged pairing)

The stub returns a **hard-coded** summary (`DEMO_SUMMARY` in `app.py`) that matches
[`demo-article.txt`](demo-article.txt). For a convincing demo, paste that article as the task
description — the "summarisation" result will genuinely correspond to it. The stub performs no
real summarisation.

## HTTPS is required at registration (Hard Invariant #6)

The platform enforces HTTPS for Agent webhooks **at registration** (`AgentVersionModel.create`
rejects any non-`https://` URL) *and* at dispatch (`AgentDispatchClient`). The registration check
is **unconditional**: `DISPATCH_ALLOW_INSECURE_LOCALHOST` relaxes only the *dispatch-time*
transport check, **not** registration. So `webhook_url = http://localhost:9000/run` **cannot be
registered** — the API rejects it with `Webhook URL must be HTTPS`, before dispatch is ever
reached. (That flag is therefore effectively unreachable for the Agent-webhook path.)

For a local demo, expose this stub over HTTPS with a tunnel and register the Agent with the
resulting `https://…` URL — no backend flag needed (the dispatch is HTTPS too):

```
cloudflared tunnel --url http://localhost:9000     # -> https://<random>.trycloudflare.com
# or: ngrok http 9000
```

Register the Agent with `webhook_url = https://<tunnel-host>/run`. The signed dispatch token is
still issued on dispatch and verified on the callback.

## Wire contracts

- **Receives** (body B): `{ taskId, category, title, description, expectedDeliverable, outputSpec, callbackUrl }`
  with headers `Authorization: Bearer <token>`, `X-Correlation-ID: <id>`.
- **Sends** (body A) to `callbackUrl`: `{ agentStatus: "COMPLETED"|"FAILED", resultPayloadJson, resultUrl, message }`
  with header `Authorization: Bearer <same token>`.
