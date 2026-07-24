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
