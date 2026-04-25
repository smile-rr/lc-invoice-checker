#!/bin/bash
# infra/scripts/start-local.sh — Start full local dev stack on Mac
#
# Prerequisites (one-time setup):
#   ./setup-mac.sh
#
# What this starts:
#   1. PostgreSQL (Docker) — lc-checker-infra
#   2. Ollama (native Mac app) — runs as local service
#   3. lc-checker-svc (Java Spring Boot)
#
# Usage:
#   ./scripts/start-local.sh          # Start everything
#   ./scripts/start-local.sh --api   # Start API only (Ollama/DB already running)
#   ./scripts/start-local.sh --status # Check status
#   Ctrl+C                           # Stop
#
# Owner: devops

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
INFRA_DIR="$PROJECT_ROOT/infra"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------

check_port() {
    local port=$1
    local name=$2
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
        log_info "$name is running on port $port"
        return 0
    else
        return 1
    fi
}

check_ollama_models() {
    log_info "Checking Ollama models..."
    if ! command -v ollama &>/dev/null; then
        log_error "Ollama not found. Run: brew install ollama"
        return 1
    fi

    local model="${VISION_MODEL:-qwen3-vl:2b}"
    if ollama list 2>/dev/null | grep -q "$model"; then
        log_info "Model '$model' is available"
    else
        log_warn "Model '$model' not found. Pulling..."
        ollama pull "$model"
    fi
}

# ---------------------------------------------------------------------------
# Start PostgreSQL (Docker)
# ---------------------------------------------------------------------------
start_postgres() {
    log_info "Starting PostgreSQL (Docker)..."
    cd "$INFRA_DIR"
    if docker compose up -d postgres 2>/dev/null; then
        log_info "PostgreSQL started"
        # Wait for it to be healthy
        local count=0
        while ! docker exec lc-checker-postgres pg_isready -U lcuser -d lc_checker &>/dev/null; do
            count=$((count+1))
            if [ $count -gt 30 ]; then
                log_error "PostgreSQL failed to start"
                exit 1
            fi
            sleep 1
        done
        log_info "PostgreSQL is ready"
    else
        log_error "Failed to start PostgreSQL"
        exit 1
    fi
}

# ---------------------------------------------------------------------------
# Start Ollama (native Mac)
# ---------------------------------------------------------------------------
start_ollama() {
    log_info "Starting Ollama..."

    # Check if Ollama is installed
    if ! command -v ollama &>/dev/null; then
        log_error "Ollama not installed. Run: brew install ollama"
        exit 1
    fi

    # Check if already running
    if check_port 11434 "Ollama"; then
        log_info "Ollama already running"
    else
        # Start Ollama as background service
        log_info "Starting Ollama service..."
        brew services start ollama 2>/dev/null || {
            # Fallback: start directly
            log_warn "brew services not available, starting ollama directly"
            nohup ollama serve > /tmp/ollama.log 2>&1 &
            sleep 3
        }
        if check_port 11434 "Ollama"; then
            log_info "Ollama service started"
        else
            log_error "Failed to start Ollama"
            exit 1
        fi
    fi

    check_ollama_models
}

# ---------------------------------------------------------------------------
# Start Java API
# ---------------------------------------------------------------------------
start_api() {
    log_info "Starting lc-checker-svc (Java Spring Boot)..."

    # Check if running on port 8080
    if check_port 8080 "lc-checker-svc"; then
        log_info "lc-checker-svc already running"
        return 0
    fi

    # Load .env if exists
    if [ -f "$PROJECT_ROOT/.env" ]; then
        log_info "Loading .env configuration..."
        set -a
        source "$PROJECT_ROOT/.env"
        set +a
    fi

    # Start Java API
    cd "$PROJECT_ROOT/lc-checker-svc"
    if [ -f ./gradlew ]; then
        chmod +x ./gradlew
        ./gradlew bootRun > /tmp/lc-checker-api.log 2>&1 &
    else
        log_error "gradlew not found in lc-checker-svc/"
        exit 1
    fi

    # Wait for API to start
    log_info "Waiting for API to start..."
    local count=0
    while ! curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
        count=$((count+1))
        if [ $count -gt 60 ]; then
            log_error "API failed to start (check /tmp/lc-checker-api.log)"
            exit 1
        fi
        sleep 2
    done
    log_info "lc-checker-svc is ready"
}

# ---------------------------------------------------------------------------
# Status check
# ---------------------------------------------------------------------------
show_status() {
    echo ""
    echo "=== Service Status ==="
    echo ""

    local all_ok=true

    if check_port 5432 "PostgreSQL"; then
        echo -e "  ${GREEN}✓${NC} PostgreSQL 5432"
    else
        echo -e "  ${RED}✗${NC} PostgreSQL 5432"
        all_ok=false
    fi

    if check_port 11434 "Ollama"; then
        echo -e "  ${GREEN}✓${NC} Ollama 11434"
    else
        echo -e "  ${RED}✗${NC} Ollama 11434"
        all_ok=false
    fi

    if check_port 8080 "lc-checker-svc"; then
        echo -e "  ${GREEN}✓${NC} lc-checker-svc 8080"
    else
        echo -e "  ${RED}✗${NC} lc-checker-svc 8080"
        all_ok=false
    fi

    echo ""

    # Show Ollama models
    if command -v ollama &>/dev/null; then
        echo "=== Ollama Models ==="
        ollama list 2>/dev/null | grep -E "NAME|qwen" || echo "  (none installed)"
        echo ""
    fi

    if $all_ok; then
        log_info "All services are running"
    else
        log_warn "Some services are not running"
    fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    cd "$PROJECT_ROOT"

    case "${1:-start}" in
        --status|-s)
            show_status
            ;;
        --api)
            start_api
            ;;
        --help|-h)
            echo "Usage: $0 [--status|--api|--help]"
            echo ""
            echo "  (no args)  Start full local stack"
            echo "  --status   Check service status"
            echo "  --api      Start API only"
            echo "  --help     Show this help"
            ;;
        *)
            log_info "=== Starting LC Checker Local Dev Stack ==="
            start_postgres
            start_ollama
            start_api
            echo ""
            log_info "=== All services started ==="
            show_status
            ;;
    esac
}

main "$@"
