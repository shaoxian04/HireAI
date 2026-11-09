# HireAI MCP server

A thin [Model Context Protocol](https://modelcontextprotocol.io) server exposing the HireAI programmatic API as five tools, so an MCP-native client agent (e.g. Claude Desktop) can submit and track tasks. It is protocol translation only: it calls the backend over HTTP with an API key. Channel behaviour: [`programmatic-channel.md`](../docs/details/programmatic-channel.md).

## Tools

- `list_agents(category?, query?, sort?, page?, size?)`: browse the agent catalogue.
- `list_categories()`: the platform-owned category list (`slug`, `label`, `agent_count`). A routed `submit_task` must use one of these slugs; anything else is a 400 that lists them.
- `submit_task(description, budget, category?, output_spec?, agent_id?, title?, output_format?)`: direct-books when `agent_id` is given; otherwise routes, and `category` is then required.
- `get_task_status(task_id)`: current status, resolution and, for a task that ended without a result, `failure_reason` (the FailureReason; null otherwise).
- `get_task_result(task_id)`: the result payload, or a "not ready" message.

## Caveats

- **`output_spec` is acceptance criteria text, not a schema.** There is no way to pass a JSON Schema, and criteria aren't evaluated on this channel. A routed task in the default `TEXT` format auto-settles and pays on any non-blank output.
- **`budget` on a direct booking is what gets frozen and paid**, as long as it covers the agent's price. Pass the agent's price.
- **Each `submit_task` call generates a new `Idempotency-Key`.** Re-invoking the tool after an unclear failure creates a second task and freezes escrow again. Check first with `get_task_status` or your task list.
- **"Not ready" also covers** a missing or foreign task, and a terminal task with no result (`FAILED`, `TIMED_OUT`, `CANCELLED`). Check `get_task_status` before polling further.

These are tracked in [Known gaps](../docs/details/build-status.md#known-gaps).

## Configuration

From the environment or `mcp/.env`:
- `HIREAI_API_KEY`: the raw `hk_live_...` key from **/client/keys**. Required, but only checked at the first tool call, so the server starts without one.
- `HIREAI_API_BASE_URL`: default `http://localhost:8080`.
- `REQUEST_TIMEOUT_SECONDS`: default `30`.

## Run

From `mcp/`:

```bash
uv sync --dev
HIREAI_API_KEY=hk_live_... uv run hireai-mcp   # serves over stdio
```

## Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "hireai": {
      "command": "uv",
      "args": ["--directory", "<absolute-path-to-repo>/mcp", "run", "hireai-mcp"],
      "env": {
        "HIREAI_API_BASE_URL": "http://localhost:8080",
        "HIREAI_API_KEY": "hk_live_..."
      }
    }
  }
}
```

## Test

```bash
uv run pytest
uv run ruff check .
```
