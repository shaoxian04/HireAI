import json

import httpx
import pytest

from app.config import Settings
from app.consumer import handle_message
from app.schemas import RulingResult

GOLDEN = json.dumps({
    "disputeId": "11111111-1111-1111-1111-111111111111",
    "taskId": "22222222-2222-2222-2222-222222222222",
    "correlationId": "c", "format": "TEXT", "schema": None,
    "acceptanceCriteria": "be helpful", "resultPayloadJson": "hello",
    "resultUrl": None, "reasonCategory": "A_MISMATCH",
}).encode()


class FakeMessage:
    def __init__(self, body):
        self.body = body
        self.acked = False
        self.nacked_requeue = None

    async def ack(self):
        self.acked = True

    async def nack(self, requeue=True):
        self.nacked_requeue = requeue


class StubGraph:
    pass


async def _ok_arbitrate(graph, req):
    return RulingResult(category="FULFILLED", rationale="ok")


async def _boom_arbitrate(graph, req):
    raise RuntimeError("LLM down")


@pytest.fixture
def settings():
    return Settings(backend_base_url="http://backend.test", arbitration_callback_secret="s")


async def test_acks_after_successful_callback(settings, monkeypatch):
    import app.consumer as consumer

    monkeypatch.setattr(consumer, "run_arbitration", _ok_arbitrate)

    async def _ok(*a, **k):
        return 200

    monkeypatch.setattr(consumer, "post_ruling", _ok)
    msg = FakeMessage(GOLDEN)
    await handle_message(msg, settings=settings, graph=StubGraph())
    assert msg.acked is True
    assert msg.nacked_requeue is None


async def test_nacks_to_dlq_on_unparseable_body(settings):
    msg = FakeMessage(b"{not json")
    await handle_message(msg, settings=settings, graph=StubGraph())
    assert msg.acked is False
    assert msg.nacked_requeue is False


async def test_nacks_to_dlq_when_arbitration_fails(settings, monkeypatch):
    import app.consumer as consumer

    monkeypatch.setattr(consumer, "run_arbitration", _boom_arbitrate)
    msg = FakeMessage(GOLDEN)
    await handle_message(msg, settings=settings, graph=StubGraph())
    assert msg.acked is False
    assert msg.nacked_requeue is False


async def test_nacks_to_dlq_when_callback_unauthorized(settings, monkeypatch):
    import app.consumer as consumer

    monkeypatch.setattr(consumer, "run_arbitration", _ok_arbitrate)

    async def _401(*a, **k):
        raise httpx.HTTPStatusError("401", request=None, response=httpx.Response(401))

    monkeypatch.setattr(consumer, "post_ruling", _401)
    msg = FakeMessage(GOLDEN)
    await handle_message(msg, settings=settings, graph=StubGraph())
    assert msg.acked is False
    assert msg.nacked_requeue is False
