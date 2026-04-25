"""FastAPI entry point — mlx-server.

Lifespan instantiates a process-wide ModelManager and pre-warms the preset's
vision model so the first real request doesn't pay the load cost.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .manager import ModelManager
from .routes import router
from .settings import settings


def _configure_logging() -> None:
    logging.basicConfig(
        level=settings.log_level.upper(),
        format="%(asctime)s %(levelname)-5s %(name)s — %(message)s",
        datefmt="%H:%M:%S",
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    _configure_logging()
    log = logging.getLogger("mlx-server")
    log.info("starting mlx-server preset=%s host=%s port=%d", settings.preset.value, settings.api_host, settings.api_port)

    mgr = ModelManager(
        preset=settings.preset,
        vision_override=settings.vision_model_override,
        text_override=settings.text_model_override,
        enable_thinking=settings.enable_thinking,
    )
    app.state.manager = mgr

    try:
        mgr.warm()
        log.info("warm complete; model_loaded=%s", mgr.loaded_models())
    except Exception:
        log.exception("warm-up failed; server will load on first request instead")

    try:
        yield
    finally:
        log.info("shutdown — releasing models")
        mgr._unload_vlm()
        mgr._unload_lm()


app = FastAPI(
    title="mlx-server",
    description="Local MLX-backed LLM service exposing OpenAI-compatible /v1/chat/completions.",
    version="0.1.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins_list(),
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)

app.include_router(router)
