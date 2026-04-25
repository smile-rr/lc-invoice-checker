# Infra — Postgres + Compose

Infrastructure module: PostgreSQL schema v3 (unified `pipeline_steps` table),
docker-compose definitions, and init scripts.

Stage numbering matches [`docs/refer-doc/logic-flow.md`](../docs/refer-doc/logic-flow.md).

---

## 1. Running

Only Postgres runs in Docker from this module. The Java service and the optional
Python parsers run locally on the host.

```bash
# Start Postgres (data persists across restarts — see §3 Volumes)
make up                  # = docker compose up -d

# Stop Postgres, KEEP data volume
make down                # = docker compose down

# Stop Postgres AND delete data volume (schema re-applies on next `make up`)
make wipe                # = docker compose down -v

# Open a psql shell
make psql                # = docker exec -it lc-checker-postgres psql -U lcuser -d lc_checker

# Show health
make ps
```

Schema source: [`postgres/init/01-schema.sql`](postgres/init/01-schema.sql). Bind-mounted
into `/docker-entrypoint-initdb.d`; auto-runs on first cluster init (empty volume).

---

## 2. Services

| Service              | Runtime    | Port (host) | How to start                           |
|----------------------|-----------|-------------|----------------------------------------|
| `postgres`           | Docker    | 5432        | `make up`                              |
| `lc-checker-svc`     | Local JVM | 8080        | `make bootrun`                         |
| `docling-svc` (opt.) | Local Py  | 8081        | `cd extractors/docling && uvicorn app.main:app --port 8081` |
| `mineru-svc`  (opt.) | Local Py  | 8082        | `cd extractors/mineru  && uvicorn app.main:app --port 8082` |

The locally-running Java service reaches Postgres via `localhost:5432` (the host-mapped
port). Set `DB_HOST=localhost` in `.env` — already the default.

---

## 3. PostgreSQL

### Connection

| Setting               | Value                                              | Env var override |
|-----------------------|----------------------------------------------------|-----------------|
| Host (from host)      | `localhost:5432`                                   | — |
| Host (from container) | `postgres:5432`                                    | `DB_HOST` |
| Database              | `lc_checker`                                       | — |
| Username              | `lcuser`                                           | `DB_USER` |
| Password              | `lcpass123`                                        | `DB_PASSWORD` |
| JDBC URL              | `jdbc:postgresql://postgres:5432/lc_checker`       | — |

```bash
# Connect from host (install: brew install libpq)
psql -h localhost -p 5432 -U lcuser -d lc_checker

# Or via docker exec
docker exec -it lc-checker-postgres psql -U lcuser -d lc_checker
```

### Volumes

| Volume                                  | Container mount                    | Purpose                                             |
|-----------------------------------------|------------------------------------|-----------------------------------------------------|
| `lc-checker-postgres-data` (named)      | `/var/lib/postgresql/data`         | Data files — **persists across restarts**           |
| `./infra/postgres/init` (bind-mount)    | `/docker-entrypoint-initdb.d:ro`   | Init scripts (runs once on empty cluster)           |

**Data persistence matrix:**

| Command                         | Container | Data volume |
|---------------------------------|-----------|-------------|
| `docker compose restart`        | restarts  | kept        |
| `docker compose stop / start`   | stopped/started | kept  |
| `make down` (= `compose down`)  | removed   | kept        |
| `make wipe` (= `compose down -v`) | removed | **deleted** |
| Host reboot                     | restarted (restart: unless-stopped) | kept |

---

## 4. Schema v3 — summary

**Two tables:**

| Table             | Purpose                                                                 | Rows per session |
|-------------------|-------------------------------------------------------------------------|-------------------|
| `check_sessions`  | Session umbrella — request metadata, status, assembled `final_report`   | 1 |
| `pipeline_steps`  | Every pipeline step — level-1 `stage`, level-2 `step_key`, timing + JSONB `result` | N |

Every step (LC parse, each invoice source, rule activation, each rule check) is one
row in `pipeline_steps` with a uniform shape: `(status, started_at, completed_at,
duration_ms, result JSONB, error)`. Stage-specific payload lives in `result`. Views
project scalars back out when you want typed columns.

### `stage` / `step_key` taxonomy

| stage             | step_key values                         | rows per session |
|-------------------|-----------------------------------------|-------------------|
| `lc_parse`        | `-`                                     | 1 |
| `invoice_extract` | `vision` \| `docling` \| `mineru`       | N (one per enabled source; one with `is_selected=true`) |
| `rule_activation` | `-`                                     | 1 |
| `rule_check`      | rule id, e.g. `INV-011`                 | N (one per activated rule) |
| `holistic_sweep`  | `pass1` \| `pass2`                       | ≤ 2 (executor deferred) |

### `check_sessions` vs `pipeline_steps` — FAQ

- **`check_sessions`** is request-level. Created at Stage 0 with `status=RUNNING`;
  finalized at Stage 5 with `status=COMPLETED|FAILED`, `compliant`, `completed_at`,
  `final_report` JSONB.
- **`pipeline_steps`** is step-level. Every stage / every rule writes here progressively.
- Deleting a `check_sessions` row cascades to remove all its `pipeline_steps`.

---

## 5. Views — query cookbook

All views are in `postgres/init/01-schema.sql`. Group by purpose:

### Whole-session (any session)

| View                  | Description |
|-----------------------|-------------|
| `v_session_overview`  | One row per session — per-stage status, tier counts, discrepancy count, selected source |
| `v_pipeline_steps`    | Every step across all sessions (pre-sorted `session_id, started_at`) |

### Focus-field views (scalar columns per stage)

| View                 | Description |
|----------------------|-------------|
| `v_lc_parse`         | Stage 1a — every LcDocument scalar + raw `:45A:/:46A:/:47A:` text |
| `v_invoice_extracts` | Stage 1b — one row per (session, source); all key InvoiceDocument fields |
| `v_rule_activation`  | Stage 2 — unnested activations array; one row per rule per session |
| `v_rule_checks`      | Stage 3 — one row per rule check; tier + severity + lc/presented values + refs |

### Latest-session shortcuts (no session_id required)

All return rows only for the newest `check_sessions` row.

| View                         | Description |
|------------------------------|-------------|
| `v_latest_session`           | Session header + aggregated counts (1 row) |
| `v_latest_pipeline`          | Chronological step timeline |
| `v_latest_lc_parse`          | LC scalar focus fields (latest session) |
| `v_latest_invoice_extracts`  | All extractor rows (latest session) |
| `v_latest_invoice_selected`  | Selected source only — focus fields + `raw_markdown` + `raw_text` |
| `v_latest_rule_activation`   | Per-rule activation decisions |
| `v_latest_rule_checks`       | Per-rule check results |

### Raw-JSON dumps (full fidelity)

| View                     | Description |
|--------------------------|-------------|
| `v_latest_lc_json`       | Full LcDocument JSON via `jsonb_pretty()` |
| `v_latest_invoice_json`  | Selected source's full InvoiceDocument JSON + captured `llm_calls` |

---

## 6. Useful SQL — cookbook by use-case

### Where am I? What just ran?

```sql
SELECT * FROM v_latest_session;                 -- session header, 1 row
SELECT * FROM v_latest_pipeline;                -- step timeline
SELECT * FROM v_session_overview ORDER BY created_at DESC LIMIT 10;
```

### See every LC field parsed

```sql
-- Text (psql -x expanded mode prints one field per line)
\x on
SELECT * FROM v_latest_lc_parse;
\x off

-- Full LC JSON, pretty-printed
SELECT lc_output_json FROM v_latest_lc_json;

-- Just a raw free-text tag
SELECT field_45a_raw FROM v_latest_lc_parse;
```

### See every invoice field extracted

```sql
-- Scalars + markdown + raw text of the selected source (psql -x for text form)
\x on
SELECT * FROM v_latest_invoice_selected;

-- Compare all 3 sources side-by-side (scalars only)
SELECT * FROM v_latest_invoice_extracts;

-- Full InvoiceDocument JSON of the selected source
SELECT document_json FROM v_latest_invoice_json;

-- LLM prompts + responses captured by the Vision extractor
SELECT llm_calls_json FROM v_latest_invoice_json;
```

### Rule activation + checks

```sql
-- Why did each rule run / skip?
SELECT rule_id, trigger, activated, reason FROM v_latest_rule_activation;

-- Per-rule outcomes, grouped by tier
SELECT tier, rule_id, status, severity, duration_ms
FROM   v_latest_rule_checks;

-- Full SpEL + LLM trace for a specific rule
SELECT jsonb_pretty(result->'trace')
FROM   pipeline_steps
WHERE  stage = 'rule_check' AND step_key = 'INV-015'
  AND  session_id = (SELECT id FROM check_sessions ORDER BY created_at DESC LIMIT 1);
```

### Final report

```sql
SELECT jsonb_pretty(final_report) FROM check_sessions
WHERE  id = (SELECT id FROM check_sessions ORDER BY created_at DESC LIMIT 1);
```

### Anything else — drop to `pipeline_steps.result` directly

```sql
-- Any step's full payload, any session
SELECT stage, step_key, jsonb_pretty(result)
FROM   pipeline_steps
WHERE  session_id = '<uuid>'
ORDER BY started_at;

-- Discrepancies in last 24h across all sessions
SELECT * FROM v_rule_checks WHERE status = 'DISCREPANT'
  AND started_at > NOW() - INTERVAL '1 day' ORDER BY started_at DESC;

-- Retention cleanup — FK cascade removes pipeline_steps rows too
DELETE FROM check_sessions WHERE created_at < NOW() - INTERVAL '30 days';
```

---

## 7. Environment variables

| Variable                         | Default                                  | Description |
|----------------------------------|------------------------------------------|-------------|
| `DB_HOST`                        | `localhost`                              | Postgres host |
| `DB_PORT`                        | `5432`                                   | Postgres port |
| `DB_USER`                        | `lcuser`                                 | Postgres user |
| `DB_PASSWORD`                    | `lcpass123`                              | Postgres password |
| `POSTGRES_PORT`                  | `5432`                                   | Host-side port |
| `API_PORT`                       | `8080`                                   | lc-checker-svc host port |
| `DOCLING_PORT`                   | `8081`                                   | docling-svc host port (full profile) |
| `MINERU_PORT`                    | `8082`                                   | mineru-svc host port (full profile) |
| `HF_TOKEN`                       | _(empty)_                                | Hugging Face token for faster model downloads in Docling / MinerU image builds |

Service-level env vars (LLM, extractor flags) are documented in
[`../lc-checker-svc/README.md`](../lc-checker-svc/README.md).
