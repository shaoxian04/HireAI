from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    openai_api_key: str = ""
    openai_model: str = "gpt-4o"
    rabbitmq_url: str = "amqp://guest:guest@localhost:5672/"
    arbitration_callback_secret: str = ""
    backend_base_url: str = "http://localhost:8080"
    dispute_queue: str = "task.dispute.requested"
    fetch_max_bytes: int = 5_000_000
    fetch_timeout_seconds: float = 10.0
    callback_timeout_seconds: float = 10.0


def load_settings() -> Settings:
    return Settings()
