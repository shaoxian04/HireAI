"""Minimal stub Agent for the HireAI marketplace-spine demo.

Receives a dispatch webhook (wire contract B), echoes the SAME Bearer token, waits
briefly to simulate work, then POSTs a spec-conforming result (wire contract A) to the
platform's callback URL. This is a stand-in for a real third-party Agent.

When HIREAI_AGENT_SIGNING_SECRET is set (the AgentSigningSecret shown once at registration
or rotation), every WorkOrder must carry a valid X-HireAI-Signature or it is rejected with 401,
and the result callback is signed with the same secret. The platform rejects (401) any callback
not signed with the Agent's current secret, so a live demo needs the secret set.
"""
import asyncio
import hashlib
import hmac
import json
import logging
import os
import re
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
# FILE tasks: the https link the stub delivers. A "#deliver:<url>" tag in the task description wins,
# so one running stub can deliver a passing file and a wrong-type one in the same demo.
FILE_URL_ENV = "HIREAI_AGENT_FILE_URL"
_DELIVER_TAG = re.compile(r"#deliver:(\S+)")


# A rework WorkOrder (it carries "rework": the checks the last result failed) delivers the
# "#rework:<url>" link instead, so a demo can fail a check once and then pass it.
_REWORK_TAG = re.compile(r"#rework:(\S+)")
# "#anomaly:<CHECK>" self-reports an anomaly about that Check Library entry with every result.
_ANOMALY_TAG = re.compile(r"#anomaly:([A-Z_]+)")


def file_url_for(work_order: dict) -> str | None:
    """The resultUrl for a FILE WorkOrder (tag in the description, else the env), else None."""
    spec = work_order.get("outputSpec") or {}
    if spec.get("format") != "FILE":
        return None
    description = work_order.get("description") or ""
    if work_order.get("rework"):
        reworked = _REWORK_TAG.search(description)
        if reworked:
            return reworked.group(1)
    tagged = _DELIVER_TAG.search(description)
    return tagged.group(1) if tagged else os.environ.get(FILE_URL_ENV)


def anomalies_for(work_order: dict) -> list[dict]:
    """The anomalies to self-report with the result: one per "#anomaly:<CHECK>" tag."""
    return [{"check": check, "description": "The demo agent flags this: the brief makes it hard to meet as written."}
            for check in _ANOMALY_TAG.findall(work_order.get("description") or "")]
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


def sign_body(secret: str, body: bytes, now: float | None = None) -> str:
    """X-HireAI-Signature value for an outgoing callback: the scheme the platform verifies."""
    timestamp = str(int(time.time() if now is None else now))
    digest = hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
    return f"t={timestamp},v1={digest}"


REJECTION_EVENT = "task.rejected"
# A buyer's reviewer rejected one Item of a multi-Item Task (sampling): same choices, per Item.
ITEM_REJECTION_EVENT = "task.item_rejected"
REJECTION_ENV = "HIREAI_AGENT_REJECTION_RESPONSE"
REJECTION_ACTIONS = ("REVISE", "CONCEDE", "STAND_BY", "SILENT")
REVISED_SUMMARY = DEMO_SUMMARY + " (Revised: figures re-checked against the source article.)"


def choose_rejection_response(notice: dict) -> str:
    """How the stub answers a rejection notice, so a demo can drive every branch.

    A tag in the client's rejection text wins (#revise, #concede, #standby, #silent); otherwise
    HIREAI_AGENT_REJECTION_RESPONSE (default STAND_BY). A revision the notice no longer allows
    falls back to standing by.
    """
    reason = (notice.get("reason") or "").lower()
    tags = {"#revise": "REVISE", "#concede": "CONCEDE", "#standby": "STAND_BY", "#silent": "SILENT"}
    action = next((a for tag, a in tags.items() if tag in reason), None)
    if action is None:
        configured = os.environ.get(REJECTION_ENV, "STAND_BY").upper()
        action = configured if configured in REJECTION_ACTIONS else "STAND_BY"
    if action == "REVISE" and not notice.get("revisionAllowed", False):
        action = "STAND_BY"
    return action


def rejection_response_body(notice: dict, action: str) -> dict:
    body = {"taskId": notice["taskId"], "action": action}
    if action == "STAND_BY":
        body["reason"] = ("The extracted fields match the source document as delivered." if notice.get("itemNo")
                          is not None else "The summary states only figures that appear in the source article.")
    if action == "REVISE":
        if notice.get("itemNo") is not None:
            body["resultPayloadJson"] = f"Revised item {notice['itemNo']}: {notice.get('itemInput', '')} (re-checked)"
        else:
            body["resultPayloadJson"] = json.dumps({"summary": REVISED_SUMMARY})
    return body


async def _respond_to_rejection(notice: dict, action: str, token: str, secret: str | None = None):
    await asyncio.sleep(2)  # simulate thinking it over
    raw = json.dumps(rejection_response_body(notice, action)).encode()
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    if secret:
        headers[SIGNATURE_HEADER] = sign_body(secret, raw)
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.post(notice["responseUrl"], headers=headers, content=raw)
    log.info("Rejection response %s for %s -> HTTP %s", action, notice["taskId"], response.status_code)


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
    if body.get("event") in (REJECTION_EVENT, ITEM_REJECTION_EVENT):
        action = choose_rejection_response(body)
        log.info("Rejection notice for %s item %s (round %s, %s): %r -> %s", body["taskId"], body.get("itemNo"),
                 body.get("round"), body.get("reasonCategory"), body.get("reason"), action)
        if action != "SILENT":
            asyncio.create_task(_respond_to_rejection(body, action, token, secret))
        return {"accepted": True, "taskId": body["taskId"]}
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
    if body.get("rework"):
        log.info("Rework %s/%s for %s; failed: %s", body["rework"].get("reworkNo"), body["rework"].get("maxReworks"),
                 task_id, [c.get("rule") for c in body["rework"].get("failedChecks", [])])
    items = body.get("items")
    extra = {"items": items} if items else {}  # only multi-Item WorkOrders carry items
    anomalies = anomalies_for(body)
    if anomalies:
        extra["anomalies"] = anomalies
    asyncio.create_task(_complete(task_id, callback_url, token, secret, file_url_for(body), **extra))
    return {"accepted": True, "taskId": task_id}


async def read_sources(items: list[dict], client: httpx.AsyncClient) -> dict[int, bytes | None]:
    """Downloads each Item's input document (an Engagement's SourceSnapshot) through its signed link,
    which expires at the execution deadline. Bytes whose SHA-256 is not the one the WorkOrder names, or a
    refused link, count as unreadable (None)."""
    documents: dict[int, bytes | None] = {}
    for item in items:
        source = item.get("source")
        if not source:
            continue
        try:
            response = await client.get(source["url"])
        except httpx.HTTPError as exc:
            log.warning("Item %s: document link failed: %s", item["itemNo"], exc)
            documents[item["itemNo"]] = None
            continue
        content = response.content if response.status_code == 200 else None
        if content is not None and hashlib.sha256(content).hexdigest() != source.get("sha256"):
            log.warning("Item %s: document hash does not match the WorkOrder", item["itemNo"])
            content = None
        log.info("Item %s: document %s -> HTTP %s", item["itemNo"], source.get("filename"), response.status_code)
        documents[item["itemNo"]] = content
    return documents


def item_results(items: list[dict], documents: dict[int, bytes | None] | None = None) -> list[dict]:
    """One result per Item of a multi-Item WorkOrder. Tags in an Item's input stage the outcome:
    #fail -> the Item is reported FAILED, #empty -> an empty result (fails a text Contract),
    #skip -> left out of the callback (the platform counts it as failed); otherwise a staged text.
    An Item whose document could not be read (``documents[itemNo] is None``) is reported FAILED."""
    documents = documents or {}
    results = []
    for item in items:
        text = item.get("input", "")
        if "#skip" in text:
            continue
        if "#fail" in text or (item["itemNo"] in documents and documents[item["itemNo"]] is None):
            results.append({"itemNo": item["itemNo"], "agentStatus": "FAILED"})
            continue
        payload = "" if "#empty" in text else f"Extracted from item {item['itemNo']}: {text}"
        document = documents.get(item["itemNo"])
        if document is not None and payload:
            payload += f" (read {len(document)} bytes, sha256 verified)"
        results.append({"itemNo": item["itemNo"], "agentStatus": "COMPLETED", "resultPayloadJson": payload})
    return results


async def _complete(task_id: str, callback_url: str, token: str, secret: str | None = None,
                    result_url: str | None = None, items: list[dict] | None = None,
                    anomalies: list[dict] | None = None):
    # simulate execution; HIREAI_AGENT_WORK_SECONDS lengthens it for a staged demo
    await asyncio.sleep(float(os.environ.get("HIREAI_AGENT_WORK_SECONDS", "2")))
    callback_body = {
        # Names the task this result answers; the platform rejects a signed body for another task.
        "taskId": task_id,
        "agentStatus": "COMPLETED",
        "resultUrl": result_url,
        "message": "stub agent completed",
    }
    if items:
        # A multi-Item Task: answer every Item under its itemNo, reading its document first if it has one.
        async with httpx.AsyncClient(timeout=30) as client:
            documents = await read_sources(items, client)
        callback_body["items"] = item_results(items, documents)
    else:
        callback_body["resultPayloadJson"] = json.dumps({"summary": DEMO_SUMMARY})
    if anomalies:
        callback_body["anomalies"] = anomalies
    # Serialise once and sign exactly these bytes: the platform verifies the raw body.
    raw = json.dumps(callback_body).encode()
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    if secret:
        headers[SIGNATURE_HEADER] = sign_body(secret, raw)
    else:
        log.warning("%s is not set: the platform will reject this unsigned callback (401)", SECRET_ENV)
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.post(callback_url, headers=headers, content=raw)
    log.info("Callback for %s -> HTTP %s", task_id, response.status_code)
