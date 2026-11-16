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
async def test_list_categories_reads_the_catalogue_categories():
    rows = [{"category": "summarisation", "label": "Summarisation", "agentCount": 2}]
    route = respx.get(f"{BASE}/api/catalogue/categories").mock(return_value=ok(rows))
    out = await client().list_categories()
    assert out == rows
    assert route.calls.last.request.headers["Authorization"] == "ApiKey hk_live_testkey"


@respx.mock
async def test_submit_direct_posts_agentid_and_idempotency_key():
    route = respx.post(f"{BASE}/api/tasks/direct").mock(return_value=ok({"id": "t1", "status": "SUBMITTED"}))
    out = await client().submit_direct(title="T", description="d", budget=50, agent_id="a1")
    assert out == {"id": "t1", "status": "SUBMITTED"}
    req = route.calls.last.request
    assert json.loads(req.content) == {"title": "T", "description": "d", "budget": 50, "agentId": "a1"}
    assert req.headers.get("idempotency-key")  # present and non-empty


@respx.mock
async def test_submit_routed_mode_1_posts_the_description_without_output_spec_or_contract():
    route = respx.post(f"{BASE}/api/tasks").mock(return_value=ok({"id": "t2", "status": "SUBMITTED"}))
    await client().submit_routed(title="T", description="d", category="summarization", budget=50)
    body = json.loads(route.calls.last.request.content)
    assert body == {"title": "T", "description": "d", "category": "summarization", "budget": 50}


@respx.mock
async def test_submit_routed_with_a_schema_asks_for_json_conforming_to_it():
    route = respx.post(f"{BASE}/api/tasks").mock(return_value=ok({"id": "t2", "status": "SUBMITTED"}))
    await client().submit_routed(title="T", description="d", category="summarization", budget=50,
                                 schema='{"type":"object"}')
    body = json.loads(route.calls.last.request.content)
    assert body["outputSpec"] == {"format": "JSON", "schema": '{"type":"object"}'}
    assert "contract" not in body


@respx.mock
async def test_submit_routed_mode_2_posts_the_contract():
    route = respx.post(f"{BASE}/api/tasks").mock(return_value=ok({"id": "t2", "status": "SUBMITTED"}))
    contract = {"template": "text_basic", "version": 1, "params": {"max_length": 500}}
    await client().submit_routed(title="T", description="d", category="summarization", budget=50,
                                 contract=contract)
    body = json.loads(route.calls.last.request.content)
    assert body["contract"] == contract
    assert "outputSpec" not in body


@respx.mock
async def test_submit_direct_posts_the_contract_only_when_given():
    route = respx.post(f"{BASE}/api/tasks/direct").mock(return_value=ok({"id": "t1", "status": "SUBMITTED"}))
    contract = {"template": "text_basic", "version": 1, "params": {}}
    await client().submit_direct(title="T", description="d", budget=50, agent_id="a1", contract=contract)
    assert json.loads(route.calls.last.request.content)["contract"] == contract


@respx.mock
async def test_draft_contract_posts_the_description_and_template():
    draft = {"contract": {"template": "image_generation", "version": 1, "params": {}}, "extraction": {}}
    route = respx.post(f"{BASE}/api/contracts/draft").mock(return_value=ok(draft))
    out = await client().draft_contract(description="a 512x512 logo", template="image_generation")
    assert out == draft
    assert json.loads(route.calls.last.request.content) == {"description": "a 512x512 logo",
                                                            "template": "image_generation"}
    assert route.calls.last.request.headers["Authorization"] == "ApiKey hk_live_testkey"


@respx.mock
async def test_draft_contract_with_a_schema_drafts_onto_json():
    route = respx.post(f"{BASE}/api/contracts/draft").mock(return_value=ok({"contract": {}, "extraction": {}}))
    await client().draft_contract(description="d", title="T", schema='{"type":"object"}')
    assert json.loads(route.calls.last.request.content) == {
        "description": "d", "title": "T", "outputSpec": {"format": "JSON", "schema": '{"type":"object"}'}}


@respx.mock
async def test_submit_sends_the_deadline_only_when_given():
    routed = respx.post(f"{BASE}/api/tasks").mock(return_value=ok({"id": "t2", "status": "SUBMITTED"}))
    direct = respx.post(f"{BASE}/api/tasks/direct").mock(return_value=ok({"id": "t1", "status": "SUBMITTED"}))
    await client().submit_routed(title="T", description="d", category="summarisation",
                                 budget=50, deadline_seconds=600)
    await client().submit_direct(title="T", description="d", budget=50, agent_id="a1", deadline_seconds=900)
    assert json.loads(routed.calls.last.request.content)["deadlineSeconds"] == 600
    assert json.loads(direct.calls.last.request.content)["deadlineSeconds"] == 900

    await client().submit_routed(title="T", description="d", category="summarisation", budget=50)
    assert "deadlineSeconds" not in json.loads(routed.calls.last.request.content)


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
