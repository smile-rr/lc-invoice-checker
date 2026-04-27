"""Langfuse + OpenTelemetry tracing for the mineru sidecar.

Goals
  • Every LLM call inside this service shows up as a Langfuse Generation
    (type=generation), with prompt, completion, token usage, and cost.
  • When Java sets the `X-Session-Id` header on the POST to /extract, the
    generation joins Java's session-level trace via langfuse.trace.id, so
    the whole pipeline appears as one Langfuse trace per session_id.
  • Graceful degradation: if `LANGFUSE_PUBLIC_KEY` is unset, the helpers
    are no-ops so local development without Langfuse keeps working.

Why we drive OTel directly instead of using `langfuse.start_as_current_generation`
  Different Langfuse Python SDK versions set different combinations of OTel
  span attributes; we've seen LLM HTTP calls render as plain "POST" spans
  in Langfuse instead of as Generations because the SDK didn't emit
  `langfuse.observation.type=generation`. Owning the attribute list
  ourselves makes the span shape deterministic.

Usage
    init_observability(app)                           # call from main.py
    with track_llm_generation(name="...", model=...,
                              input=...) as gen:
        ... call LLM via httpx ...
        gen.update(output=..., usage_details={"input": ..., "output": ...})
"""

from __future__ import annotations

import base64
import contextvars
import json
import logging
import os
from contextlib import contextmanager
from typing import Any, Iterator

logger = logging.getLogger(__name__)

_TRACING_ENABLED = False
_TRACER: Any = None

# Per-request session id, populated by the FastAPI middleware below from the
# `X-Session-Id` header that Java sends on every /extract call. Read by
# track_llm_generation so the resulting Generation tags `langfuse.trace.id`
# with the same value Java does — that's what merges Java rule spans + Python
# LLM generations into ONE Langfuse trace per session.
_session_id: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "session_id", default=None
)

# W3C TraceContext carrier captured from inbound request headers. Java's
# observation-aware RestClient.Builder injects `traceparent` (and optionally
# `tracestate`) on every outbound call to /extract; we extract it here and
# pass it as the parent context when starting our field-extract span. The
# resulting span becomes a true child of Java's HTTP-client span (which is
# itself a child of the pipeline's invoice_extract stage span), so the
# Langfuse trace tree shows the proper nested hierarchy instead of two
# orphan top-level observations.
_otel_carrier: contextvars.ContextVar[dict[str, str] | None] = contextvars.ContextVar(
    "otel_carrier", default=None
)


def current_session_id() -> str | None:
    return _session_id.get()


def _langfuse_configured() -> bool:
    return bool(os.getenv("LANGFUSE_PUBLIC_KEY")) and bool(os.getenv("LANGFUSE_SECRET_KEY"))


def init_observability(app: Any) -> None:
    """Configure OTel + Langfuse OTLP exporter and instrument FastAPI.

    Safe to call once at app construction. No-op when env vars are missing.
    """
    global _TRACING_ENABLED, _TRACER

    if not _langfuse_configured():
        logger.info(
            "Langfuse env vars not set; tracing disabled",
            extra={"event": "langfuse_disabled"},
        )
        return

    try:
        from opentelemetry import trace as otel_trace
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor

        host = os.getenv("LANGFUSE_HOST", "http://localhost:3000").rstrip("/")
        endpoint = os.getenv("LANGFUSE_OTEL_ENDPOINT", f"{host}/api/public/otel/v1/traces")
        public_key = os.getenv("LANGFUSE_PUBLIC_KEY", "")
        secret_key = os.getenv("LANGFUSE_SECRET_KEY", "")
        auth = base64.b64encode(f"{public_key}:{secret_key}".encode()).decode()

        # Service identifier. Each sidecar overrides via OTEL_SERVICE_NAME if
        # they want, otherwise defaults to the package name.
        service_name = os.getenv("OTEL_SERVICE_NAME", "mineru-svc")

        provider = TracerProvider(resource=Resource.create({"service.name": service_name}))
        provider.add_span_processor(
            BatchSpanProcessor(
                OTLPSpanExporter(endpoint=endpoint, headers={"Authorization": f"Basic {auth}"})
            )
        )
        try:
            otel_trace.set_tracer_provider(provider)
        except Exception:  # noqa: BLE001
            pass
        _TRACER = provider.get_tracer(service_name)
        _TRACING_ENABLED = True

        # FastAPIInstrumentor is intentionally NOT enabled. Reasons:
        #   1. /health is hit every ~30s by Docker's healthcheck — auto-
        #      instrumentation would produce ~2880 noise traces/day per
        #      sidecar in Langfuse.
        #   2. The /extract inbound span itself is redundant: it carries
        #      no `langfuse.trace.id`, so it lands in a SEPARATE Langfuse
        #      trace from the Java session trace anyway. The only useful
        #      Python-side span is the manual Generation produced by
        #      `track_llm_generation` below, which self-joins via the
        #      session id captured from the X-Session-Id header.

        # Capture X-Session-Id + W3C TraceContext on every inbound request.
        # Both flow into track_llm_generation so the LLM Generation gets
        # langfuse.trace.id from session id AND nests under Java's pipeline
        # span via traceparent.
        @app.middleware("http")
        async def _capture_request_context(request, call_next):
            sid = request.headers.get("x-session-id")
            sid_token = _session_id.set(sid) if sid else None
            carrier: dict[str, str] = {}
            tp = request.headers.get("traceparent")
            ts = request.headers.get("tracestate")
            if tp:
                carrier["traceparent"] = tp
            if ts:
                carrier["tracestate"] = ts
            carrier_token = _otel_carrier.set(carrier) if carrier else None
            try:
                return await call_next(request)
            finally:
                if sid_token is not None:
                    _session_id.reset(sid_token)
                if carrier_token is not None:
                    _otel_carrier.reset(carrier_token)

        logger.info(
            "Langfuse tracing enabled",
            extra={
                "event": "langfuse_enabled",
                "endpoint": endpoint,
                "service_name": service_name,
            },
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "Langfuse init failed: %s", exc,
            extra={"event": "langfuse_init_failed"},
        )


def _resolve_parent_context() -> Any | None:
    """Pick the right parent for a new span.

    1. If a local span is already active and recording, return None so OTel
       defaults to current-context inheritance — keeps in-service nesting
       correct (pdf-parse + llm-extract become children of the outer extract
       span, not siblings of it).
    2. Otherwise, if Java sent a W3C carrier (`traceparent`), extract it so
       the outermost local span attaches to Java's pipeline trace.
    3. Otherwise, None — span starts as a root.
    """
    try:
        from opentelemetry import trace as otel_trace

        current = otel_trace.get_current_span()
        ctx = current.get_span_context() if current is not None else None
        if ctx is not None and getattr(ctx, "is_valid", False) and ctx.trace_id != 0:
            return None
    except Exception:  # noqa: BLE001
        pass
    carrier = _otel_carrier.get()
    if not carrier:
        return None
    try:
        from opentelemetry import propagate as _otel_propagate

        return _otel_propagate.extract(carrier)
    except Exception:  # noqa: BLE001
        return None


@contextmanager
def track_span(
    name: str,
    attributes: dict[str, Any] | None = None,
) -> Iterator[Any]:
    """Wrap a non-LLM operation as a regular OTel span (Langfuse renders it
    as an observation of type=span, NOT generation).

    Used for the outer `<extractor>.extract` envelope and the inner
    `<extractor>.pdf-parse` step so reviewers see PDF-parse vs LLM time
    side-by-side under one Java pipeline trace.

    Yields a tiny shim with `.set_attribute(key, value)` so callers can
    layer in metadata that's only known after the wrapped work finishes
    (page count, duration, library version, …).
    """
    if not _TRACING_ENABLED or _TRACER is None:
        yield _NoOpSpan()
        return

    span_cm = None
    span = None
    sid = _session_id.get()
    parent_ctx = _resolve_parent_context()
    try:
        span_cm = _TRACER.start_as_current_span(name, context=parent_ctx) \
            if parent_ctx is not None else _TRACER.start_as_current_span(name)
        span = span_cm.__enter__()
        if sid:
            span.set_attribute("langfuse.trace.id", sid)
            span.set_attribute("langfuse.session.id", sid)
            span.set_attribute("langfuse.trace.name", f"LC Check {sid}")
        for k, v in (attributes or {}).items():
            try:
                span.set_attribute(k, _coerce_attr(v))
            except Exception:  # noqa: BLE001
                pass
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "track_span start failed: %s", exc,
            extra={"event": "track_span_start_failed"},
        )
        span_cm = None
        span = None

    shim = _SpanShim(span)
    try:
        yield shim
    finally:
        if span_cm is not None:
            try:
                span_cm.__exit__(None, None, None)
            except Exception as exc:  # noqa: BLE001
                logger.warning(
                    "track_span end failed: %s", exc,
                    extra={"event": "track_span_end_failed"},
                )


def _coerce_attr(v: Any) -> Any:
    if isinstance(v, (str, int, float, bool)):
        return v
    return str(v)


@contextmanager
def track_llm_generation(
    name: str,
    model: str,
    input_payload: Any,
    metadata: dict[str, Any] | None = None,
) -> Iterator[Any]:
    """Wrap an LLM call as a Langfuse Generation.

    Creates a single OTel span tagged with everything Langfuse needs to
    render it as a Generation observation:
      • langfuse.observation.type = "generation"  (THIS is what missing in
        the previous SDK-driven version made the span show up as plain HTTP)
      • langfuse.observation.input  = JSON of the prompt
      • langfuse.observation.output = JSON of the completion (set via
        gen.update(...) inside the with block)
      • gen_ai.request.model + gen_ai.usage.* tokens for cost computation
      • langfuse.trace.id / langfuse.session.id for the session-merge trick

    Yields a tiny shim with `.update(output=..., usage_details=...)`. Always
    yields exactly once, even on internal failure — never raises into the
    caller; tracing must not break extraction.
    """
    if not _TRACING_ENABLED or _TRACER is None:
        yield _NoOpGeneration()
        return

    span_cm = None
    span = None
    sid = _session_id.get()
    # When called inside an outer `track_span`, this returns None so OTel's
    # current-context inheritance puts the Generation under the extract
    # span (not under Java's HTTP span as a sibling).
    parent_ctx = _resolve_parent_context()
    try:
        span_cm = _TRACER.start_as_current_span(name, context=parent_ctx) \
            if parent_ctx is not None else _TRACER.start_as_current_span(name)
        span = span_cm.__enter__()
        # Langfuse-recognised attributes — explicit so the type is right
        # regardless of any SDK helper.
        span.set_attribute("langfuse.observation.type", "generation")
        if sid:
            span.set_attribute("langfuse.trace.id", sid)
            span.set_attribute("langfuse.session.id", sid)
            span.set_attribute("langfuse.trace.name", f"LC Check {sid}")
        if model:
            span.set_attribute("gen_ai.request.model", model)
            span.set_attribute("gen_ai.system", "openai-compatible")
            span.set_attribute("langfuse.observation.model.name", model)
        try:
            span.set_attribute(
                "langfuse.observation.input", _safe_json(input_payload)
            )
        except Exception:  # noqa: BLE001
            pass
        for k, v in (metadata or {}).items():
            try:
                span.set_attribute(f"langfuse.observation.metadata.{k}", str(v))
            except Exception:  # noqa: BLE001
                pass
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "Langfuse generation start failed: %s", exc,
            extra={"event": "langfuse_generation_start_failed"},
        )
        span_cm = None
        span = None

    shim = _OtelGeneration(span)

    try:
        yield shim
    finally:
        if span_cm is not None:
            try:
                # If shim received output via .update(), emit it onto the span
                # before ending. Capturing here (not eagerly in update) keeps
                # the attribute set adjacent to the span end so Langfuse sees
                # both arrival timestamps in one batch.
                if shim._output is not None and span is not None:
                    span.set_attribute(
                        "langfuse.observation.output", _safe_json(shim._output)
                    )
                if shim._usage and span is not None:
                    in_t = shim._usage.get("input")
                    out_t = shim._usage.get("output")
                    tot_t = shim._usage.get("total")
                    if in_t is not None:
                        span.set_attribute("gen_ai.usage.input_tokens", int(in_t))
                    if out_t is not None:
                        span.set_attribute("gen_ai.usage.output_tokens", int(out_t))
                    if tot_t is not None:
                        span.set_attribute("gen_ai.usage.total_tokens", int(tot_t))
                span_cm.__exit__(None, None, None)
            except Exception as exc:  # noqa: BLE001
                logger.warning(
                    "Langfuse generation end failed: %s", exc,
                    extra={"event": "langfuse_generation_end_failed"},
                )


def _safe_json(v: Any) -> str:
    try:
        return json.dumps(v, ensure_ascii=False, default=str)
    except Exception:  # noqa: BLE001
        return str(v)


class _OtelGeneration:
    """Shim that buffers .update(output=..., usage_details=...) until span
    end, then writes them as OTel attributes."""

    __slots__ = ("_span", "_output", "_usage")

    def __init__(self, span: Any | None) -> None:
        self._span = span
        self._output: Any | None = None
        self._usage: dict[str, Any] | None = None

    def update(self, **kwargs: Any) -> None:
        if "output" in kwargs:
            self._output = kwargs["output"]
        if "usage_details" in kwargs and isinstance(kwargs["usage_details"], dict):
            self._usage = kwargs["usage_details"]


class _NoOpGeneration:
    """Stand-in when tracing is disabled; same surface as _OtelGeneration."""

    __slots__ = ()

    def update(self, **_kwargs: Any) -> None:
        return None


class _SpanShim:
    """Yielded by `track_span`; lets callers attach attributes after the
    fact (e.g. duration_ms once the wrapped work has completed)."""

    __slots__ = ("_span",)

    def __init__(self, span: Any | None) -> None:
        self._span = span

    def set_attribute(self, key: str, value: Any) -> None:
        if self._span is None:
            return
        try:
            self._span.set_attribute(key, _coerce_attr(value))
        except Exception:  # noqa: BLE001
            pass


class _NoOpSpan:
    """Stand-in when tracing is disabled; matches `_SpanShim` surface."""

    __slots__ = ()

    def set_attribute(self, *_args: Any, **_kwargs: Any) -> None:
        return None
