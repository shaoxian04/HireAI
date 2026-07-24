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
