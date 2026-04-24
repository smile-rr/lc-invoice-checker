# syntax=docker/dockerfile:1.7
#
# docling-svc — LC Invoice Checker PDF extractor (CONTRACT.md v1.0 FROZEN).
# Multi-stage build: uv-based dependency install, then slim runtime.
#
# Notes:
# - Python 3.12-slim per resolved Q1.
# - Apt deps: tesseract-ocr + libgl1 (required by Docling's OCR stack).
# - Docling models are warmed in the build stage so the first /extract
#   request doesn't pay the one-off download/init cost. This bloats the
#   image (~1.5-2 GB) but is the right tradeoff for an LC checker demo.
#   `devops` has been notified of the image size.
#
# Build:    docker build -t docling-svc:1.0.0 extractors/docling
# Run:      docker run --rm -p 8081:8081 docling-svc:1.0.0
# Healthck: curl -f http://localhost:8081/health

############################################################
# Stage 1 — builder (uv + dependency install + model warm-up)
############################################################
FROM python:3.12-slim AS builder

# uv from the official published image — pinned by image tag, not :latest,
# so reproducibility is preserved across rebuilds.
COPY --from=ghcr.io/astral-sh/uv:0.4 /uv /usr/local/bin/uv

# Apt deps needed to build AND run Docling (kept here so the wheel step
# can resolve any source-packages that need system libs at install time).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        tesseract-ocr \
        tesseract-ocr-eng \
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

# Install deps first (layer cached across code changes).
# README.md is copied alongside pyproject.toml because hatchling's
# `readme = "README.md"` field is validated at install time — without
# the file present, `uv pip install -e .` fails on metadata build.
COPY pyproject.toml README.md ./
# `uv sync --frozen` requires uv.lock; without one, fall back to `uv lock`
# followed by sync. For V1 we don't commit uv.lock yet — uncomment once
# the lock file is generated and committed.
# COPY uv.lock ./
# RUN uv sync --frozen --no-dev
RUN uv venv /app/.venv \
    && . /app/.venv/bin/activate \
    && uv pip install --no-cache -e .

# Copy application source after deps so edits don't bust the dep layer.
COPY app ./app

# Warm Docling models — runs the library's eager-init path so weights
# download into /root/.cache/docling inside this stage. The cache is
# then copied into the runtime stage.
RUN . /app/.venv/bin/activate \
    && python -c "from app.docling_extractor import warm_up; warm_up(); print('docling warm-up OK')"

############################################################
# Stage 2 — runtime
############################################################
FROM python:3.12-slim AS runtime

# Runtime-only apt deps (build-essential dropped to keep image small).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-eng \
        libgl1 \
        libglib2.0-0 \
        libxext6 \
        libxrender1 \
        libsm6 \
        curl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Non-root user for least-privilege.
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /sbin/nologin app

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    LOG_LEVEL=INFO

WORKDIR /app

# Copy the built venv, warmed model cache, and application code.
COPY --from=builder --chown=app:app /app/.venv /app/.venv
COPY --from=builder --chown=app:app /app/app /app/app
COPY --from=builder --chown=app:app /root/.cache/docling /home/app/.cache/docling

USER app

EXPOSE 8081

# Healthcheck matches the contract's recommended shape (CONTRACT.md §Healthcheck).
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS http://localhost:8081/health || exit 1

# uvicorn with --workers 1 (Docling keeps a global converter singleton —
# multi-worker would re-warm per worker, eating memory). Scale horizontally
# with compose replicas if needed rather than gunicorn workers.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8081", "--workers", "1"]
