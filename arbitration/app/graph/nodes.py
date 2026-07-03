from langchain_core.messages import HumanMessage, SystemMessage

from app.graph.state import ArbitrationState
from app.graph.tools import fetch_result_content, validate_against_schema
from app.schemas import RulingResult

CATEGORIES = {"FULFILLED", "PARTIALLY_FULFILLED", "NOT_FULFILLED"}

_SYSTEM = (
    "You are a neutral dispute arbitrator for a task marketplace. "
    "You are given the TASK the client submitted, the declared acceptance criteria, and the "
    "agent's output. Judge whether the output actually addresses THIS task AND satisfies the "
    "acceptance criteria. Being well-formed or schema-valid is NOT enough: an output that does "
    "not correspond to the submitted task — for example a summary of a different article than the "
    "one the client provided — does NOT fulfil the task, even if it is a fluent, valid summary. "
    "You decide ONLY a category and a short rationale. You never discuss money, payment, or "
    "refunds — settlement is computed elsewhere. Categories: FULFILLED (addresses the task and "
    "meets the criteria), PARTIALLY_FULFILLED (addresses the task but misses some criteria), "
    "NOT_FULFILLED (does not address the submitted task, or fails the criteria)."
)


async def gather_evidence(state: ArbitrationState, *, max_bytes: int, timeout: float) -> dict:
    req = state["request"]
    parts: list[str] = [
        f"OUTPUT FORMAT: {req.format}",
        f"CLIENT COMPLAINT CATEGORY: {req.reason_category}",
    ]
    if req.task_description:
        parts.append(f"TASK (what the client asked the agent to do):\n{req.task_description}")
    if req.acceptance_criteria:
        parts.append(f"ACCEPTANCE CRITERIA:\n{req.acceptance_criteria}")
    if req.result_payload_json:
        parts.append(f"AGENT OUTPUT (inline):\n{req.result_payload_json}")
        check = validate_against_schema(req.result_payload_json, req.schema_)
        parts.append(f"SCHEMA CHECK: {check}")
    if req.format == "FILE" and req.result_url:
        try:
            fetched = await fetch_result_content(
                req.result_url, max_bytes=max_bytes, timeout=timeout
            )
            parts.append(f"AGENT OUTPUT (fetched file):\n{fetched}")
        except Exception as e:  # noqa: BLE001 - record any fetch failure; let deliberation weigh it
            parts.append(f"AGENT OUTPUT (file): could not retrieve — {e}")
    return {"evidence": "\n\n".join(parts)}


async def deliberate(state: ArbitrationState, *, llm) -> dict:
    msg = await llm.ainvoke([
        SystemMessage(content=_SYSTEM),
        HumanMessage(content=f"Evidence:\n{state['evidence']}\n\n"
                             "Reason step by step about whether the criteria are met. "
                             "Do not state a final category yet."),
    ])
    return {"deliberation": msg.content}


async def classify(state: ArbitrationState, *, llm) -> dict:
    structured = llm.with_structured_output(RulingResult)
    result: RulingResult = await structured.ainvoke([
        SystemMessage(content=_SYSTEM),
        HumanMessage(content=f"Evidence:\n{state['evidence']}\n\nDeliberation:\n"
                             f"{state.get('deliberation', '')}\n\n"
                             "Return the final category and a one-paragraph rationale."),
    ])
    category = result.category if result.category in CATEGORIES else "NOT_FULFILLED"
    return {"category": category, "rationale": result.rationale}


async def critique(state: ArbitrationState, *, llm) -> dict:
    # One bounded consistency pass: re-affirm or correct the category given the rationale.
    structured = llm.with_structured_output(RulingResult)
    result: RulingResult = await structured.ainvoke([
        SystemMessage(content=_SYSTEM),
        HumanMessage(
            content=(
                f"Proposed category: {state['category']}\n"
                f"Rationale: {state['rationale']}\n"
                f"Evidence:\n{state['evidence']}\n\n"
                "If the category is inconsistent with the rationale and evidence, correct it. "
                "Return the final category and rationale."
            )
        ),
    ])
    category = result.category if result.category in CATEGORIES else state["category"]
    return {"category": category, "rationale": result.rationale}
