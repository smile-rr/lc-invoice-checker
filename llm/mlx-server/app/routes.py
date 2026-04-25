"""HTTP routes — OpenAI-compatible /v1/chat/completions, /v1/models, /health."""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter, HTTPException, Request

from .manager import ModelManager
from .registry import MODEL_REGISTRY
from .schemas import (
    ChatCompletionChoice,
    ChatCompletionRequest,
    ChatCompletionResponse,
    ChatMessage,
    HealthResponse,
    ModelListResponse,
    ModelObject,
    Usage,
)
from .settings import settings

logger = logging.getLogger(__name__)
router = APIRouter()


def _manager(request: Request) -> ModelManager:
    mgr = getattr(request.app.state, "manager", None)
    if mgr is None:  # pragma: no cover — should never happen if lifespan ran
        raise HTTPException(status_code=503, detail="model manager not initialized")
    return mgr


@router.post("/v1/chat/completions", response_model=ChatCompletionResponse)
def chat_completions(req: ChatCompletionRequest, request: Request):
    if req.stream:
        raise HTTPException(status_code=400, detail="streaming is not supported in v1")
    if req.model not in MODEL_REGISTRY:
        raise HTTPException(
            status_code=400,
            detail=f"unknown model {req.model!r}; available: {list(MODEL_REGISTRY.keys())}",
        )
    if not req.messages:
        raise HTTPException(status_code=400, detail="messages must be non-empty")

    mgr = _manager(request)
    t0 = time.time()
    try:
        result = mgr.generate(
            model_name=req.model,
            messages=req.messages,
            max_tokens=req.max_tokens,
            temperature=req.temperature if req.temperature is not None else settings.temperature_default,
        )
    except Exception as e:
        logger.exception("generation failed")
        raise HTTPException(status_code=500, detail=f"generation failed: {type(e).__name__}: {e}") from e

    elapsed_ms = int((time.time() - t0) * 1000)
    logger.info(
        "chat.completions model=%s tokens=%d/%d elapsed_ms=%d",
        req.model,
        result.prompt_tokens,
        result.completion_tokens,
        elapsed_ms,
    )

    return ChatCompletionResponse(
        model=req.model,
        choices=[
            ChatCompletionChoice(
                index=0,
                message=ChatMessage(role="assistant", content=result.text),
                finish_reason=result.finish_reason,
            )
        ],
        usage=Usage(
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
            total_tokens=result.prompt_tokens + result.completion_tokens,
        ),
    )


@router.get("/v1/models", response_model=ModelListResponse)
def list_models():
    return ModelListResponse(data=[ModelObject(id=name) for name in MODEL_REGISTRY])


@router.get("/health", response_model=HealthResponse)
def health(request: Request):
    mgr = getattr(request.app.state, "manager", None)
    if mgr is None:
        return HealthResponse(
            status="warming",
            preset=settings.llm_preset.value,
            vision_model=settings.vision_model_override or "",
            text_model=settings.text_model_override or "",
            model_loaded=[],
            peak_memory_gb=None,
            available_models=list(MODEL_REGISTRY.keys()),
        )
    return HealthResponse(
        status="ok",
        preset=mgr.preset_key.value,
        vision_model=mgr.vision_name,
        text_model=mgr.text_name,
        model_loaded=mgr.loaded_models(),
        peak_memory_gb=mgr.peak_memory_gb(),
        available_models=list(MODEL_REGISTRY.keys()),
    )
