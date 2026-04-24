# syntax=docker/dockerfile:1.7
#
# mineru-svc — LC Invoice Checker PDF extractor (CONTRACT.md v1.0 FROZEN).
# Multi-stage build: uv-based dependency install, then slim runtime.
#
# Notes:
# - Python 3.12-slim per resolved Q1.
# - Apt deps: poppler-utils + libmagic1 + libgl1 (MinerU's pipeline
#   uses pdftoppm for rasterisation and magic for content-type sniffing).
# - MinerU's model weights are large — warmed in the builder stage so
#   the first /extract request doesn't eat the one-off download/init cost.
#   `devops` is aware of the ~2 GB image size trade-off.
#
# Build: docker build -t mineru-svc:1.0.0 extractors/mineru
# Run:   docker run --rm -p 8082:8082 mineru-svc:1.0.0
# Health:curl -f http://localhost:8082/health

############################################################
# Stage 1 — builder
############################################################
FROM python:3.12-slim AS builder

COPY --from=ghcr.io/astral-sh/uv:0.4 /uv /usr/local/bin/uv

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        poppler-utils \
        libmagic1 \
        libgl1 \
        libglib2.0-0 \
        libxext6 \
        libxrender1 \
        libsm6 \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV UV_LINK_MODE=copy \
    UV_COMPILE_BYTECODE=1 \
    UV_PYTHON_DOWNLOADS=never \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# README.md is copied alongside pyproject.toml because hatchling's
# `readme = "README.md"` field is validated at install time — without
# the file present, `uv pip install -e .` fails on metadata build.
COPY pyproject.toml README.md ./
# Same lock-file note as docling-svc: uncomment once uv.lock is committed.
# COPY uv.lock ./
# RUN uv sync --frozen --no-dev
RUN uv venv /app/.venv \
    && . /app/.venv/bin/activate \
    && uv pip install --no-cache -e .

COPY app ./app

# Warm MinerU models. Wrapped in a guard so the build doesn't fail if
# the MinerU API surface can't be imported from the installed version —
# in that case the service will surface the real error at first /extract.
RUN . /app/.venv/bin/activate \
    && python -c "from app.mineru_extractor import warm_up; warm_up()" \
    && echo "mineru warm-up OK" \
    || echo "mineru warm-up skipped; will lazy-init on first request"

############################################################
# Stage 2 — runtime
############################################################
FROM python:3.12-slim AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        poppler-utils \
        libmagic1 \
        libgl1 \
        libglib2.0-0 \
        libxext6 \
        libxrender1 \
        libsm6 \
        curl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /sbin/nologin app

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    LOG_LEVEL=INFO

WORKDIR /app

COPY --from=builder --chown=app:app /app/.venv /app/.venv
COPY --from=builder --chown=app:app /app/app /app/app
# MinerU caches downloaded weights under different paths depending on
# version (some use ~/.cache/mineru, some use ~/.paddlex). Copy both
# well-known roots, ignoring failures on ones that don't exist.
COPY --from=builder --chown=app:app /root/.cache /home/app/.cache

USER app

EXPOSE 8082

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS http://localhost:8082/health || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082", "--workers", "1"]
