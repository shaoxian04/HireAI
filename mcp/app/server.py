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


async def submit_task(description: str, budget: float, category: str = "", output_spec: str = "",
                      agent_id: str = "", title: str = "Task via MCP", output_format: str = "TEXT") -> str:
    """Submit a task to HireAI. If `agent_id` is given the task is direct-booked to that agent (it
    inherits the agent's output spec — only description, budget, agent_id are needed). Otherwise the
    task is routed to the best-matching agent and `category` is required (`output_spec` is the
    acceptance criteria; `output_format` is TEXT, JSON, or FILE)."""
    client = _client()
    if agent_id:
        task = await client.submit_direct(title=title, description=description,
                                          budget=budget, agent_id=agent_id)
    else:
        if not category:
            return "Error: `category` is required for a routed submit (or pass `agent_id` to direct-book an agent)."
        task = await client.submit_routed(title=title, description=description, category=category,
                                          budget=budget, output_format=output_format,
                                          acceptance_criteria=output_spec)
    return json.dumps({"task_id": task.get("id"), "status": task.get("status")}, default=str)


async def get_task_status(task_id: str) -> str:
    """Get the current status of a HireAI task (e.g. SUBMITTED, WORKING, RESOLVED, SPEC_VIOLATION)."""
    task = await _client().get_task(task_id)
    return json.dumps({"task_id": task.get("id"), "status": task.get("status"),
                       "resolution": task.get("resolution")}, default=str)


async def get_task_result(task_id: str) -> str:
    """Fetch the result payload a HireAI agent produced for a task. If there is no result yet, returns
    a 'not ready' message — poll again shortly."""
    try:
        result = await _client().get_task_result(task_id)
    except ResultNotReady:
        return "Result not ready yet — the task is still executing or being validated. Poll again shortly."
    return json.dumps(result, indent=2, default=str)


# Register the tools (done explicitly so the functions above stay directly callable in tests).
mcp.tool()(list_agents)
mcp.tool()(submit_task)
mcp.tool()(get_task_status)
mcp.tool()(get_task_result)


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
