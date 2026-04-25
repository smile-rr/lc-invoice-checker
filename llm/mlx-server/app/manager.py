"""ModelManager — MLX model lifecycle + generation.

Design:
- One process-wide ModelManager (instantiated in main.py lifespan).
- Holds at most one VLM and one LM. `preset.single_model=True` reuses the VLM
  for text-only too (cheap path — one model, both roles).
- `generate()` is the single entry point used by routes.py.
- All chat-template calls pass `enable_thinking=False` (see registry/settings).
"""

from __future__ import annotations

import base64
import gc
import logging
import re
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from .registry import MODEL_REGISTRY, ModelSpec, PRESETS, Preset, PresetSpec, get_spec
from .schemas import ChatMessage

logger = logging.getLogger(__name__)


_DATA_URL_RE = re.compile(r"^data:image/(?P<ext>png|jpeg|jpg|webp);base64,(?P<b64>.+)$", re.DOTALL)


@dataclass
class LoadedVlm:
    name: str
    model: object
    processor: object
    config: object


@dataclass
class LoadedLm:
    name: str
    model: object
    tokenizer: object


@dataclass
class GenerationResult:
    text: str
    finish_reason: str  # "stop" | "length" | "error"
    prompt_tokens: int
    completion_tokens: int


class ModelManager:
    def __init__(
        self,
        preset: Preset,
        vision_override: str = "",
        text_override: str = "",
        enable_thinking: bool = False,
    ):
        self.preset_key = preset
        self.preset: PresetSpec = PRESETS[preset]
        self.vision_name = vision_override or self.preset.vision
        self.text_name = text_override or self.preset.text
        self.enable_thinking = enable_thinking

        self._vlm: LoadedVlm | None = None
        self._lm: LoadedLm | None = None
        self._lock = threading.Lock()  # mlx generation is not safe to run concurrently

        # Validate at construction so a bad preset is caught early.
        get_spec(self.vision_name)
        get_spec(self.text_name)

    # ---- public API ---------------------------------------------------------

    def warm(self) -> None:
        """Pre-load the preset's vision model so the first request is fast."""
        spec = get_spec(self.vision_name)
        if spec.model_type == "vlm":
            self._ensure_vlm(spec)
        else:
            self._ensure_lm(spec)

    def loaded_models(self) -> list[str]:
        out: list[str] = []
        if self._vlm:
            out.append(self._vlm.name)
        if self._lm:
            out.append(self._lm.name)
        return out

    def peak_memory_gb(self) -> float | None:
        """Returns peak GPU memory in GB, or None if MLX metal isn't available."""
        try:
            import mlx.core as mx  # type: ignore

            return round(mx.metal.get_peak_memory() / (1024**3), 2)
        except Exception:
            return None

    def generate(
        self,
        model_name: str,
        messages: list[ChatMessage],
        max_tokens: int | None = None,
        temperature: float | None = None,
    ) -> GenerationResult:
        spec = get_spec(model_name)
        max_tokens = max_tokens or spec.default_max_tokens
        temperature = 0.1 if temperature is None else temperature

        image_paths, plain_messages = self._extract_images(messages)
        has_image = bool(image_paths)

        with self._lock:
            try:
                if has_image:
                    return self._generate_vlm(spec, plain_messages, image_paths, max_tokens, temperature)

                # text-only request
                if self.preset.single_model and spec.model_type == "vlm":
                    # cheap path — VLM serves text-only too
                    return self._generate_vlm(spec, plain_messages, [], max_tokens, temperature)

                if spec.model_type == "vlm":
                    return self._generate_vlm(spec, plain_messages, [], max_tokens, temperature)

                # explicit text-only LM
                return self._generate_lm(spec, plain_messages, max_tokens, temperature)
            finally:
                # Clean up any temp images we wrote.
                for p in image_paths:
                    try:
                        Path(p).unlink(missing_ok=True)
                    except OSError:
                        pass

    # ---- model loading ------------------------------------------------------

    def _ensure_vlm(self, spec: ModelSpec) -> LoadedVlm:
        if self._vlm and self._vlm.name == spec.name:
            return self._vlm

        # If a different VLM was loaded, free it first.
        if self._vlm and self._vlm.name != spec.name:
            self._unload_vlm()

        # Quality preset: serial — never hold a VLM and an LM together.
        if not self.preset.single_model and self._lm is not None:
            self._unload_lm()

        from mlx_vlm import load as vlm_load  # type: ignore

        t0 = time.time()
        logger.info("loading VLM %s (%s)", spec.name, spec.repo_id)
        model, processor = vlm_load(spec.repo_id)
        # mlx-vlm exposes config on the model
        config = getattr(model, "config", None)
        self._vlm = LoadedVlm(name=spec.name, model=model, processor=processor, config=config)
        logger.info("VLM %s loaded in %.1fs", spec.name, time.time() - t0)
        return self._vlm

    def _ensure_lm(self, spec: ModelSpec) -> LoadedLm:
        if self._lm and self._lm.name == spec.name:
            return self._lm
        if self._lm and self._lm.name != spec.name:
            self._unload_lm()
        if not self.preset.single_model and self._vlm is not None:
            self._unload_vlm()

        from mlx_lm import load as lm_load  # type: ignore

        t0 = time.time()
        logger.info("loading LM %s (%s)", spec.name, spec.repo_id)
        model, tokenizer = lm_load(spec.repo_id)
        self._lm = LoadedLm(name=spec.name, model=model, tokenizer=tokenizer)
        logger.info("LM %s loaded in %.1fs", spec.name, time.time() - t0)
        return self._lm

    def _unload_vlm(self) -> None:
        if not self._vlm:
            return
        logger.info("unloading VLM %s", self._vlm.name)
        self._vlm = None
        self._free_caches()

    def _unload_lm(self) -> None:
        if not self._lm:
            return
        logger.info("unloading LM %s", self._lm.name)
        self._lm = None
        self._free_caches()

    @staticmethod
    def _free_caches() -> None:
        gc.collect()
        try:
            import mlx.core as mx  # type: ignore

            mx.metal.clear_cache()
        except Exception:
            pass

    # ---- generation paths ---------------------------------------------------

    def _generate_vlm(
        self,
        spec: ModelSpec,
        messages: list[ChatMessage],
        image_paths: list[str],
        max_tokens: int,
        temperature: float,
    ) -> GenerationResult:
        loaded = self._ensure_vlm(spec)

        from mlx_vlm import generate as vlm_generate  # type: ignore
        from mlx_vlm.prompt_utils import apply_chat_template  # type: ignore

        plain = _messages_to_dicts(messages)
        num_images = len(image_paths)
        formatted = apply_chat_template(
            loaded.processor,
            loaded.config,
            plain,
            num_images=num_images,
        )

        # mlx-vlm.generate returns either a string or a result object across versions.
        gen_kwargs = dict(
            model=loaded.model,
            processor=loaded.processor,
            prompt=formatted,
            max_tokens=max_tokens,
            temperature=temperature,
            verbose=False,
        )
        if image_paths:
            gen_kwargs["image"] = image_paths

        raw = vlm_generate(**gen_kwargs)
        text, finish, prompt_tokens, completion_tokens = _coerce_generate_output(raw)
        return GenerationResult(
            text=text,
            finish_reason=finish if completion_tokens < max_tokens else "length",
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
        )

    def _generate_lm(
        self,
        spec: ModelSpec,
        messages: list[ChatMessage],
        max_tokens: int,
        temperature: float,
    ) -> GenerationResult:
        loaded = self._ensure_lm(spec)

        from mlx_lm import generate as lm_generate  # type: ignore

        plain = _messages_to_dicts(messages)
        # The Qwen tokenizer chat template accepts enable_thinking; pass it always.
        prompt = loaded.tokenizer.apply_chat_template(
            plain,
            add_generation_prompt=True,
            tokenize=False,
            enable_thinking=self.enable_thinking,
        )

        # mlx-lm.generate signature varies across versions; use the most stable form.
        try:
            text = lm_generate(
                loaded.model,
                loaded.tokenizer,
                prompt=prompt,
                max_tokens=max_tokens,
                temp=temperature,
                verbose=False,
            )
        except TypeError:
            # Newer mlx-lm renamed `temp` → `temperature` and dropped `verbose` kwarg.
            text = lm_generate(
                loaded.model,
                loaded.tokenizer,
                prompt=prompt,
                max_tokens=max_tokens,
            )

        text_str = text if isinstance(text, str) else getattr(text, "text", str(text))
        # rough token counts — mlx-lm doesn't always return them
        completion_tokens = len(loaded.tokenizer.encode(text_str)) if hasattr(loaded.tokenizer, "encode") else 0
        prompt_tokens = len(loaded.tokenizer.encode(prompt)) if hasattr(loaded.tokenizer, "encode") else 0
        finish = "length" if completion_tokens >= max_tokens else "stop"
        return GenerationResult(
            text=text_str,
            finish_reason=finish,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
        )

    # ---- image extraction ---------------------------------------------------

    def _extract_images(
        self, messages: list[ChatMessage]
    ) -> tuple[list[str], list[ChatMessage]]:
        """Pull `image_url` parts out of messages, write them to temp files, and
        rewrite the messages so the textual content remains in place.
        """
        image_paths: list[str] = []
        rewritten: list[ChatMessage] = []
        for msg in messages:
            if isinstance(msg.content, str):
                rewritten.append(msg)
                continue

            text_parts: list[str] = []
            for part in msg.content:
                if part.type == "text" and part.text:
                    text_parts.append(part.text)
                elif part.type == "image_url" and part.image_url:
                    path = self._materialize_image(part.image_url.url)
                    if path:
                        image_paths.append(path)

            rewritten.append(
                ChatMessage(role=msg.role, content=" ".join(text_parts).strip() or "")
            )
        return image_paths, rewritten

    @staticmethod
    def _materialize_image(url: str) -> str | None:
        """Decode a data: URL to a NamedTemporaryFile and return its path.
        Remote https:// URLs are out of scope in v1 — return None.
        """
        m = _DATA_URL_RE.match(url.strip())
        if not m:
            logger.warning("unsupported image URL (only data: URIs supported in v1)")
            return None
        ext = m.group("ext")
        try:
            blob = base64.b64decode(m.group("b64"), validate=False)
        except Exception:
            logger.warning("malformed base64 in image data URL")
            return None
        suffix = ".jpg" if ext == "jpg" else f".{ext}"
        f = tempfile.NamedTemporaryFile(prefix="mlx-img-", suffix=suffix, delete=False)
        try:
            f.write(blob)
            f.flush()
            return f.name
        finally:
            f.close()


# ---- small helpers ---------------------------------------------------------


def _messages_to_dicts(messages: list[ChatMessage]) -> list[dict]:
    """Convert pydantic ChatMessage list to plain dicts the chat template expects."""
    out: list[dict] = []
    for m in messages:
        if isinstance(m.content, str):
            out.append({"role": m.role, "content": m.content})
        else:
            # Already extracted text parts in _extract_images, but for safety:
            text_pieces = [p.text for p in m.content if p.type == "text" and p.text]
            out.append({"role": m.role, "content": " ".join(text_pieces).strip()})
    return out


def _coerce_generate_output(raw) -> tuple[str, str, int, int]:
    """mlx-vlm.generate returns strings on older versions and a result object
    on newer ones. Normalize to (text, finish_reason, prompt_tokens, completion_tokens).
    """
    if isinstance(raw, str):
        return raw, "stop", 0, 0
    text = getattr(raw, "text", None) or getattr(raw, "output", None) or str(raw)
    prompt_tokens = int(getattr(raw, "prompt_tokens", 0) or 0)
    completion_tokens = int(getattr(raw, "generation_tokens", 0) or getattr(raw, "completion_tokens", 0) or 0)
    return text, "stop", prompt_tokens, completion_tokens


def manager_for_settings(preset: Preset, vision_override: str, text_override: str, enable_thinking: bool) -> ModelManager:
    return ModelManager(
        preset=preset,
        vision_override=vision_override,
        text_override=text_override,
        enable_thinking=enable_thinking,
    )
