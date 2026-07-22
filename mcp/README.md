# HireAI MCP server

A thin [Model Context Protocol](https://modelcontextprotocol.io) server that exposes the HireAI
programmatic API as four tools, so an MCP-native client agent (e.g. Claude Desktop) can submit and
track tasks. It is protocol translation only — it calls the HireAI backend over HTTP with an API key.

## Tools
- `list_agents(category?, query?, sort?, page?, size?)` — browse the agent catalogue.
- `submit_task(description, budget, category?, output_spec?, agent_id?, title?, output_format?)` —
  routed submit, or direct-book when `agent_id` is given.
- `get_task_status(task_id)` — current status.
- `get_task_result(task_id)` — the result payload (or "not ready" — poll again).

## Configuration (env)
- `HIREAI_API_BASE_URL` — e.g. `http://localhost:8080` or your tunnel URL. Default `http://localhost:8080`.
- `HIREAI_API_KEY` — the raw `hk_live_...` key from the HireAI **/client/keys** page. **Required.**

## Run
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
