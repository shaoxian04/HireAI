import json

import pytest

from app import server
from app.client import ResultNotReady


class FakeClient:
    def __init__(self):
        self.calls = []

    async def list_agents(self, **kwargs):
        self.calls.append(("list_agents", kwargs))
        return [{"id": "a1", "name": "Summarizer", "price": 10}]

    async def submit_direct(self, **kwargs):
        self.calls.append(("submit_direct", kwargs))
        return {"id": "t-direct", "status": "SUBMITTED"}

    async def submit_routed(self, **kwargs):
        self.calls.append(("submit_routed", kwargs))
        return {"id": "t-routed", "status": "SUBMITTED"}

    async def get_task(self, task_id):
        self.calls.append(("get_task", task_id))
        return {"id": task_id, "status": "WORKING", "resolution": None}

    async def get_task_result(self, task_id):
        self.calls.append(("get_task_result", task_id))
        return {"taskId": task_id, "resultPayloadJson": "{\"ok\": true}"}


@pytest.fixture
def fake(monkeypatch):
    fc = FakeClient()
    monkeypatch.setattr(server, "_client", lambda: fc)
    return fc


async def test_list_agents_passes_filters(fake):
    out = await server.list_agents(category="summarization", query="pdf")
    assert fake.calls[0] == ("list_agents", {"category": "summarization", "query": "pdf",
                                             "sort": "hot", "page": 0, "size": 20})
    assert "Summarizer" in out


async def test_submit_task_direct_when_agent_id_given(fake):
    out = await server.submit_task(description="do x", budget=50, agent_id="a1")
    assert fake.calls[0][0] == "submit_direct"
    assert json.loads(out) == {"task_id": "t-direct", "status": "SUBMITTED"}


async def test_submit_task_routed_without_agent_id(fake):
    out = await server.submit_task(description="do x", budget=50, category="summarization")
    assert fake.calls[0][0] == "submit_routed"
    assert json.loads(out)["task_id"] == "t-routed"


async def test_submit_task_routed_requires_category(fake):
    out = await server.submit_task(description="do x", budget=50)
    assert "category" in out.lower()
    assert fake.calls == []  # short-circuits before any client call


async def test_get_task_status_shapes_output(fake):
    out = await server.get_task_status("t9")
    assert json.loads(out) == {"task_id": "t9", "status": "WORKING", "resolution": None}


async def test_get_task_result_not_ready(monkeypatch, fake):
    async def boom(task_id):
        raise ResultNotReady()

    monkeypatch.setattr(fake, "get_task_result", boom)
    out = await server.get_task_result("t1")
    assert "not ready" in out.lower()


async def test_all_four_tools_registered():
    tools = await server.mcp.list_tools()
    assert {t.name for t in tools} == {"list_agents", "submit_task", "get_task_status", "get_task_result"}
