from typing import TypedDict

from app.schemas import ArbitrationRequest


class ArbitrationState(TypedDict, total=False):
    request: ArbitrationRequest
    evidence: str          # gathered: payload + fetched FILE content + schema-check result
    evidence_media: list[dict]  # image/PDF snapshot as content blocks, sent with the evidence
    evidence_nonce: str    # per-run secret in the untrusted-evidence markers
    deliberation: str      # the model's reasoning over criteria vs evidence
    category: str          # FULFILLED | PARTIALLY_FULFILLED | NOT_FULFILLED
    rationale: str
