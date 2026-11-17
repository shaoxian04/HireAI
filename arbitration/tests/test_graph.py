from app.graph.build import build_graph, run_arbitration
from app.graph.state import ArbitrationState  # noqa: F401
from app.schemas import ArbitrationRequest, RulingResult


class _FakeStructured:
    def __init__(self, result):
        self._result = result

    async def ainvoke(self, messages):
        return self._result


class FakeLLM:
    """Minimal stand-in for ChatOpenAI: records the evidence it saw, returns canned output."""

    def __init__(self, category, rationale):
        self._result = RulingResult(category=category, rationale=rationale)
        self.seen = []

    async def ainvoke(self, messages):
        self.seen.append(messages)

        class _Msg:
            content = "reasoned analysis"

        return _Msg()

    def with_structured_output(self, _schema):
        return _FakeStructured(self._result)


def _request(**over):
    base = dict(dispute_id="d", task_id="t", correlation_id="c", format="JSON",
                schema_=None, acceptance_criteria="List 3 sources",
                task_description="Find three sources about four-day work weeks",
                result_payload_json='{"sources":["a","b","c"]}', result_url=None,
                reason_category="C_INCOMPLETE")
    base.update(over)
    return ArbitrationRequest.model_validate(base)


async def test_task_description_reaches_the_arbitrator():
    """The submitted task must appear in the evidence, so the arbitrator can judge relevance —
    not just whether the output is a well-formed answer to some other task."""
    llm = FakeLLM("FULFILLED", "ok")
    graph = build_graph(llm, _settings())
    await run_arbitration(graph, _request(task_description="Summarise THIS specific article XYZ"))
    seen_text = "".join(
        m.content for messages in llm.seen for m in messages if hasattr(m, "content")
    )
    assert "Summarise THIS specific article XYZ" in seen_text


async def test_the_frozen_contract_reaches_the_arbitrator():
    """Invariant #4: arbitration judges against the same Contract automated acceptance used."""
    llm = FakeLLM("FULFILLED", "ok")
    graph = build_graph(llm, _settings())
    contract = '{"template":"text_basic","version":1,"params":{"max_length":800}}'
    await run_arbitration(graph, _request(contract=contract))
    seen_text = "".join(
        m.content for messages in llm.seen for m in messages if hasattr(m, "content")
    )
    assert "CONTRACT (binding terms, frozen at submit)" in seen_text
    assert contract in seen_text


def test_contract_is_read_from_the_backend_camel_case_message():
    req = ArbitrationRequest.model_validate_json(
        '{"disputeId":"d","taskId":"t","correlationId":"c","format":"TEXT",'
        '"reasonCategory":"A_MISMATCH","contract":"{\\"template\\":\\"text_basic\\"}"}'
    )
    assert req.contract == '{"template":"text_basic"}'


async def test_graph_returns_structured_ruling():
    llm = FakeLLM("FULFILLED", "All three sources present.")
    graph = build_graph(llm, _settings())
    result = await run_arbitration(graph, _request())
    assert isinstance(result, RulingResult)
    assert result.category == "FULFILLED"
    assert "three sources" in result.rationale


async def test_graph_coerces_unknown_category_to_not_fulfilled():
    llm = FakeLLM("NONSENSE", "garbled")
    graph = build_graph(llm, _settings())
    result = await run_arbitration(graph, _request())
    assert result.category == "NOT_FULFILLED"


def _settings():
    from app.config import Settings
    return Settings()
