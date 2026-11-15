"""#99 (D13): image and PDF snapshots reach the model as content, not as an "omitted" line."""
import base64
import json

import httpx2
import pytest
from langchain_openai import ChatOpenAI

from app.config import Settings
from app.graph import nodes
from app.graph.build import build_graph, run_arbitration
from app.schemas import ArbitrationRequest, RulingResult

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 24
PDF = b"%PDF-1.7\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n"


def _request(content: bytes | None, media_type: str, **over):
    base = dict(
        dispute_id="d", task_id="t", correlation_id="c", format="FILE",
        acceptance_criteria="A logo with a red circle", task_description="Draw a logo",
        result_payload_json=None, result_url="https://agent.example/logo.png",
        reason_category="A_MISMATCH",
        deliverable={
            "sha256": "ab" * 32,
            "mediaType": media_type,
            "sizeBytes": 99 if content is None else len(content),
            "contentBase64": None if content is None else base64.b64encode(content).decode(),
        },
    )
    base.update(over)
    return ArbitrationRequest.model_validate(base)


async def _gather(req, media_max_bytes=1_000_000):
    return await nodes.gather_evidence(
        {"request": req}, max_bytes=1000, timeout=1, media_max_bytes=media_max_bytes
    )


async def test_an_image_snapshot_is_attached_as_an_image():
    out = await _gather(_request(PNG, "image/png"))

    assert out["evidence_media"] == [
        {"type": "image", "mime_type": "image/png", "base64": base64.b64encode(PNG).decode()}
    ]
    assert "binary content omitted" not in out["evidence"]
    assert "attached" in out["evidence"]


async def test_a_pdf_snapshot_is_attached_as_a_document():
    out = await _gather(_request(PDF, "application/pdf"))

    assert out["evidence_media"] == [{
        "type": "file", "mime_type": "application/pdf",
        "base64": base64.b64encode(PDF).decode(), "filename": "deliverable.pdf",
    }]
    assert "PDF" in out["evidence"]


async def test_an_image_over_the_attachment_limit_is_described_instead():
    out = await _gather(_request(PNG, "image/png"), media_max_bytes=len(PNG) - 1)

    assert out["evidence_media"] == []
    assert "not attached" in out["evidence"]


async def test_a_non_visual_binary_is_still_only_described():
    out = await _gather(_request(b"PK\x03\x04rest", "application/zip"))

    assert out["evidence_media"] == []
    assert "binary content omitted" in out["evidence"]


async def test_a_text_result_attaches_nothing():
    out = await _gather(_request(b"total,42\n", "text/csv"))

    assert out["evidence_media"] == []
    assert "total,42" in out["evidence"]


class _RecordingLLM:
    """Stand-in for the chat model: records every message list it is sent, plain or structured."""

    def __init__(self):
        self.calls = []

    async def ainvoke(self, messages):
        self.calls.append(messages)

        class _Msg:
            content = "the logo shows a blue square"

        return _Msg()

    def with_structured_output(self, _schema):
        outer = self

        class _Structured:
            async def ainvoke(self, messages):
                outer.calls.append(messages)
                return RulingResult(category="NOT_FULFILLED", rationale="blue square, no circle")

        return _Structured()


async def test_every_model_call_in_the_graph_sees_the_image():
    llm = _RecordingLLM()
    graph = build_graph(llm, Settings())

    result = await run_arbitration(graph, _request(PNG, "image/png"))

    assert result.category == "NOT_FULFILLED"
    assert len(llm.calls) == 3  # deliberate, classify, critique
    data = base64.b64encode(PNG).decode()
    for messages in llm.calls:
        human = messages[-1]
        assert human.content[0]["type"] == "text"
        assert "Draw a logo" in human.content[0]["text"]
        assert {"type": "image", "mime_type": "image/png", "base64": data} in human.content


def _completion(text: str) -> dict:
    return {
        "id": "x", "object": "chat.completion", "created": 0, "model": "gpt-4o",
        "choices": [{"index": 0, "finish_reason": "stop",
                     "message": {"role": "assistant", "content": text}}],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
    }


@pytest.mark.parametrize("content,media_type,expected_part", [
    (PNG, "image/png", {"type": "image_url", "image_url": {
        "url": "data:image/png;base64," + base64.b64encode(PNG).decode()}}),
    (PDF, "application/pdf", {"type": "file", "file": {
        "file_data": "data:application/pdf;base64," + base64.b64encode(PDF).decode(),
        "filename": "deliverable.pdf"}}),
])
async def test_openai_receives_the_snapshot_as_a_content_part(content, media_type, expected_part):
    # The real ChatOpenAI adapter over a mock transport: checks the wire format, never the network.
    # (openai's client runs on httpx2, which respx does not patch.)
    sent_bodies = []

    def handler(request):
        sent_bodies.append(json.loads(request.content))
        return httpx2.Response(200, json=_completion("looked at it"))

    llm = ChatOpenAI(model="gpt-4o", api_key="test-key", base_url="https://openai.test/v1",
                     max_retries=0,
                     http_async_client=httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    req = _request(content, media_type)
    evidence = await _gather(req)

    out = await nodes.deliberate({**evidence, "request": req}, llm=llm)

    assert out["deliberation"] == "looked at it"
    sent = sent_bodies[-1]
    user_parts = sent["messages"][-1]["content"]
    assert user_parts[0]["type"] == "text"
    assert expected_part in user_parts


async def test_without_attachments_the_model_gets_plain_text():
    llm = _RecordingLLM()
    graph = build_graph(llm, Settings())

    await run_arbitration(graph, _request(b"total,42\n", "text/csv"))

    assert all(isinstance(messages[-1].content, str) for messages in llm.calls)
