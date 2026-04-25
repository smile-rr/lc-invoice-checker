# PDF Extraction — Testing Guide

This directory contains the test fixtures and this guide documents all 3 ways to verify PDF extraction.

---

## Method 1 — Docling Extractor (`:8081`)

**What it tests:** Pure Docling layout + OCR pipeline. Returns `raw_markdown`, `raw_text`, `confidence`, `is_image_based`, and 18 structured fields.

**Prerequisites:**
```bash
# Start docling-svc (warm-up takes ~1 min on first run as models download)
docker run --rm -p 8081:8081 \
  -v lc-checker-docling-hf-cache:/hf-cache \
  -e HF_TOKEN=$(grep HF_TOKEN .env | cut -d= -f2) \
  --env-file .env \
  --name docling-svc \
  lc-checker/docling-svc:dev
```

**Test:**
```bash
curl -s -X POST http://localhost:8081/extract \
  -F "file=@sample_invoice.pdf" \
  -o /tmp/response-docling.json

# View markdown output
cat /tmp/response-docling.json | python3 -c "
import json, sys
d = json.load(sys.stdin)
print('=== raw_markdown ===')
print(d.get('raw_markdown', '')[:800])
print()
print('=== extracted fields ===')
f = d.get('fields', {})
for k, v in f.items():
    if v:
        print(f'  {k}: {v}')
"
```

**Expected fields:** invoice_number, invoice_date, seller_name, buyer_name, goods_description, quantity, unit_price, total_amount, currency, lc_reference, trade_terms, port_of_loading, port_of_discharge, signed.

---

## Method 2 — MinerU Extractor (`:8082`)

**What it tests:** MinerU `>= 3.0` pipeline (doc_analyze_streaming). Fallback extractor for complex/scanned PDFs.

**Prerequisites:**
```bash
# Start mineru-svc (models download on first run, ~2-5 min)
docker run --rm -p 8082:8082 \
  -v lc-checker-mineru-hf-cache:/hf-cache \
  -e HF_TOKEN=$(grep HF_TOKEN .env | cut -d= -f2) \
  --env-file .env \
  --name mineru-svc \
  lc-checker/mineru-svc:dev
```

**Test:**
```bash
curl -s -X POST http://localhost:8082/extract \
  -F "file=@sample_invoice.pdf" \
  -o /tmp/response-mineru.json

# View markdown output
cat /tmp/response-mineru.json | python3 -c "
import json, sys
d = json.load(sys.stdin)
print('=== raw_markdown ===')
print(d.get('raw_markdown', '')[:800])
print()
print('=== confidence (non-null fields / 18) ===')
print(d.get('confidence'))
"
```

**Note:** MinerU does not expose native confidence — `confidence = non_null_field_count / 18`. If below 0.80, the Java router falls back to MinerU.

---

## Method 3 — Vision LLM + Full Pipeline (`:8080`)

**What it tests:** End-to-end LC compliance check — MT700 parsing + PDF extraction + LLM-powered field extraction + UCP 600/ISBP 821 rule checking.

**Prerequisites:**
```bash
# 1. Ollama must be running on your Mac (host)
ollama list  # confirm qwen3-vl:2b is available

# 2. Set in .env (Docker Desktop on Mac uses host.docker.internal)
VISION_BASE_URL=http://host.docker.internal:11434/v1
VISION_MODEL=qwen3-vl:2b

# 3. Rebuild & start all services
docker compose up -d --build

# 4. Wait for health checks
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8081/health
```

**Test full LC check:**
```bash
curl -s -X POST http://localhost:8080/api/v1/lc-check \
  -F "lc=@sample_mt700.txt;type=text/plain" \
  -F "invoice=@sample_invoice.pdf" \
  -o /tmp/response-full.json

# View discrepancy report
cat /tmp/response-full.json | python3 -c "
import json, sys
d = json.load(sys.stdin)
print('compliant:', d.get('compliant'))
print()
print('=== discrepancies ===')
for disc in d.get('discrepancies', []):
    print(f'  field:    {disc[\"field\"]}')
    print(f'  lc_value: {disc[\"lc_value\"]}')
    print(f'  presented: {disc[\"presented_value\"]}')
    print(f'  rule:      {disc[\"rule_reference\"]}')
    print(f'  desc:      {disc[\"description\"]}')
    print()
print('=== summary ===')
s = d.get('summary', {})
print(f'  total: {s.get(\"totalChecks\")}, passed: {s.get(\"passed\")}, failed: {s.get(\"failed\")}')
"
```

**View full trace (intermediate steps):**
```bash
SESSION_ID=$(cat /tmp/response-full.json | python3 -c "import json,sys; print(json.load(sys.stdin).get('sessionId',''))")
curl -s http://localhost:8080/api/v1/lc-check/${SESSION_ID}/trace \
  -o /tmp/trace.json

cat /tmp/trace.json | python3 -c "
import json, sys
d = json.load(sys.stdin)
print('lc_parsing:', d['lcParsing']['status'])
print('invoice_extraction:', d['invoiceExtraction']['status'])
print('checks_run:', len(d.get('checksRun', [])))
"
```

---

## Quick Connectivity Checks

```bash
# Ollama reachable from Docker?
docker run --rm --add-host=host.docker.internal:host-gateway \
  curlimages/curl -s http://host.docker.internal:11434/api/tags

# Docling health?
curl -s http://localhost:8081/health

# MinerU health?
curl -s http://localhost:8082/health

# Java API health?
curl -fsS http://localhost:8080/actuator/health
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `EXTRACTION_TIMEOUT` from docling | Models downloading at runtime | First run takes 1-2 min; subsequent runs use cached volume |
| `EXTRACTION_FAILED` from mineru | Wrong mineru API version | Rebuild with `pyproject.toml` constraint `mineru[all]>=2.0` |
| `500 Internal Server Error` from `:8080` | Vision LLM unreachable | Check `VISION_BASE_URL` and `ollama list` on host |
| Docling returns confidence=0 | OCR/model failure | Check logs: `docker logs docling-svc` |
| MinerU `is_image_based=true` | PDF is scanned/image-based | Normal — OCR pipeline will still extract text |

---

## Model Caching

All HuggingFace model weights are cached in Docker named volumes:

```bash
docker volume ls | grep lc-checker
# lc-checker-docling-hf-cache
# lc-checker-mineru-hf-cache
```

On subsequent runs, models load from cache — startup is instant after the first run.
