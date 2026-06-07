"""Minimal stub Agent for the HireAI marketplace-spine demo.

Receives a dispatch webhook (wire contract B), echoes the SAME Bearer token, waits
briefly to simulate work, then POSTs a spec-conforming result (wire contract A) to the
platform's callback URL. This is a stand-in for a real third-party Agent.
"""
import asyncio
import json

import httpx
from fastapi import FastAPI, Header, HTTPException, Request

app = FastAPI(title="HireAI demo stub agent")

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


@app.post("/run")
async def run(request: Request, authorization: str = Header(default="")):
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing dispatch token")
    token = authorization.removeprefix("Bearer ")
    body = await request.json()
    task_id = body["taskId"]
    callback_url = body["callbackUrl"]

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
