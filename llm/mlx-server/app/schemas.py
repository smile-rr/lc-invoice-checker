"""OpenAI-compatible request/response shapes.

We model only the fields lc-checker-svc (Spring AI OpenAI client) actually
emits and consumes. Anything richer (tools/function-calling, logprobs, n>1,
streaming) is intentionally absent in v1.
"""

from __future__ import annotations

import time
import uuid
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


# ---- request side ------------------------------------------------------------


class ImageUrl(BaseModel):
    url: str  # may be a "data:image/...;base64,XXX" URL or a remote https URL


class ContentPart(BaseModel):
    model_config = ConfigDict(extra="ignore")
    type: Literal["text", "image_url"]
    text: str | None = None
    image_url: ImageUrl | None = None


class ChatMessage(BaseModel):
    model_config = ConfigDict(extra="ignore")
    role: Literal["system", "user", "assistant"]
    content: str | list[ContentPart]


class ChatCompletionRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    model: str
    messages: list[ChatMessage]

    temperature: float | None = None
    max_tokens: int | None = None
    stream: bool = False  # rejected at the route layer in v1


# ---- response side -----------------------------------------------------------


class Usage(BaseModel):
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0


class ChatCompletionChoice(BaseModel):
    index: int = 0
    message: ChatMessage
    finish_reason: Literal["stop", "length", "error"] = "stop"


class ChatCompletionResponse(BaseModel):
    id: str = Field(default_factory=lambda: f"chatcmpl-{uuid.uuid4().hex[:24]}")
    object: Literal["chat.completion"] = "chat.completion"
    created: int = Field(default_factory=lambda: int(time.time()))
    model: str
    choices: list[ChatCompletionChoice]
    usage: Usage = Field(default_factory=Usage)


# ---- /v1/models --------------------------------------------------------------


class ModelObject(BaseModel):
    id: str
    object: Literal["model"] = "model"
    created: int = Field(default_factory=lambda: int(time.time()))
    owned_by: str = "mlx-server"


class ModelListResponse(BaseModel):
    object: Literal["list"] = "list"
    data: list[ModelObject]


# ---- /health -----------------------------------------------------------------


class HealthResponse(BaseModel):
    status: Literal["ok", "warming", "error"] = "ok"
    preset: str
    vision_model: str
    text_model: str
    model_loaded: list[str]
    peak_memory_gb: float | None = None
    available_models: list[str]
