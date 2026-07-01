from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ArbitrationRequest(BaseModel):
    """Inbound dispute request (camelCase JSON from the Java backend, Jackson)."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")

    dispute_id: str
    task_id: str
    correlation_id: str
    format: str
    schema_: str | None = Field(default=None, alias="schema")
    acceptance_criteria: str | None = None
    task_description: str | None = None
    result_payload_json: str | None = None
    result_url: str | None = None
    reason_category: str


class RulingResult(BaseModel):
    """The arbitrator's verdict — the ONLY thing returned to the backend (Invariant #3)."""

    category: str  # FULFILLED | PARTIALLY_FULFILLED | NOT_FULFILLED
    rationale: str
