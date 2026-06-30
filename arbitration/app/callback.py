import httpx

from app.schemas import RulingResult


async def post_ruling(base_url: str, dispute_id: str, secret: str, result: RulingResult,
                      *, timeout: float) -> int:
    url = f"{base_url}/api/arbitration-callbacks/{dispute_id}/ruling"
    async with httpx.AsyncClient(timeout=timeout) as client:
        resp = await client.post(
            url,
            json={"category": result.category, "rationale": result.rationale},
            headers={"Authorization": f"Bearer {secret}"},
        )
    resp.raise_for_status()
    return resp.status_code
