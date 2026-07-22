# Phase 5 — MCP Server Facade + OpenAPI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a standalone Python MCP server that lets MCP-native client agents (e.g. Claude Desktop) submit and track HireAI tasks by calling tools, plus a springdoc-generated OpenAPI document / Swagger UI for the programmatic REST surface.

**Architecture:** A new `mcp/` `uv` project — mirroring the existing `arbitration/` service — exposes four MCP tools (`list_agents`, `submit_task`, `get_task_status`, `get_task_result`) via the official Python MCP SDK (FastMCP). Each tool calls the HireAI backend over HTTP through a thin `HireAIClient`, authenticating with an API key read from the environment. The server contains **no business logic** — it is protocol translation only. Two small backend touches support it: opening the read-only catalogue to API-key auth (so `list_agents` works) and adding springdoc for the OpenAPI doc + Swagger UI. No database migration; the submit/escrow/routing/validation/settlement core is untouched.

**Tech Stack:** Python 3.12 + `uv`, `mcp` (Python MCP SDK, FastMCP), `httpx` (async), `pydantic` / `pydantic-settings`, `pytest` + `pytest-asyncio` + `respx` + `ruff`. Backend: Spring Boot 3.3.5, springdoc-openapi 2.6.0.

## Global Constraints

- **Design spec:** `docs/superpowers/specs/2026-07-22-mcp-server-design.md` — this plan implements it.
- **Branch:** `feat/mcp-server` (already created; the spec is committed there as `f95811d`). All commits land here.
- **Protocol translation only.** The `mcp/` service holds no business logic; every rule stays in the backend domain layer.
- **No DB migration. No change to the submit/escrow/routing/validation/settlement core.** The only backend edits are the catalogue allow-list line and the springdoc addition.
- **All six hard invariants preserved.** Identity is resolved server-side by the backend from the API-key principal — never from tool arguments. The MCP process is a credential holder: `HIREAI_API_KEY` comes from env only and is **never logged or echoed** in tool output.
- **Mirror `arbitration/` conventions:** `uv` project, `app/` package + `tests/`, `pydantic-settings` config, async `httpx`, `pytest` (`asyncio_mode = "auto"`) + `respx` + `ruff`.
- **springdoc version 2.6.0** is pinned explicitly (it is NOT managed by the Spring Boot BOM), following the repo convention: property + `dependencyManagement` in the parent `backend/pom.xml`, version-free in the child pom.
- **Security-config changes must be asserted against the full app** (a `@SpringBootTest(RANDOM_PORT)` + Testcontainers test with **no** `@ActiveProfiles("test")`), per `docs/post-mortem/2026-07-17-api-key-lockout-401-vs-403.md`. A denied-but-authenticated request renders **401** (there is no `accessDeniedHandler`). These tests auto-skip without Docker — **run them with a Docker daemon available** to see real RED/GREEN.
- **Commit style:** `<type>: <description>` (feat/fix/docs/test/chore). No attribution footer (disabled globally for this repo).
- **`WebResult` envelope:** every backend endpoint returns `{success, code, message, data}`. On success (`success: true`, HTTP 200) the payload is in `data`. On failure the HTTP status is set by `GlobalExceptionConfiguration`: `NOT_FOUND → 404`, `IDEMPOTENCY_CONFLICT`/`SPEND_CAP_EXCEEDED`/`DOMAIN_RULE_VIOLATION`/`INSUFFICIENT_BALANCE → 409`, `VALIDATION_ERROR → 400`, auth failure/lockout → 401.

---

### Task 1: Scaffold the `mcp/` project + config

**Files:**
- Create: `mcp/pyproject.toml`
- Create: `mcp/app/__init__.py` (empty)
- Create: `mcp/tests/__init__.py` (empty)
- Create: `mcp/app/config.py`
- Create: `mcp/.gitignore`
- Test: `mcp/tests/test_config.py`

**Interfaces:**
- Produces: `app.config.Settings` (pydantic-settings) with fields `hireai_api_base_url: str` (default `"http://localhost:8080"`), `hireai_api_key: str` (default `""`), `request_timeout_seconds: float` (default `30.0`); and `app.config.load_settings() -> Settings` which raises `RuntimeError` if `hireai_api_key` is blank.

- [ ] **Step 1: Create the project files**

`mcp/pyproject.toml`:
```toml
[project]
name = "hireai-mcp"
version = "0.1.0"
description = "HireAI MCP server facade (Model Context Protocol) over the programmatic REST API"
requires-python = ">=3.12"
dependencies = [
    "mcp>=1.2",
    "httpx>=0.27",
    "pydantic>=2.7",
    "pydantic-settings>=2.3",
]

[dependency-groups]
dev = [
    "pytest>=8.2",
    "pytest-asyncio>=0.23",
    "respx>=0.21",
    "ruff>=0.5",
]

[project.scripts]
hireai-mcp = "app.server:main"

[tool.pytest.ini_options]
asyncio_mode = "auto"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["app"]
```

`mcp/app/__init__.py`: empty file.
`mcp/tests/__init__.py`: empty file.

`mcp/.gitignore`:
```gitignore
.venv/
__pycache__/
*.pyc
.env
.pytest_cache/
.ruff_cache/
```

`mcp/app/config.py`:
```python
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    hireai_api_base_url: str = "http://localhost:8080"
    hireai_api_key: str = ""
    request_timeout_seconds: float = 30.0


def load_settings() -> Settings:
    settings = Settings()
    if not settings.hireai_api_key:
        raise RuntimeError(
            "HIREAI_API_KEY is required. Set it in the environment or mcp/.env "
            "(the raw hk_live_... key issued from the HireAI /client/keys page)."
        )
    return settings
```

- [ ] **Step 2: Write the failing test**

`mcp/tests/test_config.py`:
```python
import pytest

from app.config import Settings, load_settings


def test_defaults_when_only_key_given():
    s = Settings(_env_file=None, hireai_api_key="hk_live_test")
    assert s.hireai_api_base_url == "http://localhost:8080"
    assert s.request_timeout_seconds == 30.0


def test_reads_from_env(monkeypatch):
    monkeypatch.setenv("HIREAI_API_BASE_URL", "https://api.example.com")
    monkeypatch.setenv("HIREAI_API_KEY", "hk_live_abc")
    s = Settings(_env_file=None)
    assert s.hireai_api_base_url == "https://api.example.com"
    assert s.hireai_api_key == "hk_live_abc"


def test_load_settings_requires_key(monkeypatch):
    monkeypatch.setenv("HIREAI_API_KEY", "")
    with pytest.raises(RuntimeError, match="HIREAI_API_KEY is required"):
        load_settings()
```

- [ ] **Step 3: Sync deps and run the test to verify it passes**

Run: `cd mcp && uv sync --dev && uv run pytest tests/test_config.py -v`
Expected: PASS (3 passed). `uv sync` resolves `mcp`, `httpx`, `pydantic`, `pydantic-settings`, and the dev group.

- [ ] **Step 4: Lint**

Run: `cd mcp && uv run ruff check .`
Expected: `All checks passed!`

- [ ] **Step 5: Commit**

```bash
git add mcp/pyproject.toml mcp/app mcp/tests mcp/.gitignore
git commit -m "feat(mcp): scaffold Python MCP project + env config"
```

---

### Task 2: `HireAIClient` core — request, envelope unwrap, error mapping, auth

**Files:**
- Create: `mcp/app/client.py`
- Test: `mcp/tests/test_client.py`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `app.client.HireAIError(Exception)` with attributes `code: str | None`, `status: int | None`.
  - `app.client.ResultNotReady(Exception)` (marker).
  - `app.client.HireAIClient(base_url: str, api_key: str, *, timeout: float = 30.0)` with an async method `_request(method: str, path: str, *, params: dict | None = None, json: dict | None = None, extra_headers: dict | None = None) -> Any` that sends `Authorization: ApiKey <key>`, unwraps the `WebResult` envelope, returns `data` on success, and raises `HireAIError` otherwise. (Endpoint methods are added in Task 3.)

- [ ] **Step 1: Write the failing test**

`mcp/tests/test_client.py`:
```python
import httpx
import pytest
import respx

from app.client import HireAIClient, HireAIError

BASE = "http://backend.test"


def make_client():
    return HireAIClient(BASE, "hk_live_testkey", timeout=5)


@respx.mock
async def test_sends_apikey_authorization_header():
    route = respx.get(f"{BASE}/api/ping").mock(return_value=httpx.Response(
        200, json={"success": True, "code": "OK", "message": None, "data": {"ok": 1}}))
    data = await make_client()._request("GET", "/api/ping")
    assert data == {"ok": 1}
    assert route.calls.last.request.headers["authorization"] == "ApiKey hk_live_testkey"


@respx.mock
async def test_unwraps_success_envelope_returns_data():
    respx.get(f"{BASE}/api/x").mock(return_value=httpx.Response(
        200, json={"success": True, "code": "OK", "message": None, "data": [1, 2]}))
    assert await make_client()._request("GET", "/api/x") == [1, 2]


@respx.mock
async def test_error_envelope_raises_with_code_and_status():
    respx.get(f"{BASE}/api/x").mock(return_value=httpx.Response(
        409, json={"success": False, "code": "SPEND_CAP_EXCEEDED", "message": "cap hit", "data": None}))
    with pytest.raises(HireAIError) as ei:
        await make_client()._request("GET", "/api/x")
    assert ei.value.code == "SPEND_CAP_EXCEEDED"
    assert ei.value.status == 409
    assert "cap hit" in str(ei.value)


@respx.mock
async def test_empty_body_401_raises_hireai_error():
    respx.get(f"{BASE}/api/x").mock(return_value=httpx.Response(401))
    with pytest.raises(HireAIError) as ei:
        await make_client()._request("GET", "/api/x")
    assert ei.value.status == 401


@respx.mock
async def test_network_error_raises_hireai_error():
    respx.get(f"{BASE}/api/x").mock(side_effect=httpx.ConnectError("boom"))
    with pytest.raises(HireAIError, match="Could not reach"):
        await make_client()._request("GET", "/api/x")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mcp && uv run pytest tests/test_client.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.client'`.

- [ ] **Step 3: Write minimal implementation**

`mcp/app/client.py`:
```python
from typing import Any

import httpx


class HireAIError(Exception):
    """A structured error from the HireAI backend (a non-success WebResult or a transport failure)."""

    def __init__(self, message: str, *, code: str | None = None, status: int | None = None):
        super().__init__(message)
        self.code = code
        self.status = status


class ResultNotReady(Exception):
    """The task has no result yet (backend returned NOT_FOUND) — the caller should keep polling."""


class HireAIClient:
    """Thin async REST client for the HireAI programmatic API. Protocol translation only: sends the
    ApiKey credential, unwraps the WebResult envelope, maps errors. No business logic."""

    def __init__(self, base_url: str, api_key: str, *, timeout: float = 30.0):
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._timeout = timeout

    def _auth_headers(self) -> dict[str, str]:
        return {"Authorization": f"ApiKey {self._api_key}"}

    async def _request(self, method: str, path: str, *, params: dict | None = None,
                       json: dict | None = None, extra_headers: dict | None = None) -> Any:
        headers = self._auth_headers()
        if extra_headers:
            headers.update(extra_headers)
        url = f"{self._base_url}{path}"
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            try:
                resp = await client.request(method, url, params=params, json=json, headers=headers)
            except httpx.HTTPError as exc:
                raise HireAIError(f"Could not reach HireAI backend: {exc}") from exc
        return self._unwrap(resp)

    @staticmethod
    def _unwrap(resp: httpx.Response) -> Any:
        # Every HireAI endpoint returns the WebResult envelope {success, code, message, data}. A 401
        # from the security entrypoint may have an empty body — handle that too.
        try:
            body = resp.json()
        except ValueError:
            body = None
        if not isinstance(body, dict) or "success" not in body:
            raise HireAIError(
                f"HireAI backend returned HTTP {resp.status_code} with no result envelope",
                status=resp.status_code)
        if body.get("success"):
            return body.get("data")
        raise HireAIError(body.get("message") or "HireAI request failed",
                          code=body.get("code"), status=resp.status_code)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mcp && uv run pytest tests/test_client.py -v`
Expected: PASS (5 passed).

- [ ] **Step 5: Lint + commit**

```bash
cd mcp && uv run ruff check .
git add mcp/app/client.py mcp/tests/test_client.py
git commit -m "feat(mcp): HireAIClient request core with WebResult unwrap + error mapping"
```

---

### Task 3: `HireAIClient` endpoint methods

**Files:**
- Modify: `mcp/app/client.py` (add methods to `HireAIClient`)
- Test: `mcp/tests/test_client_methods.py`

**Interfaces:**
- Consumes: `HireAIClient._request`, `HireAIError`, `ResultNotReady` (Task 2).
- Produces (all async):
  - `list_agents(*, category="", query="", sort="hot", page=0, size=20) -> list[dict]` → `GET /api/catalogue/agents` with params `{q, category, sort, page, size}`.
  - `submit_routed(*, title, description, category, budget, output_format, schema="", acceptance_criteria="") -> dict` → `POST /api/tasks` with an `Idempotency-Key` header.
  - `submit_direct(*, title, description, budget, agent_id) -> dict` → `POST /api/tasks/direct` with an `Idempotency-Key` header.
  - `get_task(task_id) -> dict` → `GET /api/tasks/{task_id}`.
  - `get_task_result(task_id) -> dict` → `GET /api/tasks/{task_id}/result`; raises `ResultNotReady` when the backend answers `NOT_FOUND`.

- [ ] **Step 1: Write the failing test**

`mcp/tests/test_client_methods.py`:
```python
import json

import httpx
import pytest
import respx

from app.client import HireAIClient, ResultNotReady

BASE = "http://backend.test"


def client():
    return HireAIClient(BASE, "hk_live_testkey", timeout=5)


def ok(data):
    return httpx.Response(200, json={"success": True, "code": "OK", "message": None, "data": data})


@respx.mock
async def test_list_agents_builds_query_params():
    route = respx.get(f"{BASE}/api/catalogue/agents").mock(return_value=ok([{"id": "a1"}]))
    out = await client().list_agents(category="summarization", query="pdf", sort="hot", page=1, size=5)
    assert out == [{"id": "a1"}]
    got = dict(httpx.QueryParams(route.calls.last.request.url.query))
    assert got == {"q": "pdf", "category": "summarization", "sort": "hot", "page": "1", "size": "5"}


@respx.mock
async def test_submit_direct_posts_agentid_and_idempotency_key():
    route = respx.post(f"{BASE}/api/tasks/direct").mock(return_value=ok({"id": "t1", "status": "SUBMITTED"}))
    out = await client().submit_direct(title="T", description="d", budget=50, agent_id="a1")
    assert out == {"id": "t1", "status": "SUBMITTED"}
    req = route.calls.last.request
    assert json.loads(req.content) == {"title": "T", "description": "d", "budget": 50, "agentId": "a1"}
    assert req.headers.get("idempotency-key")  # present and non-empty


@respx.mock
async def test_submit_routed_posts_output_spec():
    route = respx.post(f"{BASE}/api/tasks").mock(return_value=ok({"id": "t2", "status": "SUBMITTED"}))
    await client().submit_routed(title="T", description="d", category="summarization",
                                 budget=50, output_format="TEXT", acceptance_criteria="concise")
    body = json.loads(route.calls.last.request.content)
    assert body["category"] == "summarization"
    assert body["outputSpec"] == {"format": "TEXT", "schema": "", "acceptanceCriteria": "concise"}


@respx.mock
async def test_get_task_returns_data():
    respx.get(f"{BASE}/api/tasks/t1").mock(return_value=ok({"id": "t1", "status": "WORKING"}))
    assert (await client().get_task("t1"))["status"] == "WORKING"


@respx.mock
async def test_get_task_result_returns_payload():
    respx.get(f"{BASE}/api/tasks/t1/result").mock(return_value=ok({"taskId": "t1", "resultPayloadJson": "{}"}))
    assert (await client().get_task_result("t1"))["taskId"] == "t1"


@respx.mock
async def test_get_task_result_not_found_raises_result_not_ready():
    respx.get(f"{BASE}/api/tasks/t1/result").mock(return_value=httpx.Response(
        404, json={"success": False, "code": "NOT_FOUND", "message": "no result", "data": None}))
    with pytest.raises(ResultNotReady):
        await client().get_task_result("t1")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mcp && uv run pytest tests/test_client_methods.py -v`
Expected: FAIL with `AttributeError: 'HireAIClient' object has no attribute 'list_agents'`.

- [ ] **Step 3: Add the methods**

Append these methods to the `HireAIClient` class in `mcp/app/client.py` (and add `import uuid` at the top of the file):
```python
    async def list_agents(self, *, category: str = "", query: str = "", sort: str = "hot",
                          page: int = 0, size: int = 20) -> list:
        return await self._request("GET", "/api/catalogue/agents", params={
            "q": query, "category": category, "sort": sort, "page": page, "size": size})

    async def submit_routed(self, *, title: str, description: str, category: str, budget: float,
                            output_format: str, schema: str = "", acceptance_criteria: str = "") -> dict:
        return await self._request(
            "POST", "/api/tasks",
            json={"title": title, "description": description, "category": category, "budget": budget,
                  "outputSpec": {"format": output_format, "schema": schema,
                                 "acceptanceCriteria": acceptance_criteria}},
            extra_headers={"Idempotency-Key": str(uuid.uuid4())})

    async def submit_direct(self, *, title: str, description: str, budget: float, agent_id: str) -> dict:
        return await self._request(
            "POST", "/api/tasks/direct",
            json={"title": title, "description": description, "budget": budget, "agentId": agent_id},
            extra_headers={"Idempotency-Key": str(uuid.uuid4())})

    async def get_task(self, task_id: str) -> dict:
        return await self._request("GET", f"/api/tasks/{task_id}")

    async def get_task_result(self, task_id: str) -> dict:
        try:
            return await self._request("GET", f"/api/tasks/{task_id}/result")
        except HireAIError as exc:
            if exc.code == "NOT_FOUND":
                raise ResultNotReady() from exc
            raise
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mcp && uv run pytest -v`
Expected: PASS (all tests across `test_config.py`, `test_client.py`, `test_client_methods.py`).

- [ ] **Step 5: Lint + commit**

```bash
cd mcp && uv run ruff check .
git add mcp/app/client.py mcp/tests/test_client_methods.py
git commit -m "feat(mcp): HireAIClient endpoint methods (catalogue, submit routed/direct, task, result)"
```

---

### Task 4: FastMCP server — the four tools + README

**Files:**
- Create: `mcp/app/server.py`
- Create: `mcp/README.md`
- Test: `mcp/tests/test_tools.py`

**Interfaces:**
- Consumes: `HireAIClient`, `ResultNotReady` (Tasks 2–3); `load_settings` (Task 1).
- Produces:
  - Module-level `mcp = FastMCP("hireai")`.
  - `app.server._client() -> HireAIClient` (builds a client from `load_settings()`; tests monkeypatch this).
  - Four async tool functions registered on `mcp`: `list_agents`, `submit_task`, `get_task_status`, `get_task_result` (each returns a `str`).
  - `app.server.main() -> None` running the server over stdio.

- [ ] **Step 1: Write the failing test**

`mcp/tests/test_tools.py`:
```python
import json

import pytest

from app import server
from app.client import ResultNotReady


class FakeClient:
    def __init__(self):
        self.calls = []

    async def list_agents(self, **kwargs):
        self.calls.append(("list_agents", kwargs))
        return [{"id": "a1", "name": "Summarizer", "price": 10}]

    async def submit_direct(self, **kwargs):
        self.calls.append(("submit_direct", kwargs))
        return {"id": "t-direct", "status": "SUBMITTED"}

    async def submit_routed(self, **kwargs):
        self.calls.append(("submit_routed", kwargs))
        return {"id": "t-routed", "status": "SUBMITTED"}

    async def get_task(self, task_id):
        self.calls.append(("get_task", task_id))
        return {"id": task_id, "status": "WORKING", "resolution": None}

    async def get_task_result(self, task_id):
        self.calls.append(("get_task_result", task_id))
        return {"taskId": task_id, "resultPayloadJson": "{\"ok\": true}"}


@pytest.fixture
def fake(monkeypatch):
    fc = FakeClient()
    monkeypatch.setattr(server, "_client", lambda: fc)
    return fc


async def test_list_agents_passes_filters(fake):
    out = await server.list_agents(category="summarization", query="pdf")
    assert fake.calls[0] == ("list_agents", {"category": "summarization", "query": "pdf",
                                             "sort": "hot", "page": 0, "size": 20})
    assert "Summarizer" in out


async def test_submit_task_direct_when_agent_id_given(fake):
    out = await server.submit_task(description="do x", budget=50, agent_id="a1")
    assert fake.calls[0][0] == "submit_direct"
    assert json.loads(out) == {"task_id": "t-direct", "status": "SUBMITTED"}


async def test_submit_task_routed_without_agent_id(fake):
    out = await server.submit_task(description="do x", budget=50, category="summarization")
    assert fake.calls[0][0] == "submit_routed"
    assert json.loads(out)["task_id"] == "t-routed"


async def test_submit_task_routed_requires_category(fake):
    out = await server.submit_task(description="do x", budget=50)
    assert "category" in out.lower()
    assert fake.calls == []  # short-circuits before any client call


async def test_get_task_status_shapes_output(fake):
    out = await server.get_task_status("t9")
    assert json.loads(out) == {"task_id": "t9", "status": "WORKING", "resolution": None}


async def test_get_task_result_not_ready(monkeypatch, fake):
    async def boom(task_id):
        raise ResultNotReady()

    monkeypatch.setattr(fake, "get_task_result", boom)
    out = await server.get_task_result("t1")
    assert "not ready" in out.lower()


async def test_all_four_tools_registered():
    tools = await server.mcp.list_tools()
    assert {t.name for t in tools} == {"list_agents", "submit_task", "get_task_status", "get_task_result"}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mcp && uv run pytest tests/test_tools.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.server'`.

- [ ] **Step 3: Write the server**

`mcp/app/server.py` (the four tools are defined as plain async functions, then registered with `mcp.tool()(fn)` — this keeps them directly callable/testable regardless of the decorator's return value):
```python
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
```

`mcp/README.md`:
````markdown
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
````

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mcp && uv run pytest -v`
Expected: PASS (all files, including the 7 tool tests).

- [ ] **Step 5: Lint + commit**

```bash
cd mcp && uv run ruff check .
git add mcp/app/server.py mcp/tests/test_tools.py mcp/README.md
git commit -m "feat(mcp): FastMCP server with the four tools + README"
```

---

### Task 5: Backend — open the read-only catalogue to API-key auth

**Files:**
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/config/SecurityConfig.java` (add one matcher in `securedFilterChain`)
- Test: `backend/hireai-main/src/test/java/com/hireai/apikey/ProgrammaticSubmissionIntegrationTest.java` (add one test method)

**Interfaces:**
- Consumes: the existing `securedFilterChain` allow-list and the test's `login()` / `createKey()` / `apiKey()` helpers.
- Produces: `GET /api/catalogue/**` reachable by a `ROLE_API_CLIENT` key (and all human roles).

**Note:** this test auto-skips without Docker. Run it with a Docker daemon available to see RED then GREEN.

- [ ] **Step 1: Write the failing test**

Add this method to `ProgrammaticSubmissionIntegrationTest` (uses the existing helpers in that class):
```java
    /**
     * Phase 5: the read-only catalogue is reachable by an API_CLIENT key (it powers the MCP
     * list_agents tool). Public data — owner-private fields are already stripped from the DTOs.
     */
    @Test
    void catalogueBrowseIsReachableByApiKey() throws Exception {
        String jwt = login();
        String rawKey = createKey(jwt, "");

        ResponseEntity<String> resp = rest.exchange(url("/api/catalogue/agents"), HttpMethod.GET,
                new HttpEntity<>(apiKey(rawKey)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-main -am -Dtest=ProgrammaticSubmissionIntegrationTest#catalogueBrowseIsReachableByApiKey test`
Expected: FAIL — the response is `401 UNAUTHORIZED` (catalogue currently falls through to `anyRequest().hasAnyRole("CLIENT","BUILDER","ADMIN")`, which excludes `API_CLIENT`).

- [ ] **Step 3: Add the matcher**

In `SecurityConfig.securedFilterChain`, add the catalogue matcher immediately **after** the `/api/admin/**` line and **before** the `// Default-deny for API keys` comment:
```java
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Public catalogue browse: reachable by any human role or an API_CLIENT key
                        // (powers the MCP list_agents tool). GET only; owner-private fields are already
                        // stripped from the catalogue DTOs (Hard Invariant #5).
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/catalogue/**")
                                .hasAnyRole("CLIENT", "BUILDER", "ADMIN", "API_CLIENT")
                        // Default-deny for API keys: everything else needs a human role. Equivalent to
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-main -am -Dtest=ProgrammaticSubmissionIntegrationTest#catalogueBrowseIsReachableByApiKey test`
Expected: PASS (Tests run: 1, Failures: 0).

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-controller/src/main/java/com/hireai/controller/config/SecurityConfig.java \
        backend/hireai-main/src/test/java/com/hireai/apikey/ProgrammaticSubmissionIntegrationTest.java
git commit -m "feat(mcp): open GET /api/catalogue/** to API-key auth for the list_agents tool"
```

---

### Task 6: Backend — springdoc OpenAPI + Swagger UI

**Files:**
- Modify: `backend/pom.xml` (add `springdoc.version` property + managed dependency)
- Modify: `backend/hireai-controller/pom.xml` (add version-free springdoc dependency)
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/config/OpenApiConfig.java`
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/config/SecurityConfig.java` (permitAll the docs endpoints)
- Test: `backend/hireai-main/src/test/java/com/hireai/openapi/OpenApiDocsIntegrationTest.java`

**Interfaces:**
- Consumes: springdoc auto-configuration; the existing `securedFilterChain`.
- Produces: `GET /v3/api-docs/programmatic` (public) returning an OpenAPI JSON that includes the programmatic paths and excludes admin paths; Swagger UI served at `/swagger-ui/index.html`.

**Note:** this test boots the full app (Testcontainers Postgres) and auto-skips without Docker. Run it with Docker available.

- [ ] **Step 1: Write the failing test**

`backend/hireai-main/src/test/java/com/hireai/openapi/OpenApiDocsIntegrationTest.java`:
```java
package com.hireai.openapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5: springdoc publishes a scoped "programmatic" OpenAPI group, reachable without auth. Boots
 * the full app (real secured chain, no test profile). Auto-skips without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIf("dockerAvailable")
class OpenApiDocsIntegrationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void programmaticApiDocIsPublicAndScoped() {
        ResponseEntity<String> resp = rest.getForEntity(url("/v3/api-docs/programmatic"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat(body).contains("/api/tasks");
        assertThat(body).contains("/api/catalogue/agents");
        assertThat(body).doesNotContain("/api/admin");
    }

    @Test
    void swaggerUiIsPublic() {
        ResponseEntity<String> resp = rest.getForEntity(url("/swagger-ui/index.html"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-main -am -Dtest=OpenApiDocsIntegrationTest test`
Expected: FAIL — `/v3/api-docs/programmatic` returns 401 (springdoc not present, endpoint not permitted). May also fail to compile if run before the dependency is added — that is an acceptable RED.

- [ ] **Step 3a: Pin the springdoc version in the parent POM**

In `backend/pom.xml`, add to `<properties>`:
```xml
        <springdoc.version>2.6.0</springdoc.version>
```
And add to `<dependencyManagement><dependencies>` (alongside the other third-party pins):
```xml
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>${springdoc.version}</version>
            </dependency>
```

- [ ] **Step 3b: Add the dependency to the controller module**

In `backend/hireai-controller/pom.xml`, add to `<dependencies>` (version-free — managed above):
```xml
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>
```

- [ ] **Step 3c: Add the OpenAPI configuration**

`backend/hireai-controller/src/main/java/com/hireai/controller/config/OpenApiConfig.java`:
```java
package com.hireai.controller.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI for the programmatic (API-key) surface. Scoped to the endpoints an external
 * client agent uses; admin/internal routes are excluded from the published document.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public GroupedOpenApi programmaticApi() {
        return GroupedOpenApi.builder()
                .group("programmatic")
                .pathsToMatch("/api/tasks", "/api/tasks/**",
                        "/api/keys", "/api/keys/**",
                        "/api/webhooks/**",
                        "/api/catalogue/**")
                .build();
    }

    @Bean
    public OpenAPI hireaiOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("HireAI Programmatic API")
                        .version("v1")
                        .description("The API-key channel for programmatic clients: submit and track "
                                + "tasks, manage webhooks, and browse the agent catalogue."))
                .components(new Components()
                        .addSecuritySchemes("ApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")
                                .description("Programmatic auth. Value format: `ApiKey <rawKey>` "
                                        + "(the hk_live_... key issued from /client/keys)."))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")
                                .description("Human/session auth (JWT).")));
    }
}
```

- [ ] **Step 3d: Permit the docs endpoints in SecurityConfig**

In `SecurityConfig.securedFilterChain`, add the docs matchers immediately **after** the `.requestMatchers("/actuator/health").permitAll()` line:
```java
                        .requestMatchers("/actuator/health").permitAll()
                        // Public API documentation (springdoc / Swagger UI) — the programmatic OpenAPI group.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-main -am -Dtest=OpenApiDocsIntegrationTest test`
Expected: PASS (Tests run: 2, Failures: 0).

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/hireai-controller/pom.xml \
        backend/hireai-controller/src/main/java/com/hireai/controller/config/OpenApiConfig.java \
        backend/hireai-controller/src/main/java/com/hireai/controller/config/SecurityConfig.java \
        backend/hireai-main/src/test/java/com/hireai/openapi/OpenApiDocsIntegrationTest.java
git commit -m "feat(mcp): springdoc OpenAPI + Swagger UI for the programmatic API surface"
```

---

### Task 7: CI wiring + living-docs update

**Files:**
- Create: `.github/workflows/mcp-ci.yml`
- Modify: `CLAUDE.md` (repository-status paragraph pointer)
- Modify: `docs/details/build-status.md` (mark Phase 5 built)
- Modify: `docs/details/programmatic-channel.md` (add the MCP facade + OpenAPI section)
- Modify: `docs/details/architecture.md` (add the `mcp/` service)
- Modify: `docs/programmatic-task-submission.md` (flip Phase 5 status in §0)

**Interfaces:** none (CI + docs only).

- [ ] **Step 1: Add the CI workflow (mirrors `arbitration-ci.yml`)**

`.github/workflows/mcp-ci.yml`:
```yaml
name: MCP CI

on:
  pull_request:
    branches: [main]
    paths:
      - 'mcp/**'
      - '.github/workflows/mcp-ci.yml'
  push:
    branches: [main]
    paths:
      - 'mcp/**'
      - '.github/workflows/mcp-ci.yml'

permissions:
  contents: read

concurrency:
  group: mcp-ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  lint-test:
    name: Lint & test (ruff, pytest)
    runs-on: ubuntu-latest
    timeout-minutes: 15
    defaults:
      run:
        working-directory: mcp
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up uv + Python 3.12
        uses: astral-sh/setup-uv@v5
        with:
          python-version: '3.12'

      - name: Install dependencies
        run: uv sync --dev

      - name: Lint
        run: uv run ruff check .

      - name: Test
        run: uv run pytest
```

- [ ] **Step 2: Update the living docs**

In `CLAUDE.md`, in the "Repository status" paragraph, add the MCP facade to the programmatic-channel mention, e.g. change "plus the **programmatic API-key channel** (submit → deterministic auto-settle → signed push webhooks)" to end with "…signed push webhooks; **MCP server facade + OpenAPI**)".

In `docs/details/build-status.md`, under `## Frontend`/end of the programmatic paragraph or a new line, add:
> **Phase 5 — MCP server facade + OpenAPI built.** A standalone `mcp/` Python service (official MCP SDK, thin REST client over stdio) exposes four tools (`list_agents`/`submit_task`/`get_task_status`/`get_task_result`) to MCP-native client agents; springdoc publishes a scoped "programmatic" OpenAPI group + Swagger UI. Backend touches: `GET /api/catalogue/**` opened to API-key auth; springdoc added. No migration. See `programmatic-channel.md`.

In `docs/details/programmatic-channel.md`, add a new section after §6 (Push webhooks):
> ## 6a. MCP server facade (Phase 5)
> A standalone `mcp/` Python service (official MCP SDK / FastMCP) exposes the channel as four stdio tools — `list_agents` (→ `GET /api/catalogue/agents`), `submit_task` (routed `POST /api/tasks` or direct `POST /api/tasks/direct`, auto `Idempotency-Key`), `get_task_status`, `get_task_result` (polling; `NOT_FOUND` = not ready). It authenticates with an API key from env and holds **no business logic** (identity is still resolved server-side — Inv #5). An OpenAPI document (springdoc, scoped "programmatic" group) + Swagger UI describe the REST surface. Local/stdio only; remote/OAuth MCP and SDK generation stay future.

In `docs/details/architecture.md`, add `mcp/` alongside `arbitration/` in the services/topology description: a stdio MCP server that translates tool calls into API-key REST calls (no new backend I/O).

In `docs/programmatic-task-submission.md` §0, move Phase 5 from "Not built" to built: change the status header line and the "Not built" bullet to reflect that the MCP server facade + OpenAPI are now built (per-key scopes & rotation remain the only deferred item).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/mcp-ci.yml CLAUDE.md docs/details/build-status.md \
        docs/details/programmatic-channel.md docs/details/architecture.md \
        docs/programmatic-task-submission.md
git commit -m "docs(mcp): CI workflow + mark Phase 5 (MCP facade + OpenAPI) built"
```

---

### Task 8: Manual end-to-end demo verification

This task has no automated test — it is the rehearsed live demo required by the spec's success criterion. Do it once the automated tasks are green.

- [ ] **Step 1: Bring up the stack**

Follow `docs/details/demo-runbook.md` to start Postgres, RabbitMQ, a stub agent, and the backend (`mvn -f backend/pom.xml -pl hireai-main -am spring-boot:run`, cwd `backend/`), and the frontend. Log in as the seeded client and mint an API key at `/client/keys` (copy the raw `hk_live_...` value once).

- [ ] **Step 2: Verify the Swagger UI**

Open `http://localhost:8080/swagger-ui/index.html`. Confirm the "programmatic" group renders and shows `/api/tasks`, `/api/catalogue/agents`, `/api/webhooks/...`, and NOT `/api/admin/...`.

- [ ] **Step 3: Smoke-test the tools with MCP Inspector**

Run: `cd mcp && HIREAI_API_KEY=hk_live_... npx @modelcontextprotocol/inspector uv run hireai-mcp`
In the Inspector: call `list_agents` (see the seeded agents), then `submit_task` with an `agent_id` from the list + a budget, then `get_task_status` until terminal, then `get_task_result`.

- [ ] **Step 4: Rehearse the Claude Desktop demo**

Add the `mcpServers.hireai` block from `mcp/README.md` to `claude_desktop_config.json` (absolute path to `mcp/`, the base URL, and the API key). Restart Claude Desktop. Prompt: *"List summarization agents on HireAI, book the top one to summarize this text (budget 50 credits), poll until it's done, and show me the result."* Confirm the model walks `list_agents → submit_task → get_task_status → get_task_result` and returns the result. Capture a screen recording for the FYP.

- [ ] **Step 5: Final full-suite check + push**

Run the backend full suite (with Docker) and the MCP suite:
```bash
mvn -f backend/pom.xml -B test
cd mcp && uv run pytest && uv run ruff check .
```
Then push the branch: `git push -u origin feat/mcp-server`. (Do not open/merge a PR without explicit user go-ahead.)

---

## Self-Review

**Spec coverage** (against `2026-07-22-mcp-server-design.md`):
- §3 architecture / `mcp/` layout → Tasks 1–4. §3.2 MCP SDK → Task 4.
- §4 four tool contracts (routed/direct, idempotency, return shaping) → Tasks 3 (client) + 4 (tools).
- §5.1 catalogue allow-list → Task 5. §5.2 springdoc scoped group + security schemes + public docs → Task 6.
- §6 auth/invariants → enforced across Tasks 2 (auth header), 4 (key from env, never logged), 5 (server-side identity preserved).
- §7 error handling table → Task 2 (envelope/401/network) + Task 3 (`NOT_FOUND` → ResultNotReady) + Task 4 (not-ready message, category-required message).
- §8 testing (Python + backend + live E2E) → Tasks 1–4 (Python), 5–6 (backend), 8 (live E2E).
- §9 demo path → Task 4 (README) + Task 8. §11 delivery (branch, no migration, doc updates, CI) → Task 7 + global constraints.
- §10 out-of-scope items → not implemented (correct).

**Placeholder scan:** no TBD/TODO; every code and test block is complete. The `<absolute-path-to-repo>` / `hk_live_...` tokens are user-supplied runtime values in config/demo steps, not code placeholders.

**Type consistency:** client method names/signatures defined in Task 3 (`list_agents`, `submit_routed`, `submit_direct`, `get_task`, `get_task_result`) match their call sites in Task 4's server and the Task 4 `FakeClient`. `HireAIError`/`ResultNotReady` (Task 2) are used consistently in Tasks 3–4. The `WebResult` envelope shape and status codes match `GlobalExceptionConfiguration`. Backend matcher/bean names (`programmaticApi`, `hireaiOpenAPI`, `/v3/api-docs/programmatic`) are consistent between Task 6's config and its test.
