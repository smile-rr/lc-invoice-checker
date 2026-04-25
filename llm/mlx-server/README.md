# mlx-server

Local MLX-backed LLM service. Exposes an **OpenAI-compatible** `/v1/chat/completions` endpoint so `lc-checker-svc` can use it as a drop-in replacement for DashScope/Qwen by changing only `LLM_BASE_URL` and `VISION_BASE_URL`.

Native uvicorn process — **not Dockerized**. MLX needs direct Apple Silicon access; Docker for Mac runs a Linux VM that can't reach the GPU/unified memory.

## What it serves

| Endpoint | Shape |
|---|---|
| `POST /v1/chat/completions` | OpenAI chat-completions, supports image content parts (`image_url` with `data:` URIs) |
| `GET  /v1/models`           | Lists registered models (qwen3-vl-4b/8b, qwen3-4b) |
| `GET  /health`              | `{status, preset, model_loaded, peak_memory_gb}` — always 200 |

Streaming (`stream=true`) is rejected with 400 in v1 — the Java pipeline doesn't need it.

## Setup

```bash
cd llm/mlx-server
python3.11 -m venv .venv && source .venv/bin/activate
pip install -e .
cp .env.example .env
```

First start downloads the preset's model from HuggingFace to `~/.cache/huggingface/hub/`:
- `light`     ≈ 3 GB (Qwen3-VL-4B 4-bit)
- `balanced`  ≈ 5 GB (Qwen3-VL-8B 4-bit)
- `quality`   ≈ 8 GB (8B VLM + 4B LM, swapped serially)

## Run

```bash
# foreground
uvicorn app.main:app --host 127.0.0.1 --port 8088

# or via repo Makefile (background, logs to /tmp/mlx-server.log)
make llm-up
make llm-logs       # tail
make llm-health     # probe
make llm-down
```

## Smoke tests

```bash
# text-only
curl -s http://127.0.0.1:8088/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen3-vl-4b","messages":[{"role":"user","content":"reply with: pong"}]}' \
  | jq .choices[0].message.content

# vision (base64 PNG)
IMG_B64=$(base64 -i /path/to/page.png)
curl -s http://127.0.0.1:8088/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"qwen3-vl-4b\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"return invoice number only\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,${IMG_B64}\"}}]}]}" \
  | jq .choices[0].message.content
```

## Pointing lc-checker-svc at this server

In the repo-root `.env`:

```env
LLM_BASE_URL=http://localhost:8088/v1
LLM_MODEL=qwen3-vl-4b
LLM_API_KEY=local

VISION_BASE_URL=http://localhost:8088/v1
VISION_MODEL=qwen3-vl-4b
VISION_API_KEY=local
```

`LLM_API_KEY` / `VISION_API_KEY` need to be non-empty (Spring AI requires it) but the server ignores the value.

## Adding a new model

Edit `app/registry.py` — add one entry to `MODEL_REGISTRY`. No other code changes.

## Layout

```
app/
├── main.py        FastAPI app + lifespan model warmup
├── routes.py      /v1/chat/completions, /v1/models, /health
├── schemas.py     OpenAI-compatible Pydantic models
├── manager.py     ModelManager — load/unload/generate, single-VLM-both-roles
├── registry.py    MODEL_REGISTRY + PRESETS
└── settings.py    Pydantic Settings reading .env
```
