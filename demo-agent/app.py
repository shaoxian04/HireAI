"""Minimal stub Agent for the HireAI marketplace-spine demo.

Receives a dispatch webhook (wire contract B), echoes the SAME Bearer token, waits
briefly to simulate work, then POSTs a spec-conforming result (wire contract A) to the
platform's callback URL. This is a stand-in for a real third-party Agent.

When HIREAI_AGENT_SIGNING_SECRET is set (the AgentSigningSecret shown once at registration
or rotation), every WorkOrder must carry a valid X-HireAI-Signature or it is rejected with 401.
"""
import asyncio
import hashlib
import hmac
import json
import logging
import os
import time

import httpx
from fastapi import FastAPI, Header, HTTPException, Request

app = FastAPI(title="HireAI demo stub agent")
# uvicorn configures its own loggers only; give ours a handler so the lines reach the console.
logging.basicConfig(level=logging.INFO, format="%(levelname)s:     %(message)s")
log = logging.getLogger("demo-agent")

# Hard-coded demo output: the summary of demo-article.txt (paste that article as the task
# description during the demo). The stub does no real summarisation - the pairing is staged.
DEMO_SUMMARY = (
    "A 2022 UK trial of the four-day work week across 61 companies proved durable: 54 firms "
    "kept the shorter week and 31 made it permanent. Revenue rose slightly (avg +1.4%), staff "
    "turnover fell 57%, sick days dropped by two-thirds, and 71% of employees reported less "
    "burnout. Output was sustained by cutting meetings and approval overhead rather than by "
    "working faster - though shift-based sectors like healthcare and logistics found the model "
    "much harder to adopt."
)


SIGNATURE_HEADER = "X-HireAI-Signature"
SECRET_ENV = "HIREAI_AGENT_SIGNING_SECRET"
# Reject a signature whose timestamp is further than this from our clock (replay window).
TOLERANCE_SECONDS = 300


def verify_signature(secret: str, header: str | None, body: bytes, now: float | None = None) -> bool:
    """Checks t=<ts>,v1=<hex HMAC-SHA256(secret, "<ts>.<body>")> over the exact raw body bytes."""
    if not header:
        return False
    parts = dict(p.split("=", 1) for p in header.split(",") if "=" in p)
    timestamp, received = parts.get("t", ""), parts.get("v1", "")
    if not timestamp.isdigit() or not received:
        return False
    current = time.time() if now is None else now
    if abs(current - int(timestamp)) > TOLERANCE_SECONDS:
        return False
    expected = hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, received)


@app.post("/run")
async def run(request: Request, authorization: str = Header(default="")):
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing dispatch token")
    token = authorization.removeprefix("Bearer ")
    raw = await request.body()
    secret = os.environ.get(SECRET_ENV)
    if secret and not verify_signature(secret, request.headers.get(SIGNATURE_HEADER), raw):
        log.warning("Rejected a WorkOrder with a missing or invalid %s", SIGNATURE_HEADER)
        raise HTTPException(status_code=401, detail="Invalid WorkOrder signature")
    body = json.loads(raw)
    task_id = body["taskId"]
    callback_url = body["callbackUrl"]
    # Log the task content so a live run visibly proves the WorkOrder carries the client's
    # request. The stub still returns its staged output regardless of what was asked.
    log.info(
        "WorkOrder %s (version %s, category %s, deadline %s): title=%r description=%r",
        task_id, body.get("agentVersionId"), body.get("category"), body.get("executionDeadline"),
        body.get("title"), body.get("description"),
    )

    # Process asynchronously so the dispatch POST returns immediately (202-style).
    asyncio.create_task(_complete(task_id, callback_url, token))
    return {"accepted": True, "taskId": task_id}


async def _complete(task_id: str, callback_url: str, token: str):
    await asyncio.sleep(2)  # simulate execution
    result_payload = json.dumps({"summary": DEMO_SUMMARY})
    callback_body = {
        "agentStatus": "COMPLETED",
        "resultPayloadJson": result_payload,
        "resultUrl": None,
        "message": "stub agent completed",
    }
    async with httpx.AsyncClient(timeout=10) as client:
        await client.post(
            callback_url,
            headers={"Authorization": f"Bearer {token}"},
            json=callback_body,
        )
