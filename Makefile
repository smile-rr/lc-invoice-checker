# lc-checker — Makefile (minimal)
#
# Default: `make <svc>` runs in the FOREGROUND (logs in this terminal, Ctrl-C stops).
# To background:    make svc > /tmp/svc.log 2>&1 &
# Stop background:  make svc-down   (kills by port; works whether fg or bg)
#
# Whole stack:      make all                    # start svc + docling + mineru + ui in background
#                                               #   (db and llm-vl are NOT started — manage them separately)
#                   make all EXCEPT=mineru      # further skip mineru on top of the default
#                   make all-down               # stop EVERYTHING (db, llm-vl, docling, mineru, svc, ui)
# Background logs land in /tmp/lc-checker/<svc>.log
#
# +-----------+-------------------+----------------------+-------+--------------------------------------+
# | Component | Up                | Down                 | Port  | URL                                  |
# +-----------+-------------------+----------------------+-------+--------------------------------------+
# | db        | make db           | make db-down         |  5432 | postgres://localhost:5432            |
# | svc       | make svc          | make svc-down        |  8080 | http://127.0.0.1:8080                |
# | llm       | make llm          | make llm-down        |  8088 | http://127.0.0.1:8088/v1             |
# | llm-vl    | make llm-vl       | make llm-vl-down     |  8088 | http://127.0.0.1:8088/v1             |
# | docling   | make docling      | make docling-down    |  8081 | http://127.0.0.1:8081                |
# | mineru    | make mineru       | make mineru-down     |  8082 | http://127.0.0.1:8082                |
# | ui        | make ui           | make ui-down         |  5173 | http://127.0.0.1:5173                |
# | (stack)   | make all          | make all-down        |     — | all = svc/docling/mineru/ui only; all-down = everything |
# | (status)  | make status       |                      |     — | one-line per component (port-listening) |
# | (health)  | make health       |                      |     — | hit each /health endpoint (actually working?) |
# +-----------+-------------------+----------------------+-------+--------------------------------------+
#
# llm vs llm-vl: today they are aliases (same MLX server on :8088 — Qwen3-VL is
# multimodal and handles both text-only and vision requests). When a separate
# text-only model is added later, `llm` will move to its own port (e.g. :8089)
# and `llm-vl` stays on :8088. The two target names are kept now so callers
# don't have to rename later.
#
# db is a local fallback — .env may point at a remote Postgres instead.
# ---------------------------------------------------------------------------

SHELL    := /bin/bash
ENV_FILE := .env
COMPOSE  := docker compose

LLM_DIR     := llm/mlx-server
DOCLING_DIR := extractors/docling
MINERU_DIR  := extractors/mineru
UI_DIR      := ui
SVC_DIR     := lc-checker-svc

PYTHON := python3.11

LOG_DIR := /tmp/lc-checker

# ALL          — every component (used by `all-down` for an exhaustive stop)
# ALL_DEFAULT  — what `make all` starts. db and llm-vl are excluded so the
#                user can manage them separately (db is usually remote and
#                always-on; llm-vl is heavy, started on demand).
ALL          := db llm-vl docling mineru svc ui
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

all: $(LOG_DIR)  ## start svc + docling + mineru + ui in background (skips db & llm-vl — manage those separately)
	@echo "→ all: $(SELECTED)$(if $(EXCEPT),  (further skipping: $(EXCEPT)),)"
	@echo "  (db & llm-vl are NOT started by 'all' — bring them up with 'make db' / 'make llm' as needed)"
	@for s in $(SELECTED); do $(MAKE) --no-print-directory $$s-down 2>/dev/null || true; done
	@for s in $(SELECTED); do $(MAKE) --no-print-directory _$$s-bg; done
	@echo ""
	@echo "✓ stack up — logs in $(LOG_DIR)/<svc>.log   (stop with: make all-down)"

all-down:  ## stop EVERYTHING (db, llm-vl, docling, mineru, svc, ui)
	@for s in $(ALL); do $(MAKE) --no-print-directory $$s-down 2>/dev/null || true; done

status:  ## show one-line status (host:port, up/down, url) for each component
	@printf '  %-12s %-24s %-8s %s\n' service host:port status url
	@printf '  %-12s %-24s %-8s %s\n' ------------ ------------------------ -------- ---
	@for entry in svc:8080:http llm-vl:8088:http docling:8081:http mineru:8082:http ui:5173:http; do \
	   svc=$${entry%%:*}; rest=$${entry#*:}; port=$${rest%%:*}; scheme=$${rest##*:}; \
	   if lsof -i tcp:$$port -sTCP:LISTEN >/dev/null 2>&1; then state="✓ up"; else state="·"; fi; \
	   if [ "$$svc" = "llm-vl" ]; then url="http://127.0.0.1:$$port/v1"; \
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
	@for entry in svc:8080:/actuator/health llm-vl:8088:/health docling:8081:/health mineru:8082:/health ui:5173:/; do \
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

_llm-vl-bg: _llm-vl-install
	@(set -a && source $(ENV_FILE) && set +a && \
	    cd $(LLM_DIR) && nohup .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8088 \
	    > $(LOG_DIR)/llm-vl.log 2>&1 &) \
	  && echo "✓ llm-vl bg → http://127.0.0.1:8088/v1"

_docling-bg: _docling-install
	@(cd $(DOCLING_DIR) && nohup .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8081 \
	    > $(LOG_DIR)/docling.log 2>&1 &) \
	  && echo "✓ docling bg → http://127.0.0.1:8081"

_mineru-bg: _mineru-install
	@(set -a && source $(ENV_FILE) && set +a && \
	    cd $(MINERU_DIR) && nohup .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8082 \
	    > $(LOG_DIR)/mineru.log 2>&1 &) \
	  && echo "✓ mineru bg → http://127.0.0.1:8082"

_ui-bg: _ui-install _ui-samples
	@(cd $(UI_DIR) && nohup npm run dev > $(LOG_DIR)/ui.log 2>&1 &) \
	  && echo "✓ ui bg → http://127.0.0.1:5173"

# --- db (Docker compose) -----------------------------------------------------

db:        ## start Postgres in Docker (background) — postgres://localhost:5432
	@$(COMPOSE) up -d
	@echo "✓ db up → postgres://localhost:5432"

db-down:   ## stop Postgres (keeps volume)
	@$(COMPOSE) down

# --- svc (Java/Gradle) -------------------------------------------------------

svc:       ## start Java service (foreground) — http://127.0.0.1:8080
	@echo "→ svc on :8080 → http://127.0.0.1:8080   (Ctrl-C to stop)"
	@cd $(SVC_DIR) && set -a && source ../$(ENV_FILE) && set +a && ./gradlew bootRun

svc-down:  ## stop Java service (kills :8080)
	@pid=$$(lsof -ti tcp:8080 2>/dev/null); \
	  if [ -n "$$pid" ]; then kill $$pid && echo "✓ svc stopped"; else echo "(svc not running)"; fi

# --- llm-vl (MLX vision-language; canonical) ---------------------------------

llm-vl: _llm-vl-install  ## start MLX vision-language server (foreground) — http://127.0.0.1:8088/v1
	@echo "→ llm-vl on :8088 → http://127.0.0.1:8088/v1   (Ctrl-C to stop)"
	@set -a && source $(ENV_FILE) && set +a && \
	  cd $(LLM_DIR) && .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8088

llm-vl-down:  ## stop MLX vision-language server (kills :8088)
	@pid=$$(lsof -ti tcp:8088 2>/dev/null); \
	  if [ -n "$$pid" ]; then kill $$pid && echo "✓ llm-vl stopped"; else echo "(llm-vl not running)"; fi

_llm-vl-install:
	@if [ ! -d $(LLM_DIR)/.venv ]; then \
	   echo "→ first-time install in $(LLM_DIR) (~3 min, no model download yet)"; \
	   cd $(LLM_DIR) && $(PYTHON) -m venv .venv && \
	     .venv/bin/pip install --upgrade --quiet pip && \
	     .venv/bin/pip install --quiet -e . ; \
	   echo "✓ $(LLM_DIR) installed"; \
	 fi

# --- llm (text language model; alias of llm-vl today) ------------------------
# Future: edit these rules to point at a separate text-only MLX model on :8089.

llm: llm-vl              ## start MLX text LLM (alias of llm-vl today — same process on :8088)
llm-down: llm-vl-down    ## stop MLX text LLM (alias of llm-vl-down today)

# --- docling (FastAPI) -------------------------------------------------------

docling: _docling-install  ## start Docling extractor (foreground) — http://127.0.0.1:8081
	@echo "→ docling on :8081 → http://127.0.0.1:8081   (Ctrl-C to stop)"
	@cd $(DOCLING_DIR) && .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8081

docling-down: ## stop Docling extractor (kills :8081)
	@pid=$$(lsof -ti tcp:8081 2>/dev/null); \
	  if [ -n "$$pid" ]; then kill $$pid && echo "✓ docling stopped"; else echo "(docling not running)"; fi

_docling-install:
	@if [ ! -d $(DOCLING_DIR)/.venv ]; then \
	   echo "→ first-time install in $(DOCLING_DIR)"; \
	   cd $(DOCLING_DIR) && $(PYTHON) -m venv .venv && \
	     .venv/bin/pip install --upgrade --quiet pip && \
	     .venv/bin/pip install --quiet -e . ; \
	   echo "✓ $(DOCLING_DIR) installed"; \
	 fi

# --- mineru (FastAPI) --------------------------------------------------------

mineru: _mineru-install  ## start MinerU extractor (foreground) — http://127.0.0.1:8082
	@echo "→ mineru on :8082 → http://127.0.0.1:8082   (Ctrl-C to stop)"
	@set -a && source $(ENV_FILE) && set +a && \
	  cd $(MINERU_DIR) && .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8082

mineru-down: ## stop MinerU extractor (kills :8082)
	@pid=$$(lsof -ti tcp:8082 2>/dev/null); \
	  if [ -n "$$pid" ]; then kill $$pid && echo "✓ mineru stopped"; else echo "(mineru not running)"; fi

_mineru-install:
	@if [ ! -d $(MINERU_DIR)/.venv ]; then \
	   echo "→ first-time install in $(MINERU_DIR)"; \
	   cd $(MINERU_DIR) && $(PYTHON) -m venv .venv && \
	     .venv/bin/pip install --upgrade --quiet pip && \
	     .venv/bin/pip install --quiet -e . ; \
	   echo "✓ $(MINERU_DIR) installed"; \
	 fi

# --- ui (Vite) ---------------------------------------------------------------

ui: _ui-install _ui-samples  ## start Vite dev server (foreground) — http://127.0.0.1:5173
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

_ui-samples:
	@mkdir -p $(UI_DIR)/public/samples
	@cp docs/refer-doc/sample_lc_mt700.txt $(UI_DIR)/public/samples/sample_lc_mt700.txt 2>/dev/null || true
	@cp docs/refer-doc/invoice-1-apple.pdf $(UI_DIR)/public/samples/invoice-1-apple.pdf 2>/dev/null || true
	@cp "docs/refer-doc/invoice-2-go rails.pdf" $(UI_DIR)/public/samples/invoice-2-go-rails.pdf 2>/dev/null || true
	@cp docs/refer-doc/invoice-3-color-claude.pdf $(UI_DIR)/public/samples/invoice-3-color-claude.pdf 2>/dev/null || true
	@cp docs/refer-doc/invoice-3-color-image.pdf $(UI_DIR)/public/samples/invoice-3-color-image.pdf 2>/dev/null || true

.PHONY: help all all-down status health \
        db db-down \
        svc svc-down \
        llm llm-down llm-vl llm-vl-down \
        docling docling-down \
        mineru mineru-down \
        ui ui-down \
        _db-bg _svc-bg _llm-vl-bg _docling-bg _mineru-bg _ui-bg \
        _llm-vl-install _docling-install _mineru-install _ui-install _ui-samples
