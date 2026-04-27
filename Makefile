# lc-checker — Makefile (minimal)
#
# Default: `make <svc>` runs in the FOREGROUND (logs in this terminal, Ctrl-C stops).
# To background:    make svc > /tmp/svc.log 2>&1 &
# Stop background:  make svc-down   (kills by port; works whether fg or bg)
#
# Whole stack:      make all                    # start svc + docling + mineru + ui in background
#                                               #   (db is NOT started — manage it separately)
#                   make all EXCEPT=mineru      # further skip mineru on top of the default
#                   make all-down               # stop EVERYTHING (db, docling, mineru, svc, ui)
# Background logs land in /tmp/lc-checker/<svc>.log
#
# +-----------+-------------------+----------------------+-------+--------------------------------------+
# | Component | Up                | Down                 | Port  | URL                                  |
# +-----------+-------------------+----------------------+-------+--------------------------------------+
# | db        | make db           | make db-down         |  5432 | postgres://localhost:5432            |
# | svc       | make svc          | make svc-down        |  8080 | http://127.0.0.1:8080                |
# | docling   | make docling      | make docling-down    |  8081 | http://127.0.0.1:8081                |
# | mineru    | make mineru       | make mineru-down     |  8082 | http://127.0.0.1:8082                |
# | ui        | make ui           | make ui-down         |  5173 | http://127.0.0.1:5173                |
# | (stack)   | make all          | make all-down        |     — | all = svc/docling/mineru/ui only; all-down = everything |
# | (status)  | make status       |                      |     — | one-line per component (port-listening) |
# | (health)  | make health       |                      |     — | hit each /health endpoint (actually working?) |
# +-----------+-------------------+----------------------+-------+--------------------------------------+
#
# Local vision LLM is provided by Ollama (`brew install ollama && ollama serve`,
# then `ollama pull qwen3-vl:4b-instruct`). Ollama is managed outside this
# Makefile — it's a system daemon on :11434 that the Java service hits as
# `local_llm_vl`. See LOCAL_LLM_VL_* in .env.
#
# db is a local fallback — .env may point at a remote Postgres instead.
# ---------------------------------------------------------------------------

SHELL    := /bin/bash
ENV_FILE := .env
COMPOSE  := docker compose

DOCLING_DIR := extractors/docling
MINERU_DIR  := extractors/mineru
UI_DIR      := ui
SVC_DIR     := lc-checker-svc

PYTHON := python3.11

LOG_DIR := /tmp/lc-checker

# ALL          — every component (used by `all-down` for an exhaustive stop)
# ALL_DEFAULT  — what `make all` starts. db is excluded so the user can
#                manage it separately (usually remote and always-on).
ALL          := db docling mineru svc ui
ALL_DEFAULT  := docling mineru svc ui
EXCEPT       ?=
SELECTED     := $(filter-out $(EXCEPT),$(ALL_DEFAULT))

.DEFAULT_GOAL := help

help:  ## list targets with ports + URLs
	@echo ""
	@echo "  lc-checker — make <svc> = start (foreground); make <svc>-down = stop"
	@echo "                make all = start everything in background (use EXCEPT=\"...\" to skip)"
	@echo ""
	@awk 'BEGIN{FS=":.*##"} /^[a-zA-Z0-9_-]+:.*##/ {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo ""

# --- all / all-down (whole stack in background) ------------------------------

$(LOG_DIR):
	@mkdir -p $(LOG_DIR)

all: $(LOG_DIR)  ## start svc + docling + mineru + ui in background (skips db — manage separately)
	@echo "→ all: $(SELECTED)$(if $(EXCEPT),  (further skipping: $(EXCEPT)),)"
	@echo "  (db is NOT started by 'all' — bring it up with 'make db' if needed)"
	@for s in $(SELECTED); do $(MAKE) --no-print-directory $$s-down 2>/dev/null || true; done
	@for s in $(SELECTED); do $(MAKE) --no-print-directory _$$s-bg; done
	@echo ""
	@echo "✓ stack up — logs in $(LOG_DIR)/<svc>.log   (stop with: make all-down)"

all-down:  ## stop EVERYTHING (db, docling, mineru, svc, ui)
	@for s in $(ALL); do $(MAKE) --no-print-directory $$s-down 2>/dev/null || true; done

status:  ## show one-line status (host:port, up/down, url) for each component
	@printf '  %-12s %-24s %-8s %s\n' service host:port status url
	@printf '  %-12s %-24s %-8s %s\n' ------------ ------------------------ -------- ---
	@for entry in svc:8080 docling:8081 mineru:8082 ui:5173 ollama:11434; do \
	   svc=$${entry%%:*}; port=$${entry#*:}; \
	   if lsof -i tcp:$$port -sTCP:LISTEN >/dev/null 2>&1; then state="✓ up"; else state="·"; fi; \
	   if [ "$$svc" = "ollama" ]; then url="http://127.0.0.1:$$port/v1  (local_llm_vl)"; \
	   else url="http://127.0.0.1:$$port"; fi; \
	   printf '  %-12s %-24s %-8s %s\n' "$$svc" "127.0.0.1:$$port" "$$state" "$$url"; \
	 done
	@host=$$(grep '^DB_HOST=' $(ENV_FILE) 2>/dev/null | cut -d= -f2); \
	 port=$$(grep '^DB_PORT=' $(ENV_FILE) 2>/dev/null | cut -d= -f2); \
	 host=$${host:-localhost}; port=$${port:-5432}; \
	 if [ "$$host" = "localhost" ] || [ "$$host" = "127.0.0.1" ]; then \
	   label="db (local)"; \
	 else \
	   label="db (remote)"; \
	 fi; \
	 if command -v nc >/dev/null 2>&1 && nc -z -w 2 "$$host" "$$port" 2>/dev/null; then \
	   state="✓ up"; \
	 else \
	   state="·"; \
	 fi; \
	 printf '  %-12s %-24s %-8s %s\n' "$$label" "$$host:$$port" "$$state" "postgres://$$host:$$port"

health:  ## hit each /health endpoint and report actual responsiveness (deeper than status)
	@printf '  %-10s %-6s %-12s %s\n' service port health detail
	@printf '  %-10s %-6s %-12s %s\n' ---------- ------ ------------ ------
	@for entry in svc:8080:/actuator/health docling:8081:/health mineru:8082:/health ui:5173:/; do \
	   svc=$${entry%%:*}; rest=$${entry#*:}; port=$${rest%%:*}; path=$${rest#*:}; \
	   if ! lsof -i tcp:$$port -sTCP:LISTEN >/dev/null 2>&1; then \
	     printf '  %-10s %-6s %-12s %s\n' "$$svc" "$$port" "·" "(port not bound)"; \
	     continue; \
	   fi; \
	   resp=$$(curl -sS --max-time 2 -o /tmp/.lc-health.$$svc -w '%{http_code}' "http://127.0.0.1:$$port$$path" 2>/dev/null); \
	   if [ "$$resp" = "200" ]; then \
	     body=$$(head -c 80 /tmp/.lc-health.$$svc 2>/dev/null | tr -d '\n' | tr -d '\r' || true); \
	     printf '  %-10s %-6s %-12s %s\n' "$$svc" "$$port" "✓ healthy" "$$body"; \
	   elif [ -n "$$resp" ]; then \
	     printf '  %-10s %-6s %-12s %s\n' "$$svc" "$$port" "⚠ http $$resp" "(port up, /health not 200)"; \
	   else \
	     printf '  %-10s %-6s %-12s %s\n' "$$svc" "$$port" "⚠ no resp" "(port up but /health timed out)"; \
	   fi; \
	   rm -f /tmp/.lc-health.$$svc; \
	 done
	@if command -v nc >/dev/null 2>&1; then \
	   if [ -f .env ] && grep -q '^DB_HOST=' .env; then \
	     host=$$(grep '^DB_HOST=' .env | cut -d= -f2); \
	     port=$$(grep '^DB_PORT=' .env | cut -d= -f2); \
	     if nc -z -w 2 "$$host" "$$port" 2>/dev/null; then \
	       printf '  %-10s %-6s %-12s %s\n' "db" "$$port" "✓ healthy" "tcp reachable at $$host:$$port"; \
	     else \
	       printf '  %-10s %-6s %-12s %s\n' "db" "$$port" "·" "tcp unreachable at $$host:$$port"; \
	     fi; \
	   fi; \
	 fi

# private bg helpers — invoked by `all`, not in user-facing help
_db-bg:
	@$(MAKE) --no-print-directory db

_svc-bg:
	@(cd $(SVC_DIR) && set -a && source ../$(ENV_FILE) && set +a && \
	  nohup ./gradlew bootRun > $(LOG_DIR)/svc.log 2>&1 &) \
	  && echo "✓ svc bg → http://127.0.0.1:8080"

_docling-bg: _docling-install
	@pid=$$(lsof -ti tcp:8081 2>/dev/null); if [ -n "$$pid" ]; then kill $$pid && sleep 1; fi
	@(set -a && source $(ENV_FILE) && set +a && \
	    cd $(DOCLING_DIR) && nohup .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8081 \
	    > $(LOG_DIR)/docling.log 2>&1 &) \
	  && echo "✓ docling bg → http://127.0.0.1:8081"

_mineru-bg: _mineru-install
	@pid=$$(lsof -ti tcp:8082 2>/dev/null); if [ -n "$$pid" ]; then kill $$pid && sleep 1; fi
	@(set -a && source $(ENV_FILE) && set +a && \
	    cd $(MINERU_DIR) && nohup .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8082 \
	    > $(LOG_DIR)/mineru.log 2>&1 &) \
	  && echo "✓ mineru bg → http://127.0.0.1:8082"

_ui-bg: _ui-install
	@(cd $(UI_DIR) && nohup npm run dev > $(LOG_DIR)/ui.log 2>&1 &) \
	  && echo "✓ ui bg → http://127.0.0.1:5173"

# --- llm-test (Ollama smoke test — no start/stop, daemon is system-managed) --
# Diagnostic only. Verifies the Ollama daemon is listening on :11434, lists
# installed models, then fires a tiny /v1/chat/completions ping using the
# model named in .env's LOCAL_LLM_VL_MODEL. Surfaces latency + response so
# you can tell at a glance whether `local_llm_vl` will work end-to-end.

test:  ## fire one POST + tail the SSE stream live (uses test/stream.sh)
	@bash test/stream.sh

test-rule: ## same as `make test` but filtered to a single rule (RULE=ISBP-C3)
	@RULE=$${RULE:?set RULE=<rule-id> e.g. RULE=ISBP-C3} bash test/stream.sh

test-verdict: ## same as `make test` but only print the final report
	@VERDICT_ONLY=1 bash test/stream.sh

llm-test:  ## ping local Ollama (model from LOCAL_LLM_VL_MODEL in .env)
	@echo "=== 1. daemon listening on :11434 ==="
	@if lsof -nP -iTCP:11434 -sTCP:LISTEN >/dev/null 2>&1; then \
	   lsof -nP -iTCP:11434 -sTCP:LISTEN | tail -n +1; \
	 else \
	   echo "  · NOT LISTENING — start with: brew services start ollama   (or: ollama serve &)"; \
	   exit 1; \
	 fi
	@echo
	@echo "=== 2. installed models ==="
	@if command -v ollama >/dev/null 2>&1; then ollama list; \
	 else curl -sS http://127.0.0.1:11434/api/tags | python3 -m json.tool 2>/dev/null || true; fi
	@echo
	@set -a; . ./$(ENV_FILE); set +a; \
	 model="$${LOCAL_LLM_VL_MODEL:-qwen3-vl:4b-instruct}"; \
	 base="$${LOCAL_LLM_VL_BASE_URL:-http://127.0.0.1:11434/v1}"; \
	 echo "=== 3. inference smoke test ==="; \
	 echo "  base-url: $$base"; \
	 echo "  model:    $$model"; \
	 t0=$$(python3 -c 'import time; print(int(time.time()*1000))'); \
	 resp=$$(curl -sS -m 60 "$$base/chat/completions" \
	   -H "Content-Type: application/json" \
	   -d "{\"model\":\"$$model\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"Reply with one word: ready\"}]}" \
	   2>&1); \
	 t1=$$(python3 -c 'import time; print(int(time.time()*1000))'); \
	 ms=$$(( $$t1 - $$t0 )); \
	 content=$$(printf '%s' "$$resp" | python3 -c 'import json,sys; r=json.loads(sys.stdin.read()); print(r["choices"][0]["message"]["content"])' 2>/dev/null); \
	 if [ -n "$$content" ]; then \
	   echo "  status:   ✓ OK ($${ms} ms)"; \
	   echo "  response: $$content"; \
	 else \
	   echo "  status:   ✗ FAILED ($${ms} ms)"; \
	   echo "  body:     $$(printf '%s' "$$resp" | head -c 400)"; \
	   exit 2; \
	 fi
	@echo
	@echo "=== 4. currently loaded in memory (ollama ps) ==="
	@ollama ps 2>/dev/null || echo "  (ollama CLI unavailable)"

# --- db (Docker compose) -----------------------------------------------------
#
# `make db` will boot the Docker daemon (colima via brew services) on first run
# and then bring postgres up. After the container is up, it applies any
# missing schema additions from infra/postgres/init/01-schema.sql against the
# existing volume — `01-schema.sql` only auto-runs on a *fresh* volume, so new
# CREATE TABLEs added after the volume was created would otherwise be missed.

db: _docker-up  ## start Postgres in Docker (background) — postgres://localhost:5432
	@$(COMPOSE) up -d postgres
	@echo "✓ db up → postgres://localhost:5432"
	@$(MAKE) --no-print-directory _db-apply-schema

db-down:   ## stop Postgres (keeps volume)
	@$(COMPOSE) down

# Boot the Docker daemon if it isn't already responding. On macOS this uses
# colima (installed via brew). The flow:
#   1. If `docker ps` already works → done.
#   2. Else, if `colima status` says it's running, just switch the docker
#      context to colima (a fresh terminal often defaults to desktop-linux
#      and finds nothing).
#   3. Else, start colima via brew services and poll for readiness.
_docker-up:
	@if docker ps >/dev/null 2>&1; then \
	   exit 0; \
	 fi; \
	 if ! command -v brew >/dev/null 2>&1; then \
	   echo "✗ brew not installed — start Docker Desktop manually"; exit 1; \
	 fi; \
	 if ! brew list --formula 2>/dev/null | grep -q '^colima$$'; then \
	   echo "✗ colima not installed — run: brew install colima"; exit 1; \
	 fi; \
	 if colima status >/dev/null 2>&1 && docker context inspect colima >/dev/null 2>&1; then \
	   echo "→ colima is up; switching docker context"; \
	   docker context use colima >/dev/null; \
	 else \
	   echo "→ docker daemon not responding — starting colima via brew"; \
	   brew services start colima >/dev/null 2>&1 || true; \
	   docker context use colima >/dev/null 2>&1 || true; \
	 fi; \
	 for i in $$(seq 1 60); do \
	   if docker ps >/dev/null 2>&1; then echo "✓ docker ready (context: $$(docker context show))"; exit 0; fi; \
	   sleep 2; \
	 done; \
	 echo "✗ docker still not responding after 120s"; exit 1

# Idempotent re-application of the init script. CREATE TABLE/INDEX statements
# in 01-schema.sql use IF NOT EXISTS, and the views all use OR REPLACE, so a
# second run is safe and only adds new objects (e.g. the pipeline_events
# table introduced after the original volume was created).
_db-apply-schema:
	@for i in $$(seq 1 30); do \
	   if docker exec lc-checker-postgres pg_isready -U lcuser -d lc_checker >/dev/null 2>&1; then break; fi; \
	   sleep 1; \
	 done; \
	 docker cp infra/postgres/init/01-schema.sql lc-checker-postgres:/tmp/01-schema.sql >/dev/null 2>&1 && \
	 docker exec lc-checker-postgres psql -q -U lcuser -d lc_checker -f /tmp/01-schema.sql >/dev/null 2>&1 && \
	   echo "✓ schema applied (idempotent)" || \
	   echo "⚠ schema apply skipped (db not ready or psql failed)"

# --- svc (Java/Gradle) -------------------------------------------------------

svc:       ## start Java service (foreground) — http://127.0.0.1:8080
	@echo "→ svc on :8080 → http://127.0.0.1:8080   (Ctrl-C to stop)"
	@cd $(SVC_DIR) && set -a && source ../$(ENV_FILE) && set +a && ./gradlew bootRun

svc-down:  ## stop Java service (kills :8080)
	@pid=$$(lsof -ti tcp:8080 2>/dev/null); \
	  if [ -n "$$pid" ]; then kill $$pid && echo "✓ svc stopped"; else echo "(svc not running)"; fi

# --- docling (FastAPI) -------------------------------------------------------

docling: _docling-install  ## start Docling extractor (foreground) — http://127.0.0.1:8081
	@pid=$$(lsof -ti tcp:8081 2>/dev/null); if [ -n "$$pid" ]; then echo "→ stopping existing docling (pid=$$pid)"; kill $$pid && sleep 1; fi
	@echo "→ docling on :8081 → http://127.0.0.1:8081   (Ctrl-C to stop)"
	@set -a && source $(ENV_FILE) && set +a && \
	  cd $(DOCLING_DIR) && .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8081

docling-down: ## stop Docling extractor (kills :8081)
	@pid=$$(lsof -ti tcp:8081 2>/dev/null); \
	  if [ -n "$$pid" ]; then kill $$pid && echo "✓ docling stopped"; else echo "(docling not running)"; fi

_docling-install:
	@cd $(DOCLING_DIR) && uv sync --quiet

# --- mineru (FastAPI) --------------------------------------------------------

mineru: _mineru-install  ## start MinerU extractor (foreground) — http://127.0.0.1:8082
	@pid=$$(lsof -ti tcp:8082 2>/dev/null); if [ -n "$$pid" ]; then echo "→ stopping existing mineru (pid=$$pid)"; kill $$pid && sleep 1; fi
	@echo "→ mineru on :8082 → http://127.0.0.1:8082   (Ctrl-C to stop)"
	@set -a && source $(ENV_FILE) && set +a && \
	  cd $(MINERU_DIR) && .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8082

mineru-down: ## stop MinerU extractor (kills :8082)
	@pid=$$(lsof -ti tcp:8082 2>/dev/null); \
	  if [ -n "$$pid" ]; then kill $$pid && echo "✓ mineru stopped"; else echo "(mineru not running)"; fi

_mineru-install:
	@cd $(MINERU_DIR) && uv sync --quiet

# --- ui (Vite) ---------------------------------------------------------------

ui: _ui-install  ## start Vite dev server (foreground) — http://127.0.0.1:5173
	@echo "→ ui on :5173 → http://127.0.0.1:5173   (Ctrl-C to stop)"
	@cd $(UI_DIR) && npm run dev

ui-down:   ## stop UI dev server (kills :5173)
	@pid=$$(lsof -ti tcp:5173 2>/dev/null); \
	  if [ -n "$$pid" ]; then kill $$pid && echo "✓ ui stopped"; else echo "(ui not running)"; fi

_ui-install:
	@if [ ! -d $(UI_DIR)/node_modules ]; then \
	   echo "→ first-time install in $(UI_DIR)"; \
	   cd $(UI_DIR) && npm install --silent ; \
	   echo "✓ $(UI_DIR) installed"; \
	 fi

.PHONY: help all all-down status health llm-test test test-rule test-verdict \
        db db-down _docker-up _db-apply-schema \
        svc svc-down \
        docling docling-down \
        mineru mineru-down \
        ui ui-down \
        _db-bg _svc-bg _docling-bg _mineru-bg _ui-bg \
        _docling-install _mineru-install _ui-install
