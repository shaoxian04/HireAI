"""The demo agent accepts only WorkOrders signed with its AgentSigningSecret (when one is set)."""
import hashlib
import hmac
import json
import time

import pytest
from fastapi.testclient import TestClient

import app as agent

SECRET = "agsec_" + "ab" * 32
# Exact bytes as the platform would send them; the signature covers these bytes, not a re-dump.
BODY = json.dumps({"taskId": "t-1", "callbackUrl": "https://platform.test/cb"}, separators=(",", ":")).encode()


def sign(secret: str, ts: int, body: bytes) -> str:
    digest = hmac.new(secret.encode(), f"{ts}.".encode() + body, hashlib.sha256).hexdigest()
    return f"t={ts},v1={digest}"


@pytest.fixture
def started(monkeypatch):
    """Records the tasks the agent starts instead of calling back for real."""
    calls = []

    async def fake_complete(task_id, callback_url, token, secret=None):
        calls.append(task_id)

    monkeypatch.setattr(agent, "_complete", fake_complete)
    return calls


@pytest.fixture
def client(monkeypatch, started):
    monkeypatch.setenv(agent.SECRET_ENV, SECRET)
    return TestClient(agent.app)


def post(client, signature=None, body=BODY):
    headers = {"Authorization": "Bearer tok", "Content-Type": "application/json"}
    if signature is not None:
        headers[agent.SIGNATURE_HEADER] = signature
    return client.post("/run", content=body, headers=headers)


def test_a_correctly_signed_work_order_is_accepted(client, started):
    response = post(client, sign(SECRET, int(time.time()), BODY))

    assert response.status_code == 200
    assert response.json()["taskId"] == "t-1"
    assert started == ["t-1"]


def test_a_signature_made_with_another_secret_is_rejected(client, started):
    response = post(client, sign("agsec_old", int(time.time()), BODY))

    assert response.status_code == 401
    assert started == []


def test_a_tampered_body_is_rejected(client, started):
    signature = sign(SECRET, int(time.time()), BODY)
    tampered = BODY.replace(b"t-1", b"t-2")

    assert post(client, signature, tampered).status_code == 401
    assert started == []


def test_a_missing_signature_is_rejected_when_a_secret_is_set(client, started):
    assert post(client).status_code == 401
    assert started == []


def test_a_stale_timestamp_is_rejected(client, started):
    stale = int(time.time()) - 301

    assert post(client, sign(SECRET, stale, BODY)).status_code == 401
    assert started == []


def test_without_a_secret_an_unsigned_work_order_is_accepted(monkeypatch, started):
    monkeypatch.delenv(agent.SECRET_ENV, raising=False)

    assert post(TestClient(agent.app)).status_code == 200
    assert started == ["t-1"]
