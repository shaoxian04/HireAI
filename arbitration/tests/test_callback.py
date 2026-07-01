import httpx
import pytest
import respx

from app.callback import post_ruling
from app.schemas import RulingResult

BASE = "http://backend.test"
DID = "11111111-1111-1111-1111-111111111111"
URL = f"{BASE}/api/arbitration-callbacks/{DID}/ruling"


@respx.mock
async def test_posts_ruling_with_bearer_secret():
    route = respx.post(URL).mock(return_value=httpx.Response(200))
    status = await post_ruling(BASE, DID, "s3cret",
                               RulingResult(category="FULFILLED", rationale="ok"), timeout=5)
    assert status == 200
    sent = route.calls.last.request
    assert sent.headers["authorization"] == "Bearer s3cret"
    import json
    assert json.loads(sent.content) == {"category": "FULFILLED", "rationale": "ok"}


@respx.mock
async def test_raises_on_401():
    respx.post(URL).mock(return_value=httpx.Response(401))
    with pytest.raises(httpx.HTTPStatusError):
        await post_ruling(BASE, DID, "wrong",
                          RulingResult(category="FULFILLED", rationale="ok"), timeout=5)


@respx.mock
async def test_raises_on_404():
    respx.post(URL).mock(return_value=httpx.Response(404))
    with pytest.raises(httpx.HTTPStatusError):
        await post_ruling(BASE, DID, "s",
                          RulingResult(category="NOT_FULFILLED", rationale="x"), timeout=5)
