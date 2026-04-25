"""Runtime configuration loaded from .env via pydantic-settings."""

from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from .registry import Preset


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    llm_preset: Preset = Preset.LIGHT

    vision_model_override: str = ""
    text_model_override: str = ""

    api_host: str = "127.0.0.1"
    api_port: int = 8088

    max_tokens_default: int = 2048
    temperature_default: float = 0.1
    enable_thinking: bool = False

    log_level: str = "INFO"

    # Comma-separated CORS origins for the dev UI / Java service.
    cors_origins: str = Field(
        default="http://localhost:5173,http://localhost:8080,http://127.0.0.1:5173,http://127.0.0.1:8080"
    )

    def cors_origins_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]


settings = Settings()
