"""The demo agent signs its result callback with its AgentSigningSecret, over the exact bytes sent."""
import asyncio
import hashlib
import hmac
import json

import httpx
import pytest

import app as agent

SECRET = "agsec_" + "cd" * 32


def expected_signature(secret: str, ts: str, body: bytes) -> str:
    return hmac.new(secret.encode(), f"{ts}.".encode() + body, hashlib.sha256).hexdigest()


@pytest.fixture
def sent(monkeypatch):
    """Runs _complete against an in-memory transport and records the callback request."""
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"success": True})

    real_client = httpx.AsyncClient

    async def no_sleep(_seconds):
        return None

    monkeypatch.setattr(agent.asyncio, "sleep", no_sleep)
    monkeypatch.setattr(
        agent.httpx, "AsyncClient",
        lambda **kwargs: real_client(transport=httpx.MockTransport(handler), **kwargs),
    )
    return requests


def test_the_callback_is_signed_over_the_exact_bytes_sent(sent):
    asyncio.run(agent._complete("t-1", "https://platform.test/cb", "tok", SECRET))

    assert len(sent) == 1
    request = sent[0]
    assert request.headers["Authorization"] == "Bearer tok"
    header = request.headers[agent.SIGNATURE_HEADER]
    parts = dict(p.split("=", 1) for p in header.split(","))
    assert parts["v1"] == expected_signature(SECRET, parts["t"], request.content)
    # And the agent's own verifier (the platform's rule) accepts it.
    assert agent.verify_signature(SECRET, header, request.content)
    assert json.loads(request.content)["agentStatus"] == "COMPLETED"


def test_without_a_secret_the_callback_is_unsigned(sent):
    asyncio.run(agent._complete("t-1", "https://platform.test/cb", "tok", None))

    assert agent.SIGNATURE_HEADER not in sent[0].headers


def test_run_hands_the_configured_secret_to_the_callback(monkeypatch):
    from fastapi.testclient import TestClient

    secrets = []

    async def fake_complete(task_id, callback_url, token, secret=None):
        secrets.append(secret)

    monkeypatch.setattr(agent, "_complete", fake_complete)
    monkeypatch.setenv(agent.SECRET_ENV, SECRET)
    body = json.dumps({"taskId": "t-1", "callbackUrl": "https://platform.test/cb"}).encode()

    response = TestClient(agent.app).post("/run", content=body, headers={
        "Authorization": "Bearer tok",
        "Content-Type": "application/json",
        agent.SIGNATURE_HEADER: agent.sign_body(SECRET, body),
    })

    assert response.status_code == 200
    assert secrets == [SECRET]


def test_sign_body_binds_the_timestamp_and_body():
    body = b'{"a":1}'
    assert agent.sign_body(SECRET, body, now=1_700_000_000) == (
        "t=1700000000,v1=" + expected_signature(SECRET, "1700000000", body)
    )
