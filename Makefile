# lc-checker — Makefile (minimal)
#
# Default: `make <svc>` runs in the FOREGROUND (logs in this terminal, Ctrl-C stops).
# To background:    make svc > /tmp/svc.log 2>&1 &
# Stop background:  make svc-down   (kills by port; works whether fg or bg)
#
# Whole stack:      make all                    # start (or restart) everything in background
#                   make all EXCEPT=mineru      # everything except mineru
#                   make all EXCEPT="mineru docling"
#                   make all-down               # stop everything
# Background logs land in /tmp/lc-checker/<svc>.log
#
# Service          Port   URL
# db (postgres)    5432   postgres://localhost:5432         (local fallback; .env may point remote)
# svc              8080   http://127.0.0.1:8080
# llm  (mlx-vlm)   8088   http://127.0.0.1:8088/v1
# docling          8081   http://127.0.0.1:8081
# mineru           8082   http://127.0.0.1:8082
# ui   (vite)      5173   http://127.0.0.1:5173
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
ALL      := db llm docling mineru svc ui   # startup order: db, llm, extractors, svc, ui
EXCEPT   ?=
SELECTED := $(filter-out $(EXCEPT),$(ALL))

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

all: $(LOG_DIR)  ## start (restart) all services in background; EXCEPT="svc1 svc2" to skip — logs in /tmp/lc-checker/
	@echo "→ all: $(SELECTED)$(if $(EXCEPT),  (skipping: $(EXCEPT)),)"
	@for s in $(SELECTED); do $(MAKE) --no-print-directory $$s-down 2>/dev/null || true; done
	@for s in $(SELECTED); do $(MAKE) --no-print-directory _$$s-bg; done
	@echo ""
	@echo "✓ stack up — logs in $(LOG_DIR)/<svc>.log   (stop with: make all-down)"

all-down:  ## stop everything
	@for s in $(ALL); do $(MAKE) --no-print-directory $$s-down 2>/dev/null || true; done

# private bg helpers — invoked by `all`, not in user-facing help
_db-bg:
	@$(MAKE) --no-print-directory db

_svc-bg:
	@(cd $(SVC_DIR) && set -a && source ../$(ENV_FILE) && set +a && \
	  nohup ./gradlew bootRun > $(LOG_DIR)/svc.log 2>&1 &) \
	  && echo "✓ svc bg → http://127.0.0.1:8080"

_llm-bg: _llm-install
	@(cd $(LLM_DIR) && nohup .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8088 \
	    > $(LOG_DIR)/llm.log 2>&1 &) \
	  && echo "✓ llm bg → http://127.0.0.1:8088/v1"

_docling-bg: _docling-install
	@(cd $(DOCLING_DIR) && nohup .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8081 \
	    > $(LOG_DIR)/docling.log 2>&1 &) \
	  && echo "✓ docling bg → http://127.0.0.1:8081"

_mineru-bg: _mineru-install
	@(cd $(MINERU_DIR) && nohup .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8082 \
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

# --- llm (MLX vision+text) ---------------------------------------------------

llm: _llm-install  ## start MLX LLM server (foreground) — http://127.0.0.1:8088/v1
	@echo "→ llm on :8088 → http://127.0.0.1:8088/v1   (Ctrl-C to stop)"
	@cd $(LLM_DIR) && .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8088

llm-down:  ## stop MLX LLM server (kills :8088)
	@pid=$$(lsof -ti tcp:8088 2>/dev/null); \
	  if [ -n "$$pid" ]; then kill $$pid && echo "✓ llm stopped"; else echo "(llm not running)"; fi

_llm-install:
	@if [ ! -d $(LLM_DIR)/.venv ]; then \
	   echo "→ first-time install in $(LLM_DIR) (~3 min, no model download yet)"; \
	   cd $(LLM_DIR) && $(PYTHON) -m venv .venv && \
	     .venv/bin/pip install --upgrade --quiet pip && \
	     .venv/bin/pip install --quiet -e . ; \
	   [ -f $(LLM_DIR)/.env ] || cp $(LLM_DIR)/.env.example $(LLM_DIR)/.env ; \
	   echo "✓ $(LLM_DIR) installed"; \
	 fi

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
	@cd $(MINERU_DIR) && .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8082

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

.PHONY: help all all-down \
        db db-down \
        svc svc-down \
        llm llm-down \
        docling docling-down \
        mineru mineru-down \
        ui ui-down \
        _db-bg _svc-bg _llm-bg _docling-bg _mineru-bg _ui-bg \
        _llm-install _docling-install _mineru-install _ui-install _ui-samples
