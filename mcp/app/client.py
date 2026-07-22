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
