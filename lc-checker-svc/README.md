# lc-checker-svc — Java Service

Spring Boot 3.5 + Spring AI 1.1 service. Accepts an MT700 LC + an invoice PDF,
runs them through a 5-stage pipeline, and returns a discrepancy report plus a
full forensic trace. Stage numbering matches
[`../docs/refer-doc/logic-flow.md`](../docs/refer-doc/logic-flow.md).

---

## Requirements

- **JDK 21 LTS** (Temurin recommended). Install via SDKMAN:
  ```bash
  curl -s "https://get.sdkman.io" | bash
  # restart shell
  sdk install java 21.0.10-tem
  sdk default java 21.0.10-tem
  java -version
  ```
- Gradle via the bundled wrapper (`./gradlew`) — no system Gradle needed.
- An OpenAI-compatible LLM endpoint (local Ollama on `:11434` works out of the box).

---

## Build & run

**This service runs locally on the JVM.** Only Postgres runs in Docker.

```bash
# 1. Start Postgres (from repo root — data persists across restarts)
make up

# 2. Run the service locally
cd lc-checker-svc
./gradlew bootRun                 # listens on :8080

# — or from repo root —
make bootrun

# 3. Health check
curl -fsS http://localhost:8080/actuator/health | jq
```

The Java service reads `.env` at repo root for env vars (via `make bootrun`) and
connects to Postgres on `localhost:5432`.

Build a runnable jar (for ad-hoc deploy):
```bash
./gradlew bootJar
java -jar build/libs/lc-checker-svc-*.jar
```

---

## Pipeline

```
Stage 0   InputValidator (PDF magic bytes, size, MT700 structure)
           │
Stage 1a   Mt700Parser           (regex only, no LLM)
           │    → stage_lc_parse
Stage 1b   InvoiceExtractionOrchestrator
           │    runs every enabled source (vision / docling / mineru)
           │    → one stage_invoice_extract row per source; one is_selected
Stage 2    RuleActivator         (catalog-driven; code only)
           │    → stage_rule_activation
Stage 3    CheckExecutor.runAllTiers
           │    Tier 1: all Type A rules (SpEL)
           │    Tier 2: all Type B rules (LLM)
           │    Tier 3: all Type AB rules (SpEL pre-gate + LLM)
           │    → stage_rule_check (one row per rule, tier tagged)
Stage 4    Holistic Sweep        (table ready, executor deferred)
Stage 5    ReportAssembler → finalize_session → stage 5 result on check_sessions
```

Top-level orchestrator: `engine/ComplianceEngine.java`.

---

## API

**Primary:**
```
POST /api/v1/lc-check
  multipart: lc (text/plain), invoice (application/pdf)
  → 200 DiscrepancyReport
  → 400 VALIDATION_FAILED | LC_PARSE_ERROR | PDF_UNREADABLE
  → 502 EXTRACTOR_UNAVAILABLE

GET  /api/v1/lc-check/{sessionId}/trace
  → 200 CheckSession (rebuilt from stage tables)
  → 404 SESSION_NOT_FOUND

POST /api/v1/files/upload
  multipart: file (application/pdf)
  → 200 { fileKey, filename }
```

---

## Configuration

`src/main/resources/application.yml` is the source of truth for defaults. The table
below lists the env vars the service reads (typically set via `.env` + docker compose).

| Env var                          | Default                                           | Used for |
|----------------------------------|---------------------------------------------------|----------|
| `SERVER_PORT`                    | `8080`                                            | HTTP port |
| `SPRING_PROFILES_ACTIVE`         | `default`                                         | `default` = console logs, `prod` = JSON logs |
| `DB_HOST` / `DB_PORT` / `DB_USER` / `DB_PASSWORD` | `postgres` / `5432` / `lcuser` / `lcpass123` | Postgres |
| `LLM_API_KEY`                    | _(empty → Ollama)_                                | Language LLM key |
| `LLM_BASE_URL`                   | `http://host.docker.internal:11434/v1`            | Language LLM endpoint (OpenAI-compatible) |
| `LLM_MODEL`                      | `qwen2.5:3b`                                      | Language model |
| `VISION_API_KEY`                 | _(empty → Ollama)_                                | Vision LLM key |
| `VISION_BASE_URL`                | `http://host.docker.internal:11434/v1`            | Vision LLM endpoint |
| `VISION_MODEL`                   | `qwen3-vl:2b`                                     | Vision model |
| `VISION_RENDER_SCALE`            | `1.5`                                             | PDFBox render scale for vision input |
| `VISION_TIMEOUT_SECONDS`         | `120`                                             | Vision LLM call timeout |
| `EXTRACTOR_VISION_ENABLED`       | `true`                                            | Enable vision source in Stage 1b |
| `EXTRACTOR_DOCLING_ENABLED`      | `false`                                           | Enable docling-svc HTTP source (run it locally via uvicorn) |
| `EXTRACTOR_MINERU_ENABLED`       | `false`                                           | Enable mineru-svc HTTP source (run it locally via uvicorn) |
| `EXTRACTOR_DOCLING_URL`          | `http://localhost:8081`                           | Docling endpoint (local uvicorn) |
| `EXTRACTOR_MINERU_URL`           | `http://localhost:8082`                           | MinerU endpoint (local uvicorn) |
| `EXTRACTOR_CONFIDENCE_THRESHOLD` | `0.80`                                            | Selection threshold for `is_selected` |

Provider swap examples (no code changes):
```bash
# Ollama local (default)
LLM_BASE_URL=http://host.docker.internal:11434/v1  LLM_MODEL=qwen2.5:3b

# DeepSeek
LLM_BASE_URL=https://api.deepseek.com  LLM_MODEL=deepseek-chat  LLM_API_KEY=sk-...

# Qwen Bailian
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1  LLM_MODEL=qwen-plus

# Gemini via OpenAI adapter
LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/  LLM_MODEL=gemini-1.5-flash
```

---

## Rule catalog

Rules live in `src/main/resources/rules/catalog.yml`. Each entry carries:

| Field                   | Meaning |
|-------------------------|---------|
| `id`                    | e.g. `INV-011` (used in traces, DB, reports) |
| `check_type`            | `A` (code), `B` (LLM), `AB` (hybrid), `SPI` (custom Java) |
| `trigger`               | `UNCONDITIONAL` \| `LC_STIPULATED` \| `DYNAMIC_47A` |
| `activation_expr`       | SpEL predicate for `LC_STIPULATED` — has access to `lc` |
| `expression`            | SpEL for Type A (and pre-gate of AB) — has access to `lc`, `inv` |
| `prompt_template`       | `.st` filename for Type B / AB, relative to `src/main/resources/prompts/` |
| `lc_fields`             | MT700 tags the rule reads (for Q1 pre-gate) |
| `invoice_fields`        | Invoice keys the rule reads (for Q2 pre-gate) |
| `missing_invoice_action`| `DISCREPANT` \| `NOT_APPLICABLE` \| `UNABLE_TO_VERIFY` |
| `severity_on_fail`      | `MAJOR` \| `MINOR` |
| `enabled`               | `false` = loaded but never activated |

Tier assignment is **derived** at runtime: Type A → Tier 1, Type B → Tier 2, Type AB → Tier 3.

### Adding a new rule (the common case)

1. Append a YAML entry to `catalog.yml`.
2. If Type B / AB: drop a new `.st` prompt file in `prompts/`; reference the filename.
3. Restart the service. No Java changes.

For exotic rules requiring custom Java (e.g. calendar math), implement `SpiRuleChecker`
with `@RuleImpl("INV-XXX")` — the executor wires it automatically.

---

## Observability

- **Actuator** — `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`.
- **MDC keys** on every log line: `sessionId`, `stage`, `ruleId`, `checkType`.
- **Logback profiles** — `logback-spring.xml` (dev console) / `logback-json.xml` (prod,
  Loki/ELK-ready; `SPRING_PROFILES_ACTIVE=prod`).
- **Micrometer timers** per stage and per rule-check outcome.

Prompts (`src/main/resources/prompts/`) have their own authoring guide in
[`prompts/README.md`](src/main/resources/prompts/README.md).
