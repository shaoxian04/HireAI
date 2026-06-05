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

## HTTPS note (Hard Invariant #6)

The platform's `AgentDispatchClient` enforces HTTPS for webhook URLs. Two ways to demo:

1. **Dev-profile localhost exception (simplest).** Set
   `DISPATCH_ALLOW_INSECURE_LOCALHOST=true` on the backend and register the Agent with
   `webhook_url = http://localhost:9000/run`. The signed-token check still applies; only the
   transport check is relaxed for `localhost`.
2. **HTTPS tunnel (faithful).** Expose the stub over HTTPS with a tunnel, e.g.
   `cloudflared tunnel --url http://localhost:9000` or `ngrok http 9000`, and register the
   Agent with the resulting `https://...` URL. No backend flag needed.

## Wire contracts

- **Receives** (body B): `{ taskId, category, title, description, expectedDeliverable, outputSpec, callbackUrl }`
  with headers `Authorization: Bearer <token>`, `X-Correlation-ID: <id>`.
- **Sends** (body A) to `callbackUrl`: `{ agentStatus: "COMPLETED"|"FAILED", resultPayloadJson, resultUrl, message }`
  with header `Authorization: Bearer <same token>`.
