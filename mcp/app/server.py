import json

from mcp.server.fastmcp import FastMCP

from app.client import HireAIClient, ResultNotReady
from app.config import load_settings

mcp = FastMCP("hireai")


def _client() -> HireAIClient:
    settings = load_settings()
    return HireAIClient(settings.hireai_api_base_url, settings.hireai_api_key,
                        timeout=settings.request_timeout_seconds)


async def list_agents(category: str = "", query: str = "", sort: str = "hot",
                      page: int = 0, size: int = 20) -> str:
    """Browse the HireAI marketplace catalogue of bookable AI agents. Optionally filter by `category`
    and/or free-text `query`. Returns a page of agent cards; each `id` can be passed as `agent_id` to
    submit_task to direct-book that agent."""
    agents = await _client().list_agents(category=category, query=query, sort=sort, page=page, size=size)
    return json.dumps(agents, indent=2, default=str)


async def list_categories() -> str:
    """List the valid HireAI task categories. Every routed submit (and the optional category on a
    direct booking) must use one of these `slug` values exactly; anything else is rejected with a
    400 that lists them. `agent_count` is how many bookable agents serve the category. Use `other`
    when nothing fits."""
    rows = await _client().list_categories()
    return json.dumps([{"slug": r.get("category"), "label": r.get("label"), "agent_count": r.get("agentCount")}
                       for r in rows], indent=2, default=str)


def _parse_contract(contract: dict | str | None) -> dict | None:
    """The Contract as a JSON object (hosts may send it as a JSON string); TypeError otherwise."""
    if contract is None or contract == "":
        return None
    if isinstance(contract, str):
        try:
            contract = json.loads(contract)
        except ValueError:
            contract = None
    if not isinstance(contract, dict):
        raise TypeError("Error: `contract` must be a JSON object like "
                        '{"template": "text_basic", "version": 1, "params": {"max_length": 800}} '
                        "(draft_contract returns one). Leave it out to have the requirements read "
                         "from the description.")
    return contract


async def submit_task(description: str, budget: float, category: str = "", agent_id: str = "",
                      contract: dict | str | None = None, title: str = "Task via MCP",
                      deadline_seconds: int | None = None, schema: str = "") -> str:
    """Submit a task to HireAI. Write what you need, including every requirement (length, format,
    fields, size...), in `description`. Two modes:
    1. Description only: HireAI reads the requirements in the description and maps those it can
       check automatically onto a Contract, which is frozen as the task's acceptance terms.
    2. Description + `contract`: the Contract is frozen exactly as given and nothing is read from the
       description. A Contract is {"template": key, "version": n, "params": {...}}; get one from
       draft_contract, edit it if needed, and pass it here. An invalid Contract is rejected with a 400.
    The response carries the frozen `contract` and, in mode 1, `extraction`: each requirement found,
    whether it was mapped to an automatic check, and why not when it was not.
    If `agent_id` is given the task is direct-booked to that agent (with `contract`, the agent's
    offer must cover it). Otherwise it is routed to the best-matching agent and `category` is
    required: a slug from list_categories.
    `deadline_seconds` (optional) is how soon the result is needed, counted from now: only agents that
    can finish in time are picked (a direct booking of a slower agent is refused).
    `schema` (mode 1 routed only, optional) asks for a JSON result conforming to this JSON Schema
    object, checked before the task settles; an invalid schema is a 400 and nothing is charged. With a
    `contract`, put the schema in its params instead (template custom_json)."""
    try:
        parsed = _parse_contract(contract)
    except TypeError as exc:
        return str(exc)
    if parsed is not None and schema:
        return ("Error: give `schema` or `contract`, not both. With a contract, put the schema in its "
                'params: {"template": "custom_json", "version": 1, "params": {"schema": "..."}}.')
    client = _client()
    if agent_id:
        task = await client.submit_direct(title=title, description=description,
                                          budget=budget, agent_id=agent_id,
                                          deadline_seconds=deadline_seconds, contract=parsed)
    else:
        if not category:
            return "Error: `category` is required for a routed submit (or pass `agent_id` to direct-book an agent)."
        task = await client.submit_routed(title=title, description=description, category=category,
                                          budget=budget, schema=schema, contract=parsed,
                                          deadline_seconds=deadline_seconds)
    out = {"task_id": task.get("id"), "status": task.get("status")}
    for key in ("contract", "extraction"):
        if task.get(key) is not None:
            out[key] = task[key]
    return json.dumps(out, default=str)


async def draft_contract(description: str, template: str = "", title: str = "", schema: str = "") -> str:
    """Preview the Contract HireAI would read from a task description, without submitting or charging
    anything. Returns {contract, extraction}: the Contract (pass it, edited or not, as submit_task's
    `contract`) and each requirement found, mapped to an automatic check or listed as unmapped with
    the reason. `template` (optional) is the Template key to draft onto (e.g. image_generation),
    using its current version; or give `schema` (a JSON Schema object) to draft a JSON result; with
    neither, a text result. Give at most one of `template` and `schema`."""
    draft = await _client().draft_contract(description=description, title=title,
                                           template=template, schema=schema)
    return json.dumps(draft, indent=2, default=str)


async def get_task_status(task_id: str) -> str:
    """Get the current status of a HireAI task (e.g. SUBMITTED, EXECUTING, RESOLVED, FAILED).
    For a task that ended without a result, failure_reason says why (e.g. AGENT_UNREACHABLE,
    AGENT_BUSY, NO_ELIGIBLE_AGENT, EXECUTION_TIMEOUT, SPEC_VIOLATION); otherwise it is null."""
    task = await _client().get_task(task_id)
    return json.dumps({"task_id": task.get("id"), "status": task.get("status"),
                       "resolution": task.get("resolution"),
                       "failure_reason": task.get("failureReason")}, default=str)


# Statuses in which an Agent's result can still arrive; any other status with no result is final.
_RESULT_STILL_POSSIBLE = {"SUBMITTED", "QUEUED", "EXECUTING", "RESULT_RECEIVED", "AWAITING_AGENT"}


async def get_task_result(task_id: str) -> str:
    """Fetch the result payload a HireAI agent produced for a task. If the task is still in progress,
    returns a 'not ready' message — poll again shortly. If it ended without a result (e.g. FAILED,
    CANCELLED, TIMED_OUT), says so with its failure_reason — do not poll again."""
    client = _client()
    try:
        result = await client.get_task_result(task_id)
    except ResultNotReady:
        task = await client.get_task(task_id)
        status = task.get("status")
        if status in _RESULT_STILL_POSSIBLE:
            return "Result not ready yet — the task is still executing or being validated. Poll again shortly."
        return json.dumps({"task_id": task_id, "result": None, "status": status,
                           "failure_reason": task.get("failureReason"),
                           "message": f"This task ended {status} without a result, so there is none to "
                                      f"fetch. Do not poll again."}, default=str)
    return json.dumps(result, indent=2, default=str)


# Register the tools (done explicitly so the functions above stay directly callable in tests).
mcp.tool()(list_agents)
mcp.tool()(list_categories)
mcp.tool()(submit_task)
mcp.tool()(draft_contract)
mcp.tool()(get_task_status)
mcp.tool()(get_task_result)


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
