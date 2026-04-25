#!/bin/bash
# infra/scripts/setup-mac.sh — One-time Mac setup for LC Checker development
#
# Run this once after cloning the repo, or whenever you set up a new Mac.
# Safe to re-run.
#
# What it installs:
#   - Ollama (local LLM/Vision)
#   - Homebrew dependencies
#   - Required Ollama models (qwen3-vl:2b for vision)
#
# What it configures:
#   - .env from .env.example (if not exists)
#   - Ollama as a login item
#
# Usage:
#   ./scripts/setup-mac.sh
#
# Owner: devops

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()  { echo -e "${BLUE}==>${NC} $*"; }

# ---------------------------------------------------------------------------
# Check prerequisites
# ---------------------------------------------------------------------------
check_command() {
    if ! command -v "$1" &>/dev/null; then
        log_error "$1 not found. Please install $2"
        return 1
    fi
    log_info "$1 found: $(command -v "$1")"
    return 0
}

# ---------------------------------------------------------------------------
# Install Homebrew packages
# ---------------------------------------------------------------------------
install_homebrew() {
    log_step "Checking Homebrew..."
    if ! command -v brew &>/dev/null; then
        log_info "Installing Homebrew..."
        /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    fi
    log_info "Homebrew is ready"
}

install_ollama() {
    log_step "Installing Ollama..."
    if command -v ollama &>/dev/null; then
        log_info "Ollama already installed: $(ollama --version)"
    else
        log_info "Installing Ollama via Homebrew..."
        brew install ollama
    fi

    # Ensure Ollama starts on login
    log_info "Enabling Ollama as a login item..."
    brew services start ollama 2>/dev/null || true

    log_info "Ollama installed"
}

install_models() {
    log_step "Installing Ollama models..."

    # Vision model (primary - for invoice extraction)
    local vision_model="${VISION_MODEL:-qwen3-vl:2b}"
    log_info "Pulling vision model: $vision_model"
    if ollama list 2>/dev/null | grep -q "^$vision_model"; then
        log_info "Model $vision_model already installed"
    else
        ollama pull "$vision_model"
    fi

    # LLM model (for MT700 semantic checks)
    local llm_model="${LLM_MODEL:-qwen2.5:3b}"
    log_info "Pulling LLM model: $llm_model"
    if ollama list 2>/dev/null | grep -q "^$llm_model"; then
        log_info "Model $llm_model already installed"
    else
        ollama pull "$llm_model"
    fi

    log_info "Models installed"
}

# ---------------------------------------------------------------------------
# Configure environment
# ---------------------------------------------------------------------------
configure_env() {
    log_step "Configuring environment..."

    cd "$PROJECT_ROOT"

    if [ -f .env ]; then
        log_info ".env already exists, skipping"
    else
        if [ -f .env.example ]; then
            log_info "Creating .env from .env.example..."
            cp .env.example .env
            log_warn "Please edit .env and set LLM_API_KEY if using cloud fallback"
        else
            log_error ".env.example not found"
            return 1
        fi
    fi

    # Update .env for local Mac dev if not already configured
    if grep -q "VISION_BASE_URL=http://host.docker.internal:11434" .env 2>/dev/null; then
        log_info "Updating .env to use local Ollama..."
        sed -i '' 's|VISION_BASE_URL=http://host.docker.internal:11434/v1|VISION_BASE_URL=http://localhost:11434/v1|g' .env
        sed -i '' 's|LLM_BASE_URL=https://api.deepseek.com|LLM_BASE_URL=http://localhost:11434/v1|g' .env
        sed -i '' 's|LLM_MODEL=deepseek-chat|LLM_MODEL=qwen2.5:3b|g' .env
        log_info ".env updated for local Mac dev"
    fi

    log_info "Environment configured"
}

# ---------------------------------------------------------------------------
# Install Java (if needed)
# ---------------------------------------------------------------------------
install_java() {
    log_step "Checking Java..."
    if command -v java &>/dev/null; then
        local java_version
        java_version=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
        log_info "Java installed: $java_version"
        if [[ "$java_version" == 21* ]]; then
            log_info "Java 21 detected, no action needed"
        else
            log_warn "Java $java_version detected, Java 21 recommended"
            log_info "Install with: brew install openjdk@21"
        fi
    else
        log_info "Java not found, installing OpenJDK 21..."
        brew install openjdk@21
        log_info "Java 21 installed. You may need to set JAVA_HOME."
    fi
}

# ---------------------------------------------------------------------------
# Pull Docker images
# ---------------------------------------------------------------------------
pull_docker_images() {
    log_step "Pulling Docker images..."
    log_info "PostgreSQL 16..."
    docker pull postgres:16-alpine
    log_info "Docker images ready"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    log_info "=== LC Checker Mac Setup ==="
    echo ""

    install_homebrew
    install_ollama
    install_models
    configure_env
    install_java
    pull_docker_images

    echo ""
    log_info "=== Setup Complete ==="
    echo ""
    echo "Next steps:"
    echo "  1. Edit .env and set VISION_MODEL if different"
    echo "  2. Start services: ./infra/scripts/start-local.sh"
    echo "  3. Test: curl http://localhost:8080/actuator/health"
    echo ""
}

main "$@"
