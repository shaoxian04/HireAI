# Phase 5 — MCP server facade + OpenAPI — Design

> **Status:** Design approved 2026-07-22 (brainstorm). Not yet planned/built.
> **Owner:** Shaoxian · **Feeds:** the implementation plan (`docs/superpowers/plans/`), then the build.
> **Scope altitude:** design & requirements alignment — *what* we add and *how it fits*, not class-level detail.

This is the final slice of the programmatic ("agents hiring agents") channel. Phases 3 and 4 gave the
API-key REST spine (`V25`) and signed push webhooks + deterministic auto-settlement (`V26`). Phase 5 adds
the last ergonomic layer: a thin **MCP (Model Context Protocol) server** so MCP-native client agents can
reach the marketplace by calling tools, plus an **OpenAPI document** describing the programmatic REST
surface. It supersedes the roadmap sketch in `docs/programmatic-task-submission.md` §6.8 / §8.8 where they
differ.

## 1. Goal & success criteria

**Done means all three:**
1. A working **MCP server** exposing four tools (`list_agents`, `submit_task`, `get_task_status`,
   `get_task_result`) that call the real backend and round-trip a task end to end.
2. **Tests green** — Python unit tests for the tool/REST mapping (`ruff` clean) + backend tests for the two
   backend touches.
3. A rehearsed **live demo**: point **Claude Desktop** at the server over stdio and have it submit and track
   a task on the running stack; a Swagger UI page renders the programmatic API.

## 2. Principles & non-negotiables

- **Protocol translation only.** The MCP server contains *no business logic* — it turns MCP tool calls into
  REST calls and unwraps the `WebResult` envelope. All rules stay in the backend domain layer.
- **Unchanged core, no migration.** Nothing in submit/escrow/routing/validation/settlement changes. No new
  tables, no schema change. The only backend edits are (a) opening the read-only catalogue to API-key auth
  and (b) adding springdoc.
- **All six hard invariants preserved, none newly extended.** Identity is still resolved server-side from
  the API-key principal (Inv #5 reaffirmed — never from tool arguments). The MCP process is a *credential
  holder*: the API key lives only in env, never logged or echoed in tool output.
- **YAGNI.** Local/stdio only; polling only; four tools only.

## 3. Architecture

One new standalone component, plus two small backend touches. The `mcp/` service mirrors `arbitration/`
exactly — a separate `uv` project that talks to the backend over HTTP.

```
Claude Desktop ──spawns via stdio──►  mcp/  (Python, uv)
                                         │  FastMCP: 4 tools
                                         │  thin HireAIClient (httpx)
                                         ▼
                              Authorization: ApiKey <key>     (key from env)
                                         │
                                         ▼
                              HireAI backend  (UNCHANGED core)
                                + GET /api/catalogue/** opened to API-key auth
                                + springdoc OpenAPI / Swagger UI
```

### 3.1 Repo layout (`mcp/`)

```
mcp/
  pyproject.toml          # uv project; deps: mcp, httpx, pydantic; dev: pytest, respx, ruff
  README.md               # how to run + the Claude Desktop config block
  app/
    __init__.py
    server.py             # FastMCP instance; the 4 @mcp.tool handlers; main() over stdio
    client.py             # HireAIClient: httpx wrapper, ApiKey auth, envelope unwrap, error mapping
    config.py             # env loader: HIREAI_API_BASE_URL, HIREAI_API_KEY
  tests/
    test_client.py        # request shaping, auth header, routed-vs-direct, envelope unwrap, error mapping
    test_tools.py         # tool → client wiring, argument passthrough
```

- **Entry point:** `[project.scripts] hireai-mcp = "app.server:main"`; `main()` runs the FastMCP server over
  stdio. Claude Desktop spawns `uv run hireai-mcp`.
- **Config (env only):** `HIREAI_API_BASE_URL` (e.g. `https://<tunnel>` for the live demo, or
  `http://localhost:8080` local) and `HIREAI_API_KEY` (the raw `hk_live_…` key issued from `/client/keys`).
  Missing/blank config fails fast at startup with a clear message. The key is never logged.

### 3.2 The MCP SDK

Official Python MCP SDK (`mcp` package, FastMCP style): `from mcp.server.fastmcp import FastMCP`; tools are
declared with `@mcp.tool()` and typed signatures — the SDK derives the tool JSON schema from the type hints.

## 4. The four tools (contracts)

| Tool | Params | Maps to | Returns |
|---|---|---|---|
| `list_agents` | `category?`, `query?`, `sort="hot"`, `page=0`, `size=20` | `GET /api/catalogue/agents?q=&category=&sort=&page=&size=` | list of agent cards — each with `agent_id`, name, builder, tagline, categories, `price`, rating (avg+count), reputation, request count |
| `submit_task` | `description`, `budget`, `category?`, `output_spec?`, `agent_id?`, `title?` | `agent_id` set → `POST /api/tasks/direct` `{title, description, budget, agentId}`; else → `POST /api/tasks` `{title, description, category, budget, outputSpec}` | `{ task_id, status }` |
| `get_task_status` | `task_id` | `GET /api/tasks/{id}` | `{ status, … }` (the task view) |
| `get_task_result` | `task_id` | `GET /api/tasks/{id}/result` | the result payload, or a clear *"not ready — poll again"* message on `404` |

**Natural chain (the demo flow):** `list_agents` → `submit_task(agent_id=…)` → `get_task_status` (poll) →
`get_task_result`.

**Submit modes.**
- **Direct (demo-friendly, default when `agent_id` given):** pins the chosen agent; the task **inherits the
  agent's registered `output_spec`**, so the caller only needs `description`, `budget`, `agent_id`. This
  avoids asking the model to hand-author a JSON-Schema `output_spec`.
- **Routed (`agent_id` omitted):** the matcher picks an agent; requires `category` **and** `output_spec`
  (the task's own spec is the binding contract, Inv #4).

**Idempotency.** `submit_task` always sends a fresh `Idempotency-Key: <uuid4>` header, so a transport-level
retry of a single tool call can't double-submit / double-freeze escrow. (Deriving a *stable* key from the
payload to dedupe distinct-but-identical calls is a future nicety, out of scope.)

**Return shaping.** Thin. Tools return the backend DTO unwrapped from the `WebResult` envelope as a plain
dict/JSON so the tool output reads cleanly to the model. No re-computation, no enrichment.

## 5. Backend changes (the only two)

### 5.1 Open the catalogue to API-key auth
Add `GET /api/catalogue/**` (read-only, public data, no ownership) to the API-key allow-list in
`SecurityConfig`. `list_agents` is the only tool whose target isn't already reachable by an API key.

- This is a **security-config change** → mandatory security-reviewer pass before merge.
- Per `docs/post-mortem/2026-07-17-api-key-lockout-401-vs-403.md`: place the matcher in the correct
  profile-scoped chain(s), and assert the denied status against the **full app** (expect **401**, not 403).
- Only `GET` under `/api/catalogue/**` is opened; nothing else in the catalogue surface is mutating.

### 5.2 Add springdoc (OpenAPI + Swagger UI)
Add `springdoc-openapi-starter-webmvc-ui` to `hireai-main` (version matched to the project's Spring Boot 3.x
line — confirm at plan time).

- **Scope it:** a single `GroupedOpenApi("programmatic")` covering only `/api/tasks/**`, `/api/keys/**`,
  `/api/webhooks/**`, `/api/catalogue/**`. Admin/internal routes stay out of the published document.
- **Security schemes:** an `OpenAPI` bean declaring **`ApiKey`** (apiKey, in header `Authorization`, value
  format `ApiKey <key>` — documented in the scheme description) and **`bearerAuth`** (JWT), plus API title /
  version / description metadata.
- **Expose the docs:** `permitAll` on `/v3/api-docs/**` and `/swagger-ui/**` (+ `/swagger-ui.html`) so the
  Swagger UI and the OpenAPI JSON are publicly reachable — again added to the correct security chain(s).
- Deliverable: a live Swagger UI page + a versioned OpenAPI JSON at `/v3/api-docs/programmatic`.

## 6. Auth & invariant mapping

| # | Invariant | How Phase 5 honours it |
|---|---|---|
| 1 | Escrow before execution | Unchanged — submit still freezes in the same transaction; MCP just calls the same endpoint |
| 2 | Append-only money/audit | No money-table touch; no new persistence at all |
| 3 | Deterministic money path | Unchanged — settlement is the existing deterministic programmatic path |
| 4 | Output spec is the binding contract | Routed submit carries the task spec; direct inherits the agent spec |
| 5 | Server-side identity | **Reaffirmed** — user resolved from the API-key principal, never from tool args |
| 6 | Signed, HTTPS-only I/O | Inbound stdio adapter; no new outbound I/O. Key is a secret held in env only |

No invariant is *newly* extended (5 and 6 were already extended in Phases 3–4).

## 7. Error handling

REST `WebResult` / `ResultCode` + HTTP status → clean, model-readable tool responses. Tool handlers never
crash the server; every failure becomes a structured message.

| Condition | Tool response |
|---|---|
| `401` (missing/invalid/revoked key) | "invalid or expired API key" |
| `409 SPEND_CAP_EXCEEDED` / `IDEMPOTENCY_CONFLICT` | the `ResultCode` message verbatim |
| `404` on `get_task_result` | *"result not ready yet — keep polling"* (a normal state, not an error) |
| `404` on direct submit (unlisted/missing agent) | "agent not found or not bookable" (the Inv #5 no-leak behaviour) |
| network / timeout | clear transient-error message; server stays up |

## 8. Testing

- **Python (mocked backend via `respx`):** auth header present and correctly formatted; routed-vs-direct
  endpoint selection on `agent_id`; `Idempotency-Key` header present on submit; `WebResult` envelope
  unwrapped; each error mapping in §7; tool→client wiring and argument passthrough. `ruff check` clean.
  (Matches `arbitration/`'s pytest + ruff discipline.)
- **Backend:** a springdoc test that the `programmatic` group renders and includes only the intended paths;
  a security test that `GET /api/catalogue/agents` is reachable with an API key and that the swagger/api-docs
  endpoints are public — denied-status asserted against the full app.
- **Live demo E2E (documented checklist):** stack up (backend + RabbitMQ + a stub agent, reachable per the
  demo runbook); `claude_desktop_config.json` pointed at `mcp/`; run the sample prompt; capture the expected
  tool-call trace and the returned result. MCP Inspector is the non-Claude fallback for manual/CI checks.

## 9. Demo path

Claude Desktop spawns the server over stdio:

```json
{
  "mcpServers": {
    "hireai": {
      "command": "uv",
      "args": ["--directory", "<repo>/mcp", "run", "hireai-mcp"],
      "env": {
        "HIREAI_API_BASE_URL": "https://<tunnel-or-localhost:8080>",
        "HIREAI_API_KEY": "hk_live_…"
      }
    }
  }
}
```

Sample prompt: *"List summarization agents on HireAI, book the top one to summarize this text (budget 50
credits), poll until it's done, and show me the result."* → the model walks
`list_agents → submit_task(agent_id=…) → get_task_status (loop) → get_task_result`.

## 10. Out of scope (YAGNI)

Remote/hosted/OAuth-secured MCP servers; pushing webhooks *into* MCP (polling only — push doesn't fit MCP's
request/response model); official client SDK generation from the OpenAPI doc; per-key scopes & rotation;
agent-to-agent chaining; executor-side MCP/A2A transport; any real-money or hosted-execution concern.

## 11. Delivery

- New branch **`feat/mcp-server`**, independent of the push-webhooks work (PR #24) — Phase 5 depends only on
  the already-merged API-key channel (Phase 3), so it doesn't wait on #24. Branch off the latest `main` (or
  #24's head if #24 hasn't merged yet, to avoid catalogue/SecurityConfig conflicts — decide at plan time).
- No migration. CI must stay green (backend full suite + frontend); add the `mcp/` Python checks to CI in the
  same style as `arbitration/` (or document the run command if CI wiring is deferred — decide at plan time).
- Living-doc updates on completion: `CLAUDE.md` build-status pointer, `docs/details/build-status.md`,
  `docs/details/programmatic-channel.md` (add the MCP facade + OpenAPI), `docs/details/architecture.md`
  (the new service), and mark Phase 5 built in `docs/programmatic-task-submission.md`.

## 12. Open questions (resolve during planning, not blocking)

- **Q-1.** springdoc exact version vs. the project's Spring Boot version — confirm compatibility at plan time.
- **Q-2.** CI: wire `mcp/` (pytest + ruff) into the GitHub Actions matrix now, or defer and just document the
  local run command? (Lean: wire it in, mirroring `arbitration/`.)
- **Q-3.** Should the routed `submit_task` be included in the demo at all, or is direct-book sufficient for the
  FYP story? (Both are built; this is only about what the rehearsed demo exercises.)
