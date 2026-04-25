"""Runtime configuration — all MLX-server settings live in the repo's root .env
under the MLX_ prefix (e.g. MLX_PRESET, MLX_API_PORT). One env file for the
whole project so we don't fragment secrets across services.

HF_TOKEN, when present in the root .env, is sourced into the process
environment by the Makefile before uvicorn launches; the huggingface library
reads it directly from os.environ.
"""

from __future__ import annotations

from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from .registry import Preset


# Resolve repo-root .env at import time. settings.py → app/ → mlx-server/ →
# llm/ → repo root.
ROOT_ENV = Path(__file__).resolve().parents[3] / ".env"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=str(ROOT_ENV),
        env_file_encoding="utf-8",
        env_prefix="MLX_",
        extra="ignore",
        case_sensitive=False,
    )

    preset: Preset = Preset.LIGHT

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
