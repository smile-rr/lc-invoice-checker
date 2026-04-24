# LC Invoice Checker — Operator Runbook

Practical guide for bringing the stack up, verifying health, running demos, and
triaging the failures that actually happen in local dev. Kept short on purpose —
everything here should be executable, not aspirational.

Owner: `devops`. Stack definition: [`docker-compose.yml`](../docker-compose.yml).
JDK setup: [`jdk21-install.md`](./jdk21-install.md).

---

## 1. Prerequisites

| Tool | Why | Check |
|---|---|---|
| Docker Desktop (or engine) + Compose v2 | runs the stack | `docker compose version` → v2.x |
| JDK 21 (optional locally, required in build image) | `./gradlew bootRun` outside Docker | `java -version` → 21.0.x |
| `make` | target helpers | `make --version` |
| `curl` + `jq` | smoke tests | `which curl jq` |
| LLM API key | DeepSeek primary (MiniMax fallback) | see `.env.example` |

The Docker image builds the JAR inside a `gradle:8.10.2-jdk21` stage, so local
JDK 21 is **not** needed to run `make up`. It's only needed for `./gradlew bootRun`
outside the container (fast iteration).

---

## 2. First-time bring-up

```bash
cd lc-invoice-checker/           # repo root (has docker-compose.yml)
cp .env.example .env             # then edit .env → set LLM_API_KEY
make env-check                   # validates .env and compose syntax
make up                          # builds images on first run (~3–5 min cold)
```

`make up` prints container + healthcheck state when finished. Expected end state:

```
NAME             STATUS                   PORTS
docling-svc      Up 30s (healthy)         0.0.0.0:8081->8081/tcp
lc-checker-api   Up 15s (health: starting → healthy)    0.0.0.0:8080->8080/tcp

Healthcheck summary:
  /docling-svc: healthy
  /lc-checker-api: healthy
```

`lc-checker-api` depends on `docling-svc` being `healthy` before it starts
(`depends_on.condition: service_healthy`), so the API waits ~30 s for Docling
to finish loading its models on first run.

---

## 3. Smoke tests

### 3a. Actuator health

```bash
curl -fsS http://localhost:8080/actuator/health | jq
# → {"status": "UP"}
```

### 3b. Docling health

```bash
curl -fsS http://localhost:8081/health | jq
# → {"status":"ok","extractor":"docling","version":"1.0", ...}
```

### 3c. End-to-end check

Once `test/check.sh` exists (qa task #27):

```bash
make curl-demo
# internally: curl -F lc=@test/sample_mt700.txt -F invoice=@test/sample_invoice.pdf \
#             http://localhost:8080/api/v1/lc-check
```

Expected: JSON with `"compliant": false` and 3 discrepancy entries
(invoice_amount / goods_description / lc_number_reference).

### 3d. Trace inspection

```bash
SESSION_ID=$(curl -sS -F lc=@test/sample_mt700.txt -F invoice=@test/sample_invoice.pdf \
  http://localhost:8080/api/v1/lc-check | jq -r .sessionId)
curl -sS http://localhost:8080/api/v1/lc-check/$SESSION_ID/trace | jq
```

---

## 4. Common failure modes

### 4a. `lc-checker-api` stuck in `health: starting` forever

**Symptom**: after ~2 min, `make status` still shows `starting`, then eventually
`unhealthy`.

**Likely causes, in order**:

1. **Actuator not exposed.** The healthcheck `curl localhost:8080/actuator/health`
   returns 404. Fix (spring-dev's concern): ensure
   `spring-boot-starter-actuator` is on the classpath and
   `management.endpoints.web.exposure.include=health` in `application.yml`.
2. **App crashed on boot** (bad LLM_BASE_URL, missing bean, etc.).
   ```bash
   make logs-api | grep -iE 'error|exception|caused by' | head -20
   ```
3. **Healthcheck `start_period` too short.** Cold JVM boot on a slow machine
   can exceed 60 s. Bump `start_period` in `docker-compose.yml` healthcheck
   block to 90 s and `make rebuild`.

### 4b. LLM 401 / 403

**Symptom**: Type-B rule checks return `UNABLE_TO_VERIFY`; logs show
`401 Unauthorized` from `api.deepseek.com`.

```bash
make logs-api | grep -iE 'llm|unauthorized|401'
```

**Fix**:
- Confirm `.env` has a real `LLM_API_KEY` (not the placeholder).
- If using MiniMax: set `LLM_BASE_URL=https://api.minimaxi.chat/v1`
  and `LLM_MODEL=MiniMax-Text-01`.
- Restart just the API: `docker compose up -d --force-recreate lc-checker-api`.

### 4c. Port conflict on 8080 / 8081 / 8082

**Symptom**: `make up` fails with `bind: address already in use`.

**Fix (no edit required)** — override the host-side port via env:
```bash
API_PORT=18080 DOCLING_PORT=18081 make up
```
Or pin in `.env`:
```
API_PORT=18080
DOCLING_PORT=18081
```
The container-side ports (8080 / 8081) stay the same, so inter-service URLs
(`http://docling-svc:8081`) keep working.

### 4d. Docling cold start times out the API's first request

**Symptom**: first `/lc-check` request takes > 30 s and times out, subsequent
requests succeed.

**Why**: Docling loads layout + OCR models lazily on first request.
The Docker healthcheck passes (lightweight probe), but the model load only
finishes when a real extraction hits.

**Fix**: prime Docling after `make up`:
```bash
# Hit /extract once with any PDF to trigger model load
curl -F file=@refer-doc/invoice-3-color-claude.pdf http://localhost:8081/extract >/dev/null
```
Or raise `EXTRACTOR_TIMEOUT_SECONDS` in `.env` to `60` for the first cold boot.

### 4e. Extractor returns low confidence → fallback needed

**Symptom**: Docling returns `confidence: 0.6`, API logs show
`extractor.fallback from=docling to=mineru` but mineru isn't up yet.

**Fix (V1 temporary)**: mineru-svc isn't in compose until task #24 is finalized.
Until then, either:
- Use a cleaner PDF in `test/sample_invoice.pdf`, or
- Lower `EXTRACTOR_CONFIDENCE_THRESHOLD=0.50` in `.env` to accept Docling's
  best-effort output.

### 4f. `make up` fails: `LLM_API_KEY must be set`

**Symptom**:
```
error while interpolating services.lc-checker-api.environment.LLM_API_KEY:
required variable LLM_API_KEY is missing a value
```

**Fix**: `cp .env.example .env` and edit in a real key. This is intentional —
the compose spec fails fast rather than starting an API that will error on every
LLM call.

### 4g. Gradle build fails inside Dockerfile with `Could not resolve ... `

**Symptom**: first `make up` spends ~2 min then dies during the gradle build
stage with dependency-resolution errors.

**Common fix**: network policy is blocking Maven Central or Spring repos from
inside the build container. Try:
```bash
docker compose build --no-cache lc-checker-api
```
If still failing, verify from the host:
```bash
curl -I https://repo.maven.apache.org/maven2/
curl -I https://repo.spring.io/release/
```
Both should return 200. If blocked, ask spring-dev to add a corporate proxy
config to `gradle.properties`.

### 4h. Image bloat / cache staleness

**Symptom**: local images growing >2 GB total.

**Fix**: `make clean` — removes local project images + dangling layers
specific to the compose project. Leaves Docker's OS-wide cache untouched.

---

## 5. Log inspection tips

### MDC keys present in every log line (post-observability wiring, task #21)

| Key | Meaning |
|---|---|
| `sessionId` | UUID per `/lc-check` request |
| `stage` | `parse \| extract \| activate \| check \| assemble` |
| `ruleId` | e.g. `INV-011` (only inside check stage) |
| `checkType` | `A \| B \| AB \| SPI` |
| `llmModel` | only on LLM call lines |

### Console-pretty vs JSON

```bash
# Dev (console-pretty): SPRING_PROFILES_ACTIVE=default — default in .env.example
make logs-api

# Prod-like (JSON, Loki-ready): SPRING_PROFILES_ACTIVE=prod
SPRING_PROFILES_ACTIVE=prod docker compose up -d --force-recreate lc-checker-api
make logs-api | jq  # pretty-print
```

### Filter by session

Once MDC is wired, every line for a single request shares a `sessionId`:

```bash
make logs-api | grep 550e8400-e29b-41d4-a716-446655440000
# JSON mode:
make logs-api | jq -c 'select(.sessionId == "550e8400-...")'
```

### Per-stage latency summary

```bash
make logs-api | grep -oE 'stage=\w+ duration=\w+ms' | sort | uniq -c
```

---

## 6. Stopping / reset cheat-sheet

```bash
make down           # stop containers, keep images + volumes
make clean          # stop + remove local images + prune build cache
docker system prune -a --volumes   # nuclear: wipe *all* Docker state (host-wide)
```

---

## 7. Ownership map (who to ping)

| Issue domain | Owner | How to reach |
|---|---|---|
| Dockerfile / compose / Makefile / this runbook | `devops` | SendMessage |
| Java code / actuator config / Spring AI wiring | `spring-dev` | SendMessage |
| Python extractors / docling-svc / mineru-svc / CONTRACT.md | `extractor-dev` | SendMessage |
| test fixtures / check.sh / expected_output.json | `qa` | SendMessage |
| priorities, scope, cross-role blockers | `team-lead` | SendMessage |

---

## 8. Deferred items (this runbook will grow)

- [ ] OTel collector endpoint + tracing UI link (V2)
- [ ] Langfuse LLM-trace link (V2, bank-approval gated)
- [ ] Prometheus scrape config + Grafana dashboard JSON (V2)
- [ ] mineru-svc operational sections — will expand once task #24 lands
- [ ] `make curl-demo` behavior once qa ships `test/check.sh` (task #27)
