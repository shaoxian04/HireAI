import uuid
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
