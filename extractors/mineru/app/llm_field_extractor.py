"""LLM-based invoice field extraction from Docling/MinerU markdown.

The sidecar holds NO field list and NO system prompt. Both arrive from the
Java client per request — same template + field-pool.yaml that drives the
vision lanes. Sidecar's job is purely:
  1. take markdown + client-supplied prompt
  2. call the configured LLM
  3. return the LLM's structured response, transparently

Endpoint resolution (in priority order):
  1. SIDECAR_LLM_BASE_URL / SIDECAR_LLM_MODEL / SIDECAR_LLM_API_KEY
     — dedicated sidecar LLM, defaults to local Ollama in .env.
  2. LLM_BASE_URL / LLM_MODEL / LLM_API_KEY
     — falls through to whatever Java's Spring AI uses.

Raises {LlmNotConfigured, LlmCallFailed, LlmResponseInvalid} so the caller
can surface the right HTTP status. No silent regex fallback.
"""

from __future__ import annotations

import json
import logging
import os
from typing import Any

import httpx

from .observability import track_llm_generation

# `InvoiceFields` is now a plain `dict[str, Any]` alias — see contract.py.
# We don't import it; we just deal with dicts directly.

logger = logging.getLogger(__name__)


class LlmNotConfigured(RuntimeError):
    """Raised when neither SIDECAR_LLM_* nor LLM_* env vars are set."""


class LlmCallFailed(RuntimeError):
    """Raised when the LLM HTTP call returns a non-2xx status or times out."""


class LlmResponseInvalid(RuntimeError):
    """Raised when the LLM response cannot be parsed as JSON."""


def llm_extract_fields(markdown: str, prompt: str) -> tuple[dict[str, Any], float]:
    """Call the text LLM with a client-supplied prompt to extract fields.

    Both `markdown` (the docling/mineru-rendered invoice text) and `prompt`
    (the system instructions, including the field list) are MANDATORY.
    The sidecar does not own a prompt template or a field list.

    Returns (fields_dict, confidence) — `fields_dict` is whatever key/value
    map the LLM produced inside its response's `"fields"` envelope; the
    sidecar passes it through to Java unchanged so canonical keys
    (lc_number, beneficiary_name, credit_amount, …) reach Java's
    InvoiceFieldMapper alias resolution at the top level. Confidence is
    the LLM's own self-rating from the response envelope's "confidence"
    key (defaults to 0.8 if omitted).

    Raises LlmNotConfigured / LlmCallFailed / LlmResponseInvalid on failure.
    """
    if not prompt or not prompt.strip():
        raise ValueError("`prompt` is required and must be non-empty")

    base_url = (os.getenv("SIDECAR_LLM_BASE_URL") or os.getenv("LLM_BASE_URL") or "").rstrip("/")
    api_key = os.getenv("SIDECAR_LLM_API_KEY") or os.getenv("LLM_API_KEY") or ""
    model = os.getenv("SIDECAR_LLM_MODEL") or os.getenv("LLM_MODEL") or ""

    if not base_url or not model:
        raise LlmNotConfigured(
            "LLM endpoint not configured: set SIDECAR_LLM_BASE_URL+SIDECAR_LLM_MODEL "
            "(or LLM_BASE_URL+LLM_MODEL) in .env"
        )

    payload = {
        "model": model,
        "temperature": 0.0,
        "messages": [
            {"role": "system", "content": prompt.strip()},
            {"role": "user", "content": f"Invoice text:\n\n{markdown}"},
        ],
    }
    headers: dict[str, str] = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    # Wrap the LLM call in a Langfuse Generation. When the inbound /extract
    # request carries a `traceparent` header from the Java pipeline, the
    # FastAPI auto-instrumentor has already pinned the OTel context to that
    # parent trace, so this generation lands under the same Langfuse trace
    # as the Java rule checks.
    with track_llm_generation(
        name="mineru.field-extract",
        model=model,
        input_payload=payload["messages"],
        metadata={"extractor": "mineru", "base_url": base_url},
    ) as gen:
        try:
            with httpx.Client(timeout=120.0) as client:
                resp = client.post(f"{base_url}/chat/completions", json=payload, headers=headers)
                resp.raise_for_status()
            response_body = resp.json()
            raw_content: str = response_body["choices"][0]["message"]["content"]
        except Exception as exc:
            raise LlmCallFailed(f"LLM call to {base_url}: {exc}") from exc

        # Token usage from the OpenAI-compatible response — Langfuse computes
        # cost from model + token counts when pricing is configured.
        usage = response_body.get("usage") or {}
        gen.update(
            output=raw_content,
            usage_details={
                "input": usage.get("prompt_tokens"),
                "output": usage.get("completion_tokens"),
                "total": usage.get("total_tokens"),
            },
        )

    try:
        data: dict[str, Any] = _parse_json(raw_content)
    except Exception as exc:
        raise LlmResponseInvalid(f"LLM returned non-JSON content: {raw_content[:200]!r}") from exc

    fields = _unwrap_fields(data)

    # LLM-emitted self-confidence (the prompt asks for it in the envelope).
    # 0.8 is the "did extract something but didn't self-rate" default — same
    # value the Java VisionLlmExtractor uses for its lanes.
    raw_conf = data.get("confidence")
    try:
        confidence = float(raw_conf) if raw_conf is not None else 0.8
    except (TypeError, ValueError):
        confidence = 0.8
    confidence = max(0.0, min(1.0, confidence))

    non_null = sum(1 for v in fields.values() if v not in (None, "", []))
    logger.info(
        "LLM extraction succeeded",
        extra={
            "event": "llm_extract",
            "non_null": non_null,
            "field_count": len(fields),
            "llm_confidence": confidence,
        },
    )
    return fields, confidence


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _parse_json(text: str) -> dict[str, Any]:
    """Strip optional code fences the LLM may have added, then parse."""
    text = text.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        text = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])
    return json.loads(text)


def _unwrap_fields(data: dict[str, Any]) -> dict[str, Any]:
    """Pull the field dict out of the LLM's JSON envelope and pass through.

    Two response shapes accepted:
      1) Shared prompt (rendered from invoice-extract.st on Java side):
         { "fields": {...}, "extraction_warnings": [...], "confidence": 0.0 }
      2) Older flat shape (legacy / direct curl tests).

    The sidecar holds NO field schema — whatever the LLM produced flows
    straight to Java, where InvoiceFieldMapper resolves canonical and
    legacy keys via field-pool's invoice_aliases table.
    """
    return data["fields"] if isinstance(data.get("fields"), dict) else data
