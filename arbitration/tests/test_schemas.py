from app.schemas import ArbitrationRequest, RulingResult

GOLDEN = """
{
  "disputeId": "11111111-1111-1111-1111-111111111111",
  "taskId": "22222222-2222-2222-2222-222222222222",
  "correlationId": "dispute-22222222-2222-2222-2222-222222222222",
  "format": "JSON",
  "schema": "{\\"type\\":\\"object\\"}",
  "acceptanceCriteria": "Must list at least 3 sources.",
  "taskDescription": "Find at least three reputable sources on four-day work weeks.",
  "resultPayloadJson": "{\\"sources\\":[\\"a\\"]}",
  "resultUrl": null,
  "reasonCategory": "C_INCOMPLETE"
}
"""


def test_parses_camel_case_golden_message():
    req = ArbitrationRequest.model_validate_json(GOLDEN)
    assert str(req.dispute_id) == "11111111-1111-1111-1111-111111111111"
    assert req.format == "JSON"
    assert req.schema_ == '{"type":"object"}'
    assert req.acceptance_criteria == "Must list at least 3 sources."
    assert req.task_description == "Find at least three reputable sources on four-day work weeks."
    assert req.result_payload_json == '{"sources":["a"]}'
    assert req.result_url is None
    assert req.reason_category == "C_INCOMPLETE"


def test_tolerates_unknown_fields():
    payload = (
        '{"disputeId":"11111111-1111-1111-1111-111111111111",'
        '"taskId":"22222222-2222-2222-2222-222222222222",'
        '"correlationId":"c","format":"TEXT",'
        '"reasonCategory":"A_MISMATCH","somethingExtra":true}'
    )
    req = ArbitrationRequest.model_validate_json(payload)
    assert req.format == "TEXT"


def test_ruling_result_round_trips():
    r = RulingResult(category="PARTIALLY_FULFILLED", rationale="missing one source")
    assert r.model_dump() == {"category": "PARTIALLY_FULFILLED", "rationale": "missing one source"}
