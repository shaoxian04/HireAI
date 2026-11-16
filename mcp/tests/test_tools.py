import json

import pytest

from app import server
from app.client import ResultNotReady

FROZEN = {"template": "text_basic", "version": 1, "params": {"max_length": 800}}
EXTRACTION = {"template": "text_basic", "version": 1, "status": "EXTRACTED",
              "requirements": [{"text": "under 800 characters", "mapped": True, "check": "TEXT_MAX_LENGTH",
                                "params": {"max_length": 800}},
                               {"text": "friendly tone", "mapped": False, "unmappedReason": "no check"}]}


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
        return {"id": "t-routed", "status": "SUBMITTED", "contract": FROZEN, "extraction": EXTRACTION}

    async def draft_contract(self, **kwargs):
        self.calls.append(("draft_contract", kwargs))
        return {"contract": FROZEN, "extraction": EXTRACTION}

    async def list_categories(self):
        self.calls.append(("list_categories", None))
        return [{"category": "document-extraction", "label": "Document extraction", "agentCount": 3},
                {"category": "other", "label": "Other", "agentCount": 0}]

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
    out = await server.list_agents(category="summarisation", query="pdf")
    assert fake.calls[0] == ("list_agents", {"category": "summarisation", "query": "pdf",
                                             "sort": "hot", "page": 0, "size": 20})
    assert "Summarizer" in out


async def test_submit_task_direct_when_agent_id_given(fake):
    out = await server.submit_task(description="do x", budget=50, agent_id="a1")
    assert fake.calls[0][0] == "submit_direct"
    assert json.loads(out) == {"task_id": "t-direct", "status": "SUBMITTED"}


async def test_submit_task_routed_without_agent_id(fake):
    out = await server.submit_task(description="do x", budget=50, category="summarisation")
    assert fake.calls[0][0] == "submit_routed"
    assert json.loads(out)["task_id"] == "t-routed"


async def test_mode_1_sends_the_description_only_and_returns_the_frozen_contract_and_extraction(fake):
    out = json.loads(await server.submit_task(description="A bio under 800 characters, friendly tone",
                                              budget=50, category="summarisation"))
    _, kwargs = fake.calls[0]
    assert kwargs["contract"] is None
    assert kwargs["description"] == "A bio under 800 characters, friendly tone"
    assert out == {"task_id": "t-routed", "status": "SUBMITTED", "contract": FROZEN, "extraction": EXTRACTION}


async def test_mode_2_sends_the_contract_as_given(fake):
    contract = {"template": "text_basic", "version": 1, "params": {"max_length": 500}}
    await server.submit_task(description="do x", budget=50, category="summarisation", contract=contract)
    assert fake.calls[0][1]["contract"] == contract


async def test_mode_2_accepts_the_contract_as_a_json_string(fake):
    contract = {"template": "text_basic", "version": 1, "params": {"max_length": 500}}
    await server.submit_task(description="do x", budget=50, category="summarisation",
                             contract=json.dumps(contract))
    assert fake.calls[0][1]["contract"] == contract


async def test_a_contract_that_is_not_a_json_object_is_refused_before_any_call(fake):
    out = await server.submit_task(description="do x", budget=50, category="summarisation",
                                   contract="max 500 characters")
    assert "contract" in out.lower() and "json object" in out.lower()
    assert fake.calls == []


async def test_a_contract_and_a_schema_together_are_refused_before_any_call(fake):
    out = await server.submit_task(description="do x", budget=50, category="summarisation",
                                   contract={"template": "custom_json", "version": 1, "params": {}},
                                   schema='{"type":"object"}')
    assert "schema" in out.lower() and "contract" in out.lower()
    assert fake.calls == []


async def test_a_direct_booking_passes_the_contract_on(fake):
    contract = {"template": "text_basic", "version": 1, "params": {"max_length": 500}}
    await server.submit_task(description="do x", budget=50, agent_id="a1", contract=contract)
    await server.submit_task(description="do x", budget=50, agent_id="a1")
    assert fake.calls[0] == ("submit_direct", {"title": "Task via MCP", "description": "do x", "budget": 50,
                                               "agent_id": "a1", "deadline_seconds": None,
                                               "contract": contract})
    assert fake.calls[1][1]["contract"] is None


@pytest.mark.parametrize("removed", ["output_format", "output_spec"])
async def test_the_misleading_output_parameters_are_gone(removed):
    tools = {t.name: t for t in await server.mcp.list_tools()}
    props = tools["submit_task"].inputSchema["properties"]
    assert removed not in props
    assert {"description", "contract"} <= set(props)
    with pytest.raises(TypeError):
        await server.submit_task(description="do x", budget=50, category="summarisation", **{removed: "TEXT"})


async def test_draft_contract_returns_the_contract_and_extraction(fake):
    out = json.loads(await server.draft_contract(description="A bio under 800 characters",
                                                 template="text_basic"))
    assert fake.calls[0] == ("draft_contract", {"description": "A bio under 800 characters",
                                                "title": "", "template": "text_basic", "schema": ""})
    assert out == {"contract": FROZEN, "extraction": EXTRACTION}


async def test_submit_task_routed_requires_category(fake):
    out = await server.submit_task(description="do x", budget=50)
    assert "category" in out.lower()
    assert fake.calls == []  # short-circuits before any client call


async def test_get_task_status_shapes_output(fake):
    out = await server.get_task_status("t9")
    assert json.loads(out) == {"task_id": "t9", "status": "WORKING", "resolution": None,
                               "failure_reason": None, "failure_detail": None, "review_reason": None}


async def test_get_task_status_shows_why_a_task_failed(monkeypatch, fake):
    async def failed_task(task_id):
        # The backend omits failureReason on live tasks and sets it once a task ends without a result.
        return {"id": task_id, "status": "FAILED", "failureReason": "AGENT_UNREACHABLE"}

    monkeypatch.setattr(fake, "get_task", failed_task)
    out = await server.get_task_status("t9")
    assert json.loads(out) == {"task_id": "t9", "status": "FAILED", "resolution": None,
                               "failure_reason": "AGENT_UNREACHABLE", "failure_detail": None,
                               "review_reason": None}


async def test_get_task_status_says_what_ruled_the_agents_out(monkeypatch, fake):
    async def no_match(task_id):
        return {"id": task_id, "status": "FAILED", "failureReason": "NO_ELIGIBLE_AGENT",
                "failureDetail": "3 agents: their offers don't accept the largest file size"}

    monkeypatch.setattr(fake, "get_task", no_match)
    out = json.loads(await server.get_task_status("t9"))
    assert out["failure_detail"] == "3 agents: their offers don't accept the largest file size"


async def test_get_task_status_says_why_a_result_waits_for_a_human(monkeypatch, fake):
    async def waiting(task_id):
        # A booked AI Review gave no usable verdict (#107): nothing settled, the Client decides.
        return {"id": task_id, "status": "PENDING_REVIEW", "reviewReason": "AI_REVIEW_UNAVAILABLE"}

    monkeypatch.setattr(fake, "get_task", waiting)
    out = json.loads(await server.get_task_status("t9"))
    assert out["status"] == "PENDING_REVIEW"
    assert out["review_reason"] == "AI_REVIEW_UNAVAILABLE"


def _no_result_yet(monkeypatch, fake, status, failure_reason=None):
    async def boom(task_id):
        raise ResultNotReady()

    async def task(task_id):
        fake.calls.append(("get_task", task_id))
        return {"id": task_id, "status": status, "resolution": None, "failureReason": failure_reason}

    monkeypatch.setattr(fake, "get_task_result", boom)
    monkeypatch.setattr(fake, "get_task", task)


async def test_get_task_result_not_ready(monkeypatch, fake):
    _no_result_yet(monkeypatch, fake, "EXECUTING")
    out = await server.get_task_result("t1")
    assert "not ready" in out.lower()


@pytest.mark.parametrize("status", ["SUBMITTED", "QUEUED", "EXECUTING", "RESULT_RECEIVED", "AWAITING_AGENT"])
async def test_get_task_result_keeps_polling_while_a_result_can_still_arrive(monkeypatch, fake, status):
    _no_result_yet(monkeypatch, fake, status)
    assert "poll again" in (await server.get_task_result("t1")).lower()


@pytest.mark.parametrize("status,reason", [("FAILED", "AGENT_BUSY"), ("CANCELLED", "NO_ELIGIBLE_AGENT"),
                                           ("TIMED_OUT", "EXECUTION_TIMEOUT")])
async def test_get_task_result_for_a_task_that_ended_without_one_says_so(monkeypatch, fake, status, reason):
    _no_result_yet(monkeypatch, fake, status, reason)
    out = await server.get_task_result("t1")
    assert "poll again shortly" not in out.lower()
    assert json.loads(out) == {"task_id": "t1", "result": None, "status": status, "failure_reason": reason,
                               "message": f"This task ended {status} without a result, so there is none to "
                                          f"fetch. Do not poll again."}


async def test_list_categories_returns_the_platform_list(fake):
    out = await server.list_categories()
    assert fake.calls == [("list_categories", None)]
    assert json.loads(out) == [
        {"slug": "document-extraction", "label": "Document extraction", "agent_count": 3},
        {"slug": "other", "label": "Other", "agent_count": 0},
    ]


async def test_all_tools_registered():
    tools = await server.mcp.list_tools()
    assert {t.name for t in tools} == {"list_agents", "list_categories", "submit_task", "get_task_status",
                                       "get_task_result", "draft_contract"}


async def test_submit_task_passes_the_deadline_on(fake):
    await server.submit_task(description="do x", budget=50, category="summarisation", deadline_seconds=600)
    await server.submit_task(description="do x", budget=50, agent_id="a1", deadline_seconds=900)
    await server.submit_task(description="do x", budget=50, category="summarisation")
    assert fake.calls[0][1]["deadline_seconds"] == 600
    assert fake.calls[1][1]["deadline_seconds"] == 900
    assert fake.calls[2][1]["deadline_seconds"] is None


async def test_submit_task_passes_a_json_schema_on_for_a_routed_task(fake):
    schema = '{"type":"object","required":["total"]}'
    await server.submit_task(description="do x", budget=50, category="summarisation", schema=schema)
    await server.submit_task(description="do x", budget=50, category="summarisation")
    assert fake.calls[0][1]["schema"] == schema
    assert fake.calls[1][1]["schema"] == ""


async def test_submit_task_surfaces_the_backends_400_for_an_unparseable_schema(monkeypatch, fake):
    from app.client import HireAIError

    async def rejecting(**kwargs):
        raise HireAIError("outputSpec.schema: is not a valid JSON Schema (it is not JSON)",
                          code="VALIDATION_ERROR", status=400)

    monkeypatch.setattr(fake, "submit_routed", rejecting)
    with pytest.raises(HireAIError, match="not a valid JSON Schema"):
        await server.submit_task(description="do x", budget=50, category="summarisation",
                                 schema="totals please")
