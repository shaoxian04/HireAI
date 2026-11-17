"""#97 (D24): the arbitrator judges the frozen DeliverySnapshot, never the Agent's link."""
import base64

import pytest

from app.graph import nodes
from app.graph.tools import describe_deliverable
from app.schemas import ArbitrationRequest, Deliverable


def _request(**over):
    base = dict(dispute_id="d", task_id="t", correlation_id="c", format="FILE",
                acceptance_criteria="A CSV of totals", task_description="Total the invoices",
                result_payload_json=None, result_url="https://agent.example/out.csv",
                reason_category="A_MISMATCH")
    base.update(over)
    return ArbitrationRequest.model_validate(base)


def _snapshot(content: bytes | None, media_type: str) -> dict:
    return {
        "sha256": "ab" * 32,
        "mediaType": media_type,
        "sizeBytes": 12 if content is None else len(content),
        "contentBase64": None if content is None else base64.b64encode(content).decode(),
    }


def test_the_request_carries_the_snapshot_in_camel_case():
    req = ArbitrationRequest.model_validate({
        "disputeId": "d", "taskId": "t", "correlationId": "c", "format": "FILE",
        "reasonCategory": "A_MISMATCH", "deliverable": _snapshot(b"a,b\n1,2\n", "text/csv")})
    assert isinstance(req.deliverable, Deliverable)
    assert req.deliverable.media_type == "text/csv"
    assert req.deliverable.content() == b"a,b\n1,2\n"


def test_text_snapshots_are_shown_as_text():
    snapshot = Deliverable.model_validate(_snapshot(b"a,b\n1,2\n", "text/csv"))
    text, _ = describe_deliverable(snapshot, max_bytes=100, media_max_bytes=1000,
                                   nonce="n0")
    assert "a,b\n1,2" in text
    assert "text/csv" in text
    assert "ab" * 32 in text


def test_non_visual_binary_snapshots_are_described_not_decoded():
    snapshot = Deliverable.model_validate(_snapshot(b"PK\x03\x04rest", "application/zip"))
    text, _ = describe_deliverable(snapshot, max_bytes=100, media_max_bytes=1000,
                                   nonce="n0")
    assert "application/zip" in text
    assert "binary content omitted" in text


def test_a_snapshot_too_large_to_inline_says_so():
    snapshot = Deliverable.model_validate(_snapshot(None, "application/pdf"))
    text, _ = describe_deliverable(snapshot, max_bytes=100, media_max_bytes=1000,
                                   nonce="n0")
    assert "not included" in text


async def test_with_a_snapshot_the_agents_link_is_never_fetched(monkeypatch):
    async def must_not_fetch(*args, **kwargs):
        pytest.fail("the Agent's link must not be fetched when a snapshot is present")

    monkeypatch.setattr(nodes, "fetch_result_content", must_not_fetch)
    req = _request(deliverable=_snapshot(b"total,42\n", "text/csv"))

    out = await nodes.gather_evidence({"request": req}, max_bytes=1000, timeout=1,
                                      media_max_bytes=1000)

    assert "total,42" in out["evidence"]
    assert "frozen" in out["evidence"].lower()


async def test_without_a_snapshot_a_legacy_file_task_still_falls_back_to_the_link(monkeypatch):
    async def fake_fetch(url, *, max_bytes, timeout):
        return "fetched " + url

    monkeypatch.setattr(nodes, "fetch_result_content", fake_fetch)

    out = await nodes.gather_evidence({"request": _request()}, max_bytes=1000, timeout=1,
                                      media_max_bytes=1000)

    assert "fetched https://agent.example/out.csv" in out["evidence"]
