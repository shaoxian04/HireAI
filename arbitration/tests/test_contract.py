"""Cross-language contract test — pins inbound/outbound shapes to the Java records.

Inbound:  com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage
Outbound: com.hireai.application.biz.adjudication.port.ArbitrationRulingRequest
"""

from pydantic.alias_generators import to_camel

from app.schemas import ArbitrationRequest, RulingResult

# Mirror of ArbitrationRequestMessage's camelCase field names (Jackson defaults).
JAVA_FIELDS = {
    "disputeId",
    "taskId",
    "correlationId",
    "format",
    "schema",
    "acceptanceCriteria",
    "taskDescription",
    "resultPayloadJson",
    "resultUrl",
    "reasonCategory",
}


def test_inbound_accepts_exactly_the_java_message_fields():
    """The model's alias set must exactly match the Java record — any drift is caught here."""
    model_aliases = {
        fi.alias if fi.alias is not None else to_camel(name)
        for name, fi in ArbitrationRequest.model_fields.items()
    }
    assert model_aliases == JAVA_FIELDS

    req = ArbitrationRequest.model_validate(
        {
            "disputeId": "11111111-1111-1111-1111-111111111111",
            "taskId": "22222222-2222-2222-2222-222222222222",
            "correlationId": "c",
            "format": "FILE",
            "schema": None,
            "acceptanceCriteria": None,
            "resultPayloadJson": None,
            "resultUrl": "https://example.com/out.json",
            "reasonCategory": "B_FACTUAL",
        }
    )
    assert req.format == "FILE"
    assert req.result_url == "https://example.com/out.json"


def test_outbound_matches_ArbitrationRulingRequest():
    """Outbound payload must have exactly {category, rationale} with a valid category."""
    # Java: record ArbitrationRulingRequest(@NotBlank String category, String rationale)
    payload = RulingResult(category="PARTIALLY_FULFILLED", rationale="r").model_dump()
    assert set(payload.keys()) == {"category", "rationale"}
    assert payload["category"] in {"FULFILLED", "PARTIALLY_FULFILLED", "NOT_FULFILLED"}
