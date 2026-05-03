# Alibaba Bailian — Free-Tier Model Reference

All models below are confirmed free-tier on your Bailian account (verified 2026-05-03).
No quota columns — check current quota at the Bailian console.
All models use the same base URL: `https://dashscope.aliyuncs.com/compatible-mode/v1`

---

## How to use this file

Two env-var blocks to fill in `.env` / `.env.ubuntu`:

```bash
# ── Text LLM (rule checks) ─────────────────────────────────────
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_API_KEY=<your-key>
LLM_MODEL=<pick from TEXT section below>

# ── Vision LLM cloud slots (invoice extraction) ────────────────
CLOUD_LLM_VL_MODEL=<pick from VISION section below>   # slot 1
CLOUD_LLM_VL2_MODEL=<pick from VISION section below>  # slot 2
CLOUD_LLM_VL3_MODEL=<pick from VISION section below>  # slot 3 (disabled by default)
```

`enable_thinking: false` is already applied globally in `ChatClientConfig.extraBody()` —
safe no-op for non-Qwen3, suppresses thinking tokens for all Qwen3-family models.

---

## TEXT MODELS (rule checks — no image input needed)

These models receive text prompts only (LC fields + invoice fields as structured text).
All Qwen3-family models have thinking mode; it is suppressed automatically.

> **Do NOT use text models as vision extractor slots.** DeepSeek and MiniMax are text-only:
> DashScope either strips the `image_url` blocks before forwarding, or the model ignores them.
> The model then sees only the text prompt (no actual invoice), returns `confidence: 0`, and
> the UI shows 0% — not a parser bug, just a model with no image input.

### Fast / MoE-small — lowest latency, good for most compliance checks

| Model ID | Expires | Notes |
|---|---|---|
| `qwen3.5-flash` | 2026-05-25 | Fastest Qwen3.5; MoE efficient |
| `qwen3.5-flash-2026-02-23` | 2026-05-25 | Snapshot of above |
| `qwen3.6-flash` | 2026-07-17 | Fastest Qwen3.6; vision-capable too (see below) |
| `qwen3.6-flash-2026-04-16` | 2026-07-17 | Snapshot of above |
| `deepseek-v4-flash` | 2026-07-24 | DeepSeek fast tier; no thinking mode; **text-only — not vision** |
| `qwen3.5-35b-a3b` | 2026-05-25 | MoE 35B total / 3B active — very fast inference |
| `qwen3.6-35b-a3b` | 2026-07-17 | Same pattern, Qwen3.6 generation |

### Mid-size / Balanced — good quality, moderate latency

| Model ID | Expires | Notes |
|---|---|---|
| `qwen3.5-27b` | 2026-05-25 | Dense 27B; solid reasoning |
| `qwen3.6-27b` | 2026-07-23 | Dense 27B Qwen3.6; vision-capable too |
| `glm-5` | 2026-05-18 | GLM-5 from Zhipu AI |
| `glm-5.1` | 2026-07-14 | GLM-5.1 updated; longer validity |
| `MiniMax-M2.5` | 2026-05-25 | MiniMax flagship; likely multimodal |
| `kimi-k2.6` | 2026-07-21 | Moonshot Kimi; likely vision-capable |
| `gui-plus-2026-02-26` | 2026-06-15 | GUI-agent model; likely vision-capable |
| `qwen-flash-character-2026-02-26` | 2026-05-30 | Character/roleplay optimised |

### Large / MoE-large — best reasoning, higher latency

| Model ID | Expires | Notes |
|---|---|---|
| `qwen3.5-plus` | 2026-05-18 | Qwen3.5 flagship text; expires soon |
| `qwen3.5-plus-2026-02-15` | 2026-05-18 | Snapshot — same expiry |
| `qwen3.5-plus-2026-04-20` | 2026-07-23 | Latest snapshot; longest validity |
| `qwen3.5-122b-a10b` | 2026-05-25 | MoE 122B / 10B active |
| `qwen3.5-397b-a17b` | 2026-05-18 | MoE 397B / 17B active — most powerful Qwen3.5 |
| `deepseek-v4-pro` | 2026-07-24 | DeepSeek strong-tier; excellent UCP reasoning; longest validity; **text-only — not vision** |
| `qwen3-coder-next` | 2026-05-05 | Code-focused; almost expired |

---

## VISION / MULTIMODAL MODELS (invoice image extraction)

These models accept image input (base64 JPEG pages) via `/v1/chat/completions`.
Use for `CLOUD_LLM_VL_MODEL`, `CLOUD_LLM_VL2_MODEL`, `CLOUD_LLM_VL3_MODEL`.

**Confirmed vision-capable** (Qwen3.6 family has unified vision-language):

### Fast — slot 2 or slot 3 (reference / comparison)

| Model ID | Expires | Notes |
|---|---|---|
| `qwen3.6-flash` | 2026-07-17 | Fast, cheap; recommended for slot 2 |
| `qwen3.6-flash-2026-04-16` | 2026-07-17 | Snapshot of above |
| `qwen3.6-35b-a3b` | 2026-07-17 | MoE fast; vision-capable |

### Balanced / Flagship — slot 1 (primary cloud reference)

| Model ID | Expires | Notes |
|---|---|---|
| `qwen3.6-plus-2026-04-02` | 2026-07-02 | Recommended slot 1 (fresh quota) |
| `qwen3.6-plus` | 2026-07-02 | Same model; 85% quota used |
| `qwen3.6-27b` | 2026-07-23 | Dense 27B; good extraction quality |

### Highest capability (use if extraction quality matters most)

| Model ID | Expires | Notes |
|---|---|---|
| `qwen3.6-max-preview` | 2026-07-20 | Most capable Qwen3.6; preview access |

**Likely vision-capable** (based on provider specs — NOT confirmed via DashScope compatible endpoint):

| Model ID | Expires | Notes |
|---|---|---|
| `MiniMax-M2.5` | 2026-05-25 | **400 error in testing** — rejects `response_format` and `enable_thinking`; image input unconfirmed via DashScope |
| `kimi-k2.6` | 2026-07-21 | Kimi has vision natively — unverified via DashScope compatible endpoint |
| `gui-plus-2026-02-26` | 2026-06-15 | GUI model; designed for screen understanding — unverified |

> **Schema compatibility note**: The vision extractor sends `response_format: json_object` and `enable_thinking: false` only for Qwen-family models (auto-detected by model name prefix `qwen`). Non-Qwen models receive a clean standard request; JSON output relies on the prompt instruction only. Models not in the Qwen family that still return 400 likely don't support `image_url` content blocks via DashScope at all — use confirmed vision models above.

---

## Recommended configurations by scenario

### Current default (cost-free, production-stable)
```bash
LLM_MODEL=qwen3.5-plus                      # text checks
CLOUD_LLM_VL_MODEL=qwen3.6-plus-2026-04-02  # vision slot 1 (flagship)
CLOUD_LLM_VL2_MODEL=qwen3.6-flash           # vision slot 2 (fast reference)
```

### After qwen3.5-plus quota expires (switch to longer-validity)
```bash
LLM_MODEL=qwen3.5-plus-2026-04-20           # expires 2026-07-23
# or
LLM_MODEL=deepseek-v4-pro                   # expires 2026-07-24, excellent reasoning
```

### Max reasoning quality (slower, larger models)
```bash
LLM_MODEL=qwen3.5-397b-a17b                 # largest Qwen3.5 MoE
# or
LLM_MODEL=deepseek-v4-pro                   # best for UCP logic reasoning
```

### Fastest / lowest cost (flash models)
```bash
LLM_MODEL=qwen3.5-flash                     # or qwen3.6-flash
LLM_MODEL=deepseek-v4-flash                 # DeepSeek fast tier
```

### Three parallel vision slots (enable slot 3 in .env)
```bash
EXTRACTOR_CLOUD_LLM_VL3_ENABLED=true
CLOUD_LLM_VL_MODEL=qwen3.6-plus-2026-04-02  # flagship
CLOUD_LLM_VL2_MODEL=qwen3.6-flash           # fast
CLOUD_LLM_VL3_MODEL=qwen3.6-27b             # alternative size
```

---

## Model expiry quick view (sorted, soonest first)

| Expires | Models |
|---|---|
| 2026-05-05 | `qwen3-coder-next` |
| 2026-05-18 | `qwen3.5-plus`, `qwen3.5-plus-2026-02-15`, `qwen3.5-397b-a17b`, `glm-5` |
| 2026-05-25 | `qwen3.5-flash`, `qwen3.5-flash-2026-02-23`, `qwen3.5-27b`, `qwen3.5-35b-a3b`, `qwen3.5-122b-a10b`, `MiniMax-M2.5` |
| 2026-05-30 | `qwen-flash-character-2026-02-26` |
| 2026-06-15 | `gui-plus-2026-02-26` |
| 2026-07-02 | `qwen3.6-plus`, `qwen3.6-plus-2026-04-02` |
| 2026-07-14 | `glm-5.1` |
| 2026-07-17 | `qwen3.6-flash`, `qwen3.6-flash-2026-04-16`, `qwen3.6-35b-a3b` |
| 2026-07-20 | `qwen3.6-max-preview` |
| 2026-07-21 | `kimi-k2.6` |
| 2026-07-23 | `qwen3.5-plus-2026-04-20`, `qwen3.6-27b` |
| 2026-07-24 | `deepseek-v4-pro`, `deepseek-v4-flash` |
