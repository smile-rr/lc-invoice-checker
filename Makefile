# lc-checker — local dev helpers
#
# Workflow:
#   - Postgres runs in Docker (this Makefile's `make up`).
#   - lc-checker-svc runs locally via `./gradlew bootRun` (`make bootrun`).
#   - docling-svc / mineru-svc (optional) run locally via uvicorn.
#
# Data persistence: named volume `lc-checker-postgres-data` survives `down` / `up`
# and `restart`. Use `make wipe` to blow it away and re-apply 01-schema.sql.
#
# Usage:
#   make help             # list targets
#   make up               # start Postgres in Docker
#   make down             # stop Postgres (KEEPS data)
#   make wipe             # stop Postgres AND remove data volume
#   make ps               # show container health
#   make psql             # open psql shell against the Docker Postgres
#   make bootrun          # run the Java service locally (expects `make up` done)
#   make curl-demo        # end-to-end curl test
# ---------------------------------------------------------------------------

SHELL    := /bin/bash
COMPOSE  := docker compose
ENV_FILE := .env

.DEFAULT_GOAL := help

.PHONY: help env-check up down wipe ps logs psql bootrun curl-demo ui-samples ui-dev ui-build ui-lint

help:  ## list targets
	@echo "lc-checker — Makefile targets"
	@echo ""
	@awk 'BEGIN{FS=":.*##"; printf "  \033[36m%-14s\033[0m %s\n", "target", "description"} \
	      /^[a-zA-Z0-9_-]+:.*##/ {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

env-check:  ## validate .env exists
	@([ -f $(ENV_FILE) ] || { \
	  echo "✗ .env missing. Run: cp .env.example .env"; \
	  exit 1; \
	})
	@echo "✓ .env found"

# --- Docker Postgres --------------------------------------------------------

up: env-check  ## start Postgres in Docker
	$(COMPOSE) up -d
	@$(MAKE) --no-print-directory ps

down:  ## stop Postgres container (keeps data volume)
	$(COMPOSE) down

wipe:  ## stop Postgres AND delete data volume (re-runs 01-schema.sql on next up)
	$(COMPOSE) down -v

ps:  ## show container + healthcheck status
	@$(COMPOSE) ps
	@echo ""
	@ids=$$($(COMPOSE) ps -q); \
	  if [ -z "$$ids" ]; then echo "  (no containers running)"; else \
	    docker inspect --format '  {{.Name}}: {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' $$ids; \
	  fi

logs:  ## tail Postgres logs
	$(COMPOSE) logs -f --tail=100

psql:  ## open psql shell against the Docker Postgres
	@docker exec -it lc-checker-postgres psql -U lcuser -d lc_checker

# --- Local Java service -----------------------------------------------------

bootrun: env-check  ## run lc-checker-svc locally via Gradle (needs `make up` first)
	@cd lc-checker-svc && set -a && source ../$(ENV_FILE) && set +a && ./gradlew bootRun

# --- End-to-end demo --------------------------------------------------------

curl-demo:  ## run end-to-end curl demo (requires `make up` + `make bootrun`)
	@bash test/check.sh

# --- UI (React + Vite) ------------------------------------------------------

ui-samples:  ## copy sample LC + every invoice into ui/public/samples for the demo
	@mkdir -p ui/public/samples
	@cp docs/refer-doc/sample_lc_mt700.txt ui/public/samples/sample_lc_mt700.txt
	@cp docs/refer-doc/invoice-1-apple.pdf ui/public/samples/invoice-1-apple.pdf
	@cp "docs/refer-doc/invoice-2-go rails.pdf" ui/public/samples/invoice-2-go-rails.pdf
	@cp docs/refer-doc/invoice-3-color-claude.pdf ui/public/samples/invoice-3-color-claude.pdf
	@cp docs/refer-doc/invoice-3-color-image.pdf ui/public/samples/invoice-3-color-image.pdf
	@echo "✓ samples copied to ui/public/samples/"

ui-dev: ui-samples  ## run the Vite dev server (proxies /api → :8080)
	@cd ui && npm install --silent && npm run dev

ui-build: ui-samples  ## type-check + production build into ui/dist
	@cd ui && npm install --silent && npm run build

ui-lint:  ## type-check only
	@cd ui && npm run lint
