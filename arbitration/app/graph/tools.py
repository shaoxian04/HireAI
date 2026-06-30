import ipaddress
import json
import socket
from urllib.parse import urlparse

import httpx
from jsonschema import Draft202012Validator


class FetchError(Exception):
    """Raised when an evidence URL is unsafe or unfetchable."""


def _assert_public_https(url: str) -> None:
    """Validate that *url* is HTTPS and resolves only to public IP addresses.

    Security note — residual TOCTOU/DNS-rebinding gap:
    This function resolves the host then allows httpx to reconnect independently.
    A DNS-rebinding attack could cause the second resolution to return a private
    address after the check passes.  For the HireAI FYP threat model this is
    acceptable: ``follow_redirects=False`` eliminates the redirect vector, and
    cloud metadata services (169.254.169.254) are blocked as link-local.  A
    production deployment should use a fixed-IP transport or an egress proxy
    instead of relying on this guard alone.
    """
    parsed = urlparse(url)
    if parsed.scheme != "https":
        raise FetchError("only https URLs are allowed")
    host = parsed.hostname
    if not host:
        raise FetchError("missing host")
    port = parsed.port or 443
    try:
        infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
    except socket.gaierror as e:
        raise FetchError(f"cannot resolve host: {host}") from e
    for *_, sockaddr in infos:
        ip = ipaddress.ip_address(sockaddr[0])
        if (
            ip.is_private
            or ip.is_loopback
            or ip.is_link_local
            or ip.is_reserved
            or ip.is_multicast
            or ip.is_unspecified
        ):
            raise FetchError(f"blocked non-public address: {ip}")


async def fetch_result_content(url: str, *, max_bytes: int, timeout: float) -> str:
    """Fetch the full FILE evidence over HTTPS, SSRF-guarded, size-capped, no redirects."""
    _assert_public_https(url)
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as client:
        async with client.stream("GET", url) as resp:
            resp.raise_for_status()
            content = b""
            truncated = False
            async for chunk in resp.aiter_bytes():
                content += chunk
                if len(content) > max_bytes:
                    content = content[:max_bytes]
                    truncated = True
                    break
            ctype = resp.headers.get("content-type", "")
    if "text" in ctype or "json" in ctype or not ctype:
        text = content.decode("utf-8", errors="replace")
    else:
        text = f"[binary content omitted: {len(content)} bytes, content-type {ctype!r}]"
    if truncated:
        text += "\n[TRUNCATED — content exceeded the size cap]"
    return text


def validate_against_schema(payload: str, schema: str | None) -> dict:
    """Re-run the JSON-Schema check as citable grounding evidence (not a gate)."""
    if not schema:
        return {"valid": True, "errors": [], "note": "no schema declared"}
    try:
        data = json.loads(payload)
    except (json.JSONDecodeError, TypeError):
        return {"valid": False, "errors": ["payload is not valid JSON"]}
    try:
        schema_obj = json.loads(schema)
    except (json.JSONDecodeError, TypeError):
        return {"valid": False, "errors": ["declared schema is not valid JSON"]}
    try:
        validator = Draft202012Validator(schema_obj)
        errors = [e.message for e in validator.iter_errors(data)]
    except Exception as exc:  # noqa: BLE001 - schema may have unresolvable $ref / be invalid; never raise
        return {"valid": False, "errors": [f"schema evaluation error: {exc}"]}
    return {"valid": not errors, "errors": errors}
