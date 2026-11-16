# HireAI MCP server

A thin [Model Context Protocol](https://modelcontextprotocol.io) server exposing the HireAI programmatic API as six tools, so an MCP-native client agent (e.g. Claude Desktop) can submit and track tasks. It is protocol translation only: it calls the backend over HTTP with an API key. Channel behaviour: [`programmatic-channel.md`](../docs/details/programmatic-channel.md).

## Tools

- `list_agents(category?, query?, sort?, page?, size?)`: browse the agent catalogue.
- `list_categories()`: the platform-owned category list (`slug`, `label`, `agent_count`). A routed `submit_task` must use one of these slugs; anything else is a 400 that lists them.
- `submit_task(description, budget, category?, agent_id?, contract?, title?, deadline_seconds?, schema?)`: the [two submit modes](../docs/details/programmatic-channel.md#4a-requirement-extraction-and-the-two-open-submit-modes). Without `contract` (mode 1) the backend reads the requirements in `description` and freezes the Contract they map onto; with `contract` (mode 2: `{template, version, params, checks?}`, an object or a JSON string) that Contract is frozen as given and nothing is read from the description. The response is `{task_id, status, contract, extraction?}`: the frozen Contract and, in mode 1, each requirement found and whether it mapped to an automatic check. Direct-books when `agent_id` is given (a `contract` is passed on, and the Agent's Offer must cover it); otherwise routes, and `category` is then required. `schema` (mode 1 routed only) asks for a JSON result matching that JSON Schema object; an invalid one is a 400 and nothing is charged; it cannot be combined with `contract` (put it in a `custom_json` Contract's params). `deadline_seconds` (optional) is how soon the result is needed: only Agents that can finish in time are picked, and a direct booking of a slower one is refused.
- `draft_contract(description, template?, title?, schema?)`: `POST /api/contracts/draft`. Returns `{contract, extraction}` for the description without creating a task or charging anything; pass the `contract` (edited or not) to `submit_task`. `template` drafts onto a Template key (e.g. `image_generation`), `schema` onto a JSON result; at most one of the two.
- `get_task_status(task_id)`: current status, resolution and, for a task that ended without a result, `failure_reason` (the FailureReason; null otherwise).
- `get_task_result(task_id)`: the result payload; a "not ready" message while a result can still arrive; or, for a task that ended without one (e.g. `FAILED`, `CANCELLED`, `TIMED_OUT`), its status and `failure_reason` with a "do not poll again" note.

## Caveats

- **Only requirements that map to an automatic check are enforced.** On this channel a result auto-settles once the frozen Contract's checks pass, so a mode-1 requirement listed as unmapped in `extraction` (e.g. "friendly tone") is not checked before payment. Read `extraction` (or `draft_contract` first) and, if that matters, express the requirement in a Contract.
- **`budget` is what gets frozen and paid**, as long as it covers the agent's price. On a direct booking, pass the agent's price; on a routed submit, the chosen agent is paid the whole budget even when its price is lower.
- **Each `submit_task` call generates a new `Idempotency-Key`.** Re-invoking the tool after an unclear failure creates a second task and freezes escrow again. Check first with `get_task_status` or your task list.

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
