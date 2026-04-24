# lc-checker — local dev helpers
#
# Usage:
#   make help        # list targets
#   make up          # docker compose up -d (builds images on first run)
#   make down        # stop + remove containers (keeps images, volumes)
#   make rebuild     # force image rebuild then restart
#   make clean       # down + remove local images + prune compose build cache
#   make logs        # tail all service logs
#   make logs-api    # lc-checker-api only
#   make logs-docling
#   make logs-mineru
#   make status      # container + healthcheck state
#   make env-check   # validate .env and compose config
#   make curl-demo   # invoke test/check.sh once it exists (qa task #27)
#
# Owner: devops. Ties to docker-compose.yml in the same directory.
# ---------------------------------------------------------------------------

SHELL   := /usr/bin/env bash
COMPOSE := docker compose
PROJECT := lc-checker

.DEFAULT_GOAL := help

.PHONY: help up up-dev down logs logs-api logs-docling logs-mineru status rebuild clean curl-demo env-check ps build \
	build-docling build-mineru run-docling run-mineru stop-docling stop-mineru logs-docling-native logs-mineru-native

help:
	@echo "lc-checker — Makefile targets"
	@echo ""
	@awk 'BEGIN{FS=":.*##"; printf "  \033[36m%-16s\033[0m %s\n", "target", "description"} \
	      /^[a-zA-Z0-9_-]+:.*##/ {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

env-check:  ## Validate .env and compose config
	@[ -f .env ] || { \
	  echo "✗ .env missing. Run: cp .env.example .env  (then set LLM_API_KEY)"; \
	  exit 1; \
	}
	@key=$$(grep -E '^LLM_API_KEY=' .env | head -1 | cut -d= -f2-); \
	 if [ -z "$$key" ] || [ "$$key" = "sk-REPLACE_ME" ]; then \
	   echo "✗ LLM_API_KEY in .env is unset or still the placeholder"; exit 1; \
	 fi
	@$(COMPOSE) config --quiet && echo "✓ compose config valid, .env looks good"

build: env-check  ## Build images without starting
	$(COMPOSE) build

# --- Individual extractor targets (use Dockerfile.dev — single-stage, no warm-up) ---

build-docling:  ## Build docling-svc image (Dockerfile.dev)
	docker build -t lc-checker/docling:dev -f extractors/docling/Dockerfile.dev extractors/docling

build-mineru:  ## Build mineru-svc image (Dockerfile.dev)
	docker build -t lc-checker/mineru:dev -f extractors/mineru/Dockerfile.dev extractors/mineru

run-docling: build-docling  ## Build and run docling-svc in foreground (Ctrl-C to stop)
	docker run --rm -p 8081:8081 --env-file .env --name docling-svc lc-checker/docling:dev

run-mineru: build-mineru  ## Build and run mineru-svc in foreground (Ctrl-C to stop)
	docker run --rm -p 8082:8082 --env-file .env --name mineru-svc lc-checker/mineru:dev

stop-docling:  ## Stop and remove any running docling-svc container
	-docker stop docling-svc 2>/dev/null; docker rm docling-svc 2>/dev/null; true

stop-mineru:  ## Stop and remove any running mineru-svc container
	-docker stop mineru-svc 2>/dev/null; docker rm mineru-svc 2>/dev/null; true

logs-docling-native:  ## Tail logs from native docling-svc run (not compose)
	@docker logs -f --tail=100 docling-svc 2>&1 || echo "(no docling-svc container running)"

logs-mineru-native:  ## Tail logs from native mineru-svc run (not compose)
	@docker logs -f --tail=100 mineru-svc 2>&1 || echo "(no mineru-svc container running)"

up-dev: env-check  ## Start docling + mineru only (no Java API), then show status
	$(COMPOSE) up -d docling-svc mineru-svc
	@echo ""
	@$(MAKE) --no-print-directory status

up: env-check  ## Start the full stack in the background
	$(COMPOSE) up -d --build
	@echo ""
	@$(MAKE) --no-print-directory status

down:  ## Stop and remove containers (keeps images + volumes)
	$(COMPOSE) down

logs:  ## Tail all service logs
	$(COMPOSE) logs -f --tail=100

logs-api:  ## Tail lc-checker-api logs
	$(COMPOSE) logs -f --tail=200 lc-checker-api

logs-docling:  ## Tail docling-svc logs
	$(COMPOSE) logs -f --tail=200 docling-svc

logs-mineru:  ## Tail mineru-svc logs (live after task #24 compose finalization)
	$(COMPOSE) logs -f --tail=200 mineru-svc

ps status:  ## Show container + healthcheck status
	@$(COMPOSE) ps
	@echo ""
	@echo "Healthcheck summary:"
	@ids=$$($(COMPOSE) ps -q); \
	  if [ -z "$$ids" ]; then echo "  (no containers running)"; else \
	    docker inspect --format '  {{.Name}}: {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' $$ids; \
	  fi

rebuild:  ## Force full image rebuild (no cache) then restart
	$(COMPOSE) build --no-cache
	$(COMPOSE) up -d --force-recreate
	@echo ""
	@$(MAKE) --no-print-directory status

clean:  ## Stop stack + remove local images + prune build cache
	$(COMPOSE) down --rmi local --volumes --remove-orphans
	-docker builder prune -f --filter label=com.docker.compose.project=$(PROJECT)

curl-demo:  ## Run end-to-end curl demo (requires test/check.sh from qa task #27)
	@[ -x test/check.sh ] || { \
	  echo "✗ test/check.sh not found or not executable."; \
	  echo "  It's owned by qa (task #27 — Running Guide + check.sh)."; \
	  exit 1; \
	}
	@bash test/check.sh
