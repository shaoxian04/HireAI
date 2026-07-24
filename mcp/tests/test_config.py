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
