import asyncio
import hashlib

import httpx

from app import item_results, read_sources

SCAN = b"%PDF-1.7\ninvoice"


def _items():
    return [
        {"itemNo": 1, "input": "a.pdf",
         "source": {"url": "https://platform/api/source-links/ok", "sha256": hashlib.sha256(SCAN).hexdigest(),
                    "filename": "a.pdf"}},
        {"itemNo": 2, "input": "b.pdf",
         "source": {"url": "https://platform/api/source-links/expired", "sha256": "0" * 64, "filename": "b.pdf"}},
        {"itemNo": 3, "input": "c.pdf",
         "source": {"url": "https://platform/api/source-links/swapped", "sha256": "1" * 64, "filename": "c.pdf"}},
        {"itemNo": 4, "input": "plain text"},
    ]


def _platform(request: httpx.Request) -> httpx.Response:
    if request.url.path.endswith("/expired"):
        return httpx.Response(401, json={"message": "This link expired"})
    return httpx.Response(200, content=SCAN)


def test_each_document_is_read_through_its_link_and_checked_against_its_hash():
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(_platform)) as client:
            return await read_sources(_items(), client)

    documents = asyncio.run(run())

    assert documents == {1: SCAN, 2: None, 3: None}  # 3's bytes are not the hash the WorkOrder names
    results = item_results(_items(), documents)
    assert [r["agentStatus"] for r in results] == ["COMPLETED", "FAILED", "FAILED", "COMPLETED"]
    assert "sha256 verified" in results[0]["resultPayloadJson"]
    assert "verified" not in results[3]["resultPayloadJson"]
