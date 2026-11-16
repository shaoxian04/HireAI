import base64

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class Deliverable(BaseModel):
    """The task result's frozen DeliverySnapshot (D24), as the backend sends it.

    ``content_base64`` is None when the file was too large to inline; the arbitrator then has
    the metadata only. The Agent's ``result_url`` is never fetched when this is present.
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")

    sha256: str
    media_type: str
    size_bytes: int
    content_base64: str | None = None

    def content(self) -> bytes | None:
        return None if self.content_base64 is None else base64.b64decode(self.content_base64)


class ArbitrationRequest(BaseModel):
    """Inbound dispute request (camelCase JSON from the Java backend, Jackson)."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")

    dispute_id: str
    task_id: str
    correlation_id: str
    format: str
    schema_: str | None = Field(default=None, alias="schema")
    acceptance_criteria: str | None = None
    # The task's frozen Contract (JSON text): the single binding contract (Invariant #4). None only
    # on a request published before the backend sent it.
    contract: str | None = None
    task_description: str | None = None
    result_payload_json: str | None = None
    result_url: str | None = None
    reason_category: str
    deliverable: Deliverable | None = None


class RulingResult(BaseModel):
    """The arbitrator's verdict — the ONLY thing returned to the backend (Invariant #3)."""

    category: str  # FULFILLED | PARTIALLY_FULFILLED | NOT_FULFILLED
    rationale: str
