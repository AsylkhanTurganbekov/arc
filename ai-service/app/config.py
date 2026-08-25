from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = "postgresql+asyncpg://arc:arc_local_password@localhost:5432/arc"
    arc_backend_url: str = "http://localhost:8081"
    nitec_llm_api_key: str = ""
    nitec_llm_base_url: str = "https://llm.nitec.kz/v1"
    nitec_llm_model: str = "openai/gpt-oss-120b"
    temperature: float = 0.1
    max_tokens: int = 1400

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()
