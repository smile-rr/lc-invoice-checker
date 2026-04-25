"""Model registry and preset definitions.

Adding a new model = adding one entry to MODEL_REGISTRY. No other code touches.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Literal


ModelType = Literal["vlm", "lm"]


@dataclass(frozen=True)
class ModelSpec:
    name: str
    repo_id: str
    model_type: ModelType
    default_max_tokens: int = 2048


MODEL_REGISTRY: dict[str, ModelSpec] = {
    "qwen3-vl-4b": ModelSpec(
        name="qwen3-vl-4b",
        repo_id="mlx-community/Qwen3-VL-4B-Instruct-4bit",
        model_type="vlm",
        default_max_tokens=2048,
    ),
    "qwen3-vl-8b": ModelSpec(
        name="qwen3-vl-8b",
        repo_id="mlx-community/Qwen3-VL-8B-Instruct-4bit",
        model_type="vlm",
        default_max_tokens=2048,
    ),
    "qwen3-4b": ModelSpec(
        name="qwen3-4b",
        repo_id="mlx-community/Qwen3-4B-Instruct-2507-4bit",
        model_type="lm",
        default_max_tokens=2048,
    ),
}


class Preset(str, Enum):
    LIGHT = "light"
    BALANCED = "balanced"
    QUALITY = "quality"


@dataclass(frozen=True)
class PresetSpec:
    """Which models a preset uses, and whether the preset can hold them concurrently.

    `single_model=True` means the *vision* model (a multimodal VLM) handles BOTH
    vision and text-only requests — text-only requests just don't include an
    image part. This is the cheap path: one model loaded, both roles served.

    `single_model=False` means vision and text are different models; we must
    swap them serially when memory is tight (16 GB MacBook).
    """

    vision: str
    text: str
    single_model: bool


PRESETS: dict[Preset, PresetSpec] = {
    Preset.LIGHT: PresetSpec(
        vision="qwen3-vl-4b",
        text="qwen3-vl-4b",
        single_model=True,
    ),
    Preset.BALANCED: PresetSpec(
        vision="qwen3-vl-8b",
        text="qwen3-vl-8b",
        single_model=True,
    ),
    Preset.QUALITY: PresetSpec(
        vision="qwen3-vl-8b",
        text="qwen3-4b",
        single_model=False,
    ),
}


def resolve_preset(preset: Preset) -> PresetSpec:
    return PRESETS[preset]


def get_spec(name: str) -> ModelSpec:
    if name not in MODEL_REGISTRY:
        known = ", ".join(MODEL_REGISTRY.keys())
        raise KeyError(f"unknown model {name!r}; registered: {known}")
    return MODEL_REGISTRY[name]
