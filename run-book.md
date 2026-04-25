# Run Book

Minimal commands to start, verify, inspect, and stop the LC Invoice Checker.

Runtime layout: **Postgres in Docker** · **Java service + Python parsers local**.

---

## 1. Start

```bash
cp .env.example .env          # first time only — then edit your keys
make up                        # start Postgres (data persists across restarts)
make bootrun                   # start Java service locally (foreground)
```

API ready when:
```bash
curl -fsS http://localhost:8080/actuator/health | jq      # → {"status":"UP"}
```

Schema check (6 tables expected):
```bash
make psql -- -c "\dt"
```

---

## 2. Verify each stage

### Stage 1a — LC parse (regex, no LLM)
```bash
curl -sS -X POST http://localhost:8080/api/v1/debug/mt700/parse \
  -H "Content-Type: text/plain" \
  --data-binary @docs/refer-doc/sample_lc_mt700.txt
```

### Stage 1b — Invoice extract (every enabled source, side-by-side)
```bash
curl -sS -X POST http://localhost:8080/api/v1/debug/invoice/compare \
  -F "invoice=@docs/refer-doc/invoice-1-apple.pdf"
```

### Full pipeline (Stages 1a → 5)
```bash
SID=$(curl -sS -X POST http://localhost:8080/api/v1/lc-check \
        -F "lc=@docs/refer-doc/sample_lc_mt700.txt;type=text/plain" \
        -F "invoice=@docs/refer-doc/invoice-1-apple.pdf;type=application/pdf" \
      | jq -r '.session_id')
echo "Session: $SID"

curl -sS http://localhost:8080/api/v1/lc-check/${SID}/trace | jq
```

---

## 3. Inspect the DB

```bash
# Everything at a glance for the latest sessions
make psql -- -c "SELECT * FROM v_session_overview ORDER BY created_at DESC LIMIT 5;"

# Extractor sources side-by-side for one session
make psql -- -c "SELECT source,status,is_selected,confidence FROM stage_invoice_extract WHERE session_id='${SID}' ORDER BY source;"

# Rule checks grouped by tier
make psql -- -c "SELECT tier,rule_id,status,severity FROM stage_rule_check WHERE session_id='${SID}' ORDER BY tier,rule_id;"

# Final report JSON
make psql -- -c "SELECT jsonb_pretty(final_report) FROM check_sessions WHERE id='${SID}';"
```

---

## 4. Enable Docling / MinerU (optional)

Each runs locally via uvicorn. Start in separate terminals:
```bash
cd extractors/docling && uvicorn app.main:app --port 8081
cd extractors/mineru  && uvicorn app.main:app --port 8082
```

Then in `.env`:
```
EXTRACTOR_DOCLING_ENABLED=true
EXTRACTOR_MINERU_ENABLED=true
```
Restart `make bootrun`. Rerun Stage 1b to see three rows persisted per session.

---

## 5. Stop

```bash
# Ctrl-C the `make bootrun` process
make down        # stop Postgres, KEEP data
make wipe        # stop Postgres AND delete data volume (schema re-applies on next `make up`)
```

Data persistence:
| Command           | Container | Volume   |
|-------------------|-----------|----------|
| `docker restart`  | restarts  | kept     |
| `make down`       | removed   | kept     |
| Host reboot       | auto-up   | kept     |
| `make wipe`       | removed   | **gone** |

---

## 6. Common issues

| Symptom                                 | Fix |
|-----------------------------------------|-----|
| `502 EXTRACTOR_UNAVAILABLE`             | Confirm Ollama serving `VISION_MODEL`; or enable docling/mineru and start them via uvicorn |
| `400 LC_PARSE_ERROR`                    | Missing mandatory MT700 tag (:20: :31D: :32B: :45A: :50: :59:) |
| Trace has empty `checks[]`              | Pipeline failed early — `make psql -- -c "SELECT status,error FROM check_sessions WHERE id='${SID}';"` |
| Slow first request                      | Cold Ollama model; first call 10–30s, subsequent fast |
| API can't reach Postgres                | Confirm `DB_HOST=localhost` in `.env`; `make ps` shows postgres healthy |
