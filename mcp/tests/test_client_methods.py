import json

import httpx
import pytest
import respx

from app.client import HireAIClient, ResultNotReady

BASE = "http://backend.test"


def client():
    return HireAIClient(BASE, "hk_live_testkey", timeout=5)


def ok(data):
    return httpx.Response(200, json={"success": True, "code": "OK", "message": None, "data": data})


@respx.mock
async def test_list_agents_builds_query_params():
    route = respx.get(f"{BASE}/api/catalogue/agents").mock(return_value=ok([{"id": "a1"}]))
    out = await client().list_agents(category="summarization", query="pdf", sort="hot", page=1, size=5)
    assert out == [{"id": "a1"}]
    got = dict(httpx.QueryParams(route.calls.last.request.url.query))
    assert got == {"q": "pdf", "category": "summarization", "sort": "hot", "page": "1", "size": "5"}


@respx.mock
async def test_submit_direct_posts_agentid_and_idempotency_key():
    route = respx.post(f"{BASE}/api/tasks/direct").mock(return_value=ok({"id": "t1", "status": "SUBMITTED"}))
    out = await client().submit_direct(title="T", description="d", budget=50, agent_id="a1")
    assert out == {"id": "t1", "status": "SUBMITTED"}
    req = route.calls.last.request
    assert json.loads(req.content) == {"title": "T", "description": "d", "budget": 50, "agentId": "a1"}
    assert req.headers.get("idempotency-key")  # present and non-empty


@respx.mock
async def test_submit_routed_posts_output_spec():
    route = respx.post(f"{BASE}/api/tasks").mock(return_value=ok({"id": "t2", "status": "SUBMITTED"}))
    await client().submit_routed(title="T", description="d", category="summarization",
                                 budget=50, output_format="TEXT", acceptance_criteria="concise")
    body = json.loads(route.calls.last.request.content)
    assert body["category"] == "summarization"
    assert body["outputSpec"] == {"format": "TEXT", "schema": "", "acceptanceCriteria": "concise"}


@respx.mock
async def test_get_task_returns_data():
    respx.get(f"{BASE}/api/tasks/t1").mock(return_value=ok({"id": "t1", "status": "WORKING"}))
    assert (await client().get_task("t1"))["status"] == "WORKING"


@respx.mock
async def test_get_task_result_returns_payload():
    respx.get(f"{BASE}/api/tasks/t1/result").mock(return_value=ok({"taskId": "t1", "resultPayloadJson": "{}"}))
    assert (await client().get_task_result("t1"))["taskId"] == "t1"


@respx.mock
async def test_get_task_result_not_found_raises_result_not_ready():
    respx.get(f"{BASE}/api/tasks/t1/result").mock(return_value=httpx.Response(
        404, json={"success": False, "code": "NOT_FOUND", "message": "no result", "data": None}))
    with pytest.raises(ResultNotReady):
        await client().get_task_result("t1")
