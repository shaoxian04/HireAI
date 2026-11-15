"""The demo agent answers a rejection notice (#95) with a signed revise / concede / stand-by response."""
import asyncio
import json

import httpx
import pytest

import app as agent

SECRET = "agsec_" + "ef" * 32


def notice(reason: str = "wrong", revision_allowed: bool = True) -> dict:
    return {
        "event": "task.rejected",
        "taskId": "t-1",
        "round": 1,
        "reasonCategory": "A_MISMATCH",
        "reason": reason,
        "revisionAllowed": revision_allowed,
        "responseUrl": "https://platform.example/api/agent-callbacks/t-1/rejection-response",
    }


def test_a_tag_in_the_reason_picks_the_response(monkeypatch):
    monkeypatch.delenv(agent.REJECTION_ENV, raising=False)
    assert agent.choose_rejection_response(notice("dates wrong #revise")) == "REVISE"
    assert agent.choose_rejection_response(notice("#concede please")) == "CONCEDE"
    assert agent.choose_rejection_response(notice("#silent")) == "SILENT"
    assert agent.choose_rejection_response(notice("no tag")) == "STAND_BY"


def test_the_env_sets_the_default_and_a_spent_revision_falls_back(monkeypatch):
    monkeypatch.setenv(agent.REJECTION_ENV, "concede")
    assert agent.choose_rejection_response(notice("no tag")) == "CONCEDE"
    assert agent.choose_rejection_response(notice("#revise", revision_allowed=False)) == "STAND_BY"


@pytest.mark.parametrize("action,field", [("REVISE", "resultPayloadJson"), ("STAND_BY", "reason")])
def test_the_response_is_signed_and_names_its_task(monkeypatch, action, field):
    requests: list[httpx.Request] = []
    real_client = httpx.AsyncClient

    async def no_sleep(_seconds):
        return None

    monkeypatch.setattr(agent.asyncio, "sleep", no_sleep)
    monkeypatch.setattr(
        agent.httpx, "AsyncClient",
        lambda **kwargs: real_client(
            transport=httpx.MockTransport(lambda r: (requests.append(r), httpx.Response(200))[1]), **kwargs),
    )

    asyncio.run(agent._respond_to_rejection(notice(), action, "tok", SECRET))

    sent = requests[0]
    body = json.loads(sent.content)
    assert str(sent.url) == notice()["responseUrl"]
    assert sent.headers["Authorization"] == "Bearer tok"
    assert body["taskId"] == "t-1" and body["action"] == action and body[field]
    assert agent.verify_signature(SECRET, sent.headers[agent.SIGNATURE_HEADER], sent.content)
