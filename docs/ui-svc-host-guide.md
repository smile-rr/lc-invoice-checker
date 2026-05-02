# Ubuntu Hosting Guide — lc-checker v1

## Architecture overview

```
Internet / LAN
      │
   Traefik  (reverse proxy, already running on Ubuntu)
      │
      ├── lc-checker-ui  (nginx, port 80 internal)
      │      └── /api/* → lc-checker-svc:8080  (internal Docker network)
      │      └── /      → React SPA (static files)
      │
      └── (lc-checker-svc is NOT exposed directly to Traefik —
           nginx handles the /api proxy inside the same Docker network)
```

**Why this layout:** the nginx container already reverse-proxies `/api/*` to the
Java service using the Docker service name `lc-checker-svc`. Traefik only needs
to route traffic to the nginx container. No duplicate routing, no CORS.

---

## Prerequisites on Ubuntu

| What | Where |
|---|---|
| Traefik running | existing Docker container, shared external network |
| Shared PostgreSQL | `192.168.31.214:5436` |
| MinIO | `192.168.31.214:9000` |
| Langfuse | `192.168.31.214:3300` |
| Mac Ollama reachable | `192.168.31.201:11434` (vision model) |
| Repo cloned | e.g. `/opt/lc-checker/v1-invoice-check` |
| `.env.ubuntu` copied | into `v1-invoice-check/` (gitignored, copy manually) |

---

## Step 1 — Know your Traefik network name

Traefik discovers services via a shared external Docker network. Find yours:

```bash
docker network ls | grep -i traefik
# likely: traefik-public, proxy, or traefik-net
```

Use that name as `<TRAEFIK_NETWORK>` in Step 2.

---

## Step 2 — Update docker-compose.yml with Traefik labels

The current `docker-compose.yml` has no Traefik labels. Add them before deploying.
Edit `v1-invoice-check/docker-compose.yml` — replace with:

```yaml
name: lc-checker

services:

  lc-checker-svc:
    build:
      context: ./lc-checker-svc
    image: lc-checker-svc:v1
    container_name: lc-checker-svc
    # No external port binding — only reachable via lc-checker-net.
    # Traefik does NOT route here; nginx handles /api proxy internally.
    env_file: .env.ubuntu
    restart: unless-stopped
    networks:
      - lc-checker-net

  ui:
    build:
      context: ./ui
    image: lc-checker-ui:v1
    container_name: lc-checker-ui
    # No ports: block — Traefik accesses port 80 via Docker network directly.
    restart: unless-stopped
    networks:
      - lc-checker-net
      - <TRAEFIK_NETWORK>        # ← replace with actual Traefik network name
    labels:
      - "traefik.enable=true"
      # --- HTTP router (redirect to HTTPS if TLS is active) ---
      - "traefik.http.routers.lc-ui.rule=Host(`<YOUR_DOMAIN>`)"
      - "traefik.http.routers.lc-ui.entrypoints=web"          # or websecure for HTTPS
      # --- TLS (uncomment if Traefik has cert resolver configured) ---
      # - "traefik.http.routers.lc-ui.entrypoints=websecure"
      # - "traefik.http.routers.lc-ui.tls=true"
      # - "traefik.http.routers.lc-ui.tls.certresolver=myresolver"
      # --- Backend port (nginx listens on 80) ---
      - "traefik.http.services.lc-ui.loadbalancer.server.port=80"

networks:
  lc-checker-net:
    driver: bridge
  <TRAEFIK_NETWORK>:             # ← same name as above
    external: true
```

**Fill in:**
- `<YOUR_DOMAIN>` — e.g. `lc.moments-plus.com` or `192.168.31.214` for LAN-only
- `<TRAEFIK_NETWORK>` — the external Docker network Traefik uses

> `lc-checker-svc` stays on `lc-checker-net` only — it is never directly
> reachable from outside. Only nginx reaches it.

---

## Step 3 — Prepare the environment file

The `.env.ubuntu` file is gitignored (contains secrets). Copy it to the server:

```bash
# From your Mac:
scp v1-invoice-check/.env.ubuntu user@192.168.31.214:/opt/lc-checker/v1-invoice-check/
```

Or create it manually on Ubuntu using the template below. Key values:

```env
DB_HOST=192.168.31.214
DB_PORT=5436
LOCAL_LLM_VL_BASE_URL=http://192.168.31.201:11434/v1
EXTRACTOR_DOCLING_ENABLED=false
EXTRACTOR_MINERU_ENABLED=false
# ... rest of cloud API keys, MinIO, Langfuse (same as local .env)
```

---

## Step 4 — Build and start

```bash
cd /opt/lc-checker/v1-invoice-check

# First deploy or after code changes:
docker compose build
docker compose up -d

# Rebuild one service only (faster iteration):
docker compose build lc-checker-svc && docker compose up -d lc-checker-svc
docker compose build ui            && docker compose up -d ui
```

---

## Step 5 — Verify

```bash
# 1. Both containers running
docker compose ps

# 2. Java service healthy (direct — bypasses Traefik)
curl http://192.168.31.214:8080/actuator/health    # only if API_PORT is exposed
# OR check from inside the Docker network:
docker exec lc-checker-ui wget -qO- http://lc-checker-svc:8080/actuator/health

# 3. HikariCP connected to shared postgres (no errors = good)
docker logs lc-checker-svc 2>&1 | grep -i "hikari\|pool"

# 4. Traefik picked up the ui route
docker logs <traefik-container-name> 2>&1 | grep lc-ui

# 5. Full end-to-end via Traefik
curl -I http://<YOUR_DOMAIN>/
curl -I http://<YOUR_DOMAIN>/api/actuator/health
```

---

## Updating the deployment

```bash
cd /opt/lc-checker/v1-invoice-check
git pull

# Rebuild only what changed:
docker compose build lc-checker-svc
docker compose up -d lc-checker-svc   # zero-downtime: compose replaces the container

# If UI (nginx.conf or React) changed:
docker compose build ui
docker compose up -d ui
```

---

## Logs and troubleshooting

```bash
# Tail both services
docker compose logs -f

# Tail one service
docker compose logs -f lc-checker-svc
docker compose logs -f ui

# Common issues:
# 1. HikariCP timeout → check DB_HOST=192.168.31.214 / DB_PORT=5436 in .env.ubuntu
#    Also verify Ubuntu can reach shared postgres: nc -zv 192.168.31.214 5436
# 2. Ollama call fails → Mac must have Ollama running: ollama serve
#    Check connectivity: curl http://192.168.31.201:11434/api/tags
# 3. 502 Bad Gateway from Traefik → ui container not on <TRAEFIK_NETWORK>,
#    or Traefik network name is wrong in docker-compose.yml
# 4. nginx 502 on /api/* → lc-checker-svc not healthy or not on lc-checker-net;
#    check: docker exec lc-checker-ui wget -qO- http://lc-checker-svc:8080/actuator/health
```

---

## Network diagram (Docker-level)

```
Ubuntu host
├── Docker network: <TRAEFIK_NETWORK>  (external, shared)
│   ├── traefik container
│   └── lc-checker-ui (nginx)  ← Traefik routes to here
│
├── Docker network: lc-checker-net  (internal, isolated)
│   ├── lc-checker-ui (nginx)  — proxies /api/* internally
│   └── lc-checker-svc (Java)  — only reachable via lc-checker-net
│
└── Host network (192.168.31.214)
    ├── postgres :5436
    ├── MinIO    :9000
    └── Langfuse :3300

Mac (192.168.31.201)
└── Ollama :11434  ← lc-checker-svc calls this over LAN
```
