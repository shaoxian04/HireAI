import pytest

from app.graph.tools import FetchError, _assert_public_https, validate_against_schema


def test_rejects_non_https():
    with pytest.raises(FetchError):
        _assert_public_https("http://example.com/x")


def test_rejects_loopback_host():
    with pytest.raises(FetchError):
        _assert_public_https("https://127.0.0.1/x")


def test_rejects_metadata_ip():
    with pytest.raises(FetchError):
        _assert_public_https("https://169.254.169.254/latest/meta-data/")


def test_rejects_private_hostname(monkeypatch):
    # Force DNS resolution to a private address.
    import app.graph.tools as tools

    monkeypatch.setattr(tools.socket, "getaddrinfo",
                        lambda *a, **k: [(None, None, None, None, ("10.0.0.5", 443))])
    with pytest.raises(FetchError):
        _assert_public_https("https://internal.evil.test/x")


def test_validate_against_schema_reports_errors():
    schema = '{"type":"object","required":["sources"]}'
    ok = validate_against_schema('{"sources":[]}', schema)
    bad = validate_against_schema('{}', schema)
    assert ok["valid"] is True
    assert bad["valid"] is False
    assert bad["errors"]


def test_validate_handles_malformed_json():
    res = validate_against_schema("not json", '{"type":"object"}')
    assert res["valid"] is False


def test_validate_handles_unresolvable_ref():
    res = validate_against_schema('{}', '{"$ref": "https://unresolvable.example.test/schema.json"}')
    assert res["valid"] is False
    assert res["errors"]
