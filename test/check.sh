#!/usr/bin/env bash
# test/check.sh — end-to-end verification of the LC Invoice Checker pipeline.
#
# Runs health checks, fires a POST /lc-check, then queries the pipeline_steps
# table and the v_session_overview / v_invoice_extracts / v_rule_checks views
# to confirm every stage persisted correctly.
#
# Usage:
#   test/check.sh                         # defaults: sample MT700 + invoice-3-color-claude.pdf
#   test/check.sh <lc.txt> <invoice.pdf>  # custom inputs
#   make curl-demo                        # wraps this script

set -u
set -o pipefail

# ── Config ───────────────────────────────────────────────────────────────────
API="${API:-http://localhost:8080}"
DOCLING="${EXTRACTOR_DOCLING_URL:-http://localhost:8081}"
MINERU="${EXTRACTOR_MINERU_URL:-http://localhost:8082}"
PG_CONTAINER="${PG_CONTAINER:-lc-checker-postgres}"
PG_USER="${DB_USER:-lcuser}"
PG_DB="${PG_DB:-lc_checker}"

LC_FILE="${1:-docs/refer-doc/sample_lc_mt700.txt}"
INVOICE_FILE="${2:-docs/refer-doc/invoice-3-color-claude.pdf}"

# ── Pretty output ────────────────────────────────────────────────────────────
GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; DIM=$'\033[2m'; RESET=$'\033[0m'
ok()   { printf "%s✓%s %s\n"  "${GREEN}" "${RESET}" "$*"; }
warn() { printf "%s!%s %s\n"  "${YELLOW}" "${RESET}" "$*"; }
fail() { printf "%s✗%s %s\n"  "${RED}" "${RESET}"   "$*"; exit_code=1; }
hr()   { printf -- "────────────────────────────────────────\n"; }

exit_code=0
cd "$(dirname "$0")/.." || exit 1

# ── 0. Inputs ────────────────────────────────────────────────────────────────
printf "%s== preflight ==%s\n" "${BOLD}" "${RESET}"
[ -f "$LC_FILE" ]      && ok "LC file:      $LC_FILE"      || fail "LC file missing: $LC_FILE"
[ -f "$INVOICE_FILE" ] && ok "Invoice file: $INVOICE_FILE" || fail "Invoice file missing: $INVOICE_FILE"

# ── 1. Health checks ─────────────────────────────────────────────────────────
health() {
    local name=$1 url=$2 match=$3 required=$4
    local body
    body=$(curl -fsS --max-time 3 "$url" 2>/dev/null || true)
    if [ -n "$body" ] && grep -q "$match" <<<"$body"; then
        ok "$name  $url  — ${body:0:60}"
        return 0
    fi
    if [ "$required" = "required" ]; then
        fail "$name  $url  — not reachable"
    else
        warn "$name  $url  — not reachable (optional)"
    fi
    return 1
}
health "api    " "$API/actuator/health" '"UP"'        required
health "docling" "$DOCLING/health"      '"status":"ok"' optional && HAVE_DOCLING=1 || HAVE_DOCLING=0
health "mineru " "$MINERU/health"       '"status":"ok"' optional && HAVE_MINERU=1  || HAVE_MINERU=0

# Postgres
if docker exec "$PG_CONTAINER" pg_isready -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1; then
    ok "postgres  $PG_CONTAINER  — accepting connections"
else
    fail "postgres  $PG_CONTAINER  — not reachable (is \`make up\` done?)"
fi

[ "$exit_code" = "0" ] || { hr; exit "$exit_code"; }

# ── 2. POST /lc-check ────────────────────────────────────────────────────────
hr
printf "%s== running pipeline ==%s\n" "${BOLD}" "${RESET}"
printf "POST %s/api/v1/lc-check\n" "$API"
printf "     lc=%s\n     invoice=%s\n" "$LC_FILE" "$INVOICE_FILE"

RESP_FILE=$(mktemp -t lc-check-resp.XXXXXX.json)
trap 'rm -f "$RESP_FILE"' EXIT

START_TS=$(date +%s)
HTTP_CODE=$(curl -sS -o "$RESP_FILE" -w "%{http_code}" \
    -X POST "$API/api/v1/lc-check" \
    -F "lc=@${LC_FILE};type=text/plain" \
    -F "invoice=@${INVOICE_FILE};type=application/pdf" || echo "000")
END_TS=$(date +%s)
DURATION=$((END_TS - START_TS))

if [ "$HTTP_CODE" != "200" ]; then
    fail "HTTP $HTTP_CODE  (expected 200) — body:"
    head -c 600 "$RESP_FILE"; echo
    exit 1
fi
ok "HTTP 200 in ${DURATION}s ($(wc -c <"$RESP_FILE") bytes)"

SID=$(python3 -c "import json,sys; print(json.load(sys.stdin)['session_id'])" <"$RESP_FILE")
COMPLIANT=$(python3 -c "import json,sys; print(json.load(sys.stdin)['compliant'])" <"$RESP_FILE")
N_DISC=$(python3 -c "import json,sys; print(len(json.load(sys.stdin)['discrepancies']))" <"$RESP_FILE")
printf "     session_id=%s  compliant=%s  discrepancies=%s\n" "$SID" "$COMPLIANT" "$N_DISC"

# ── 3. DB verification ───────────────────────────────────────────────────────
psql_exec() { docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAF $'\t' -c "$1"; }

hr
printf "%s== pipeline timeline ==%s\n" "${BOLD}" "${RESET}"
printf "%-4s %-16s %-10s %-12s %-12s\n" "#" "stage" "step_key" "status" "duration_ms"
printf "%-4s %-16s %-10s %-12s %-12s\n" "──" "─────────────" "────────" "──────────" "──────────"
psql_exec "
SELECT ROW_NUMBER() OVER (ORDER BY started_at), stage, step_key, status, duration_ms
FROM   v_pipeline_steps
WHERE  session_id = '${SID}'
ORDER BY started_at;
" | awk -F'\t' '{ printf "%-4s %-16s %-10s %-12s %-12s\n", $1, $2, $3, $4, $5 }'

hr
printf "%s== invoice extractor comparison ==%s\n" "${BOLD}" "${RESET}"
printf "%-10s %-8s %-11s %-10s %-5s %-8s %-12s\n" "source" "status" "is_selected" "confidence" "pages" "img" "duration_ms"
psql_exec "
SELECT source, status, is_selected, COALESCE(confidence::text,''), COALESCE(pages::text,''), COALESCE(is_image_based::text,''), duration_ms
FROM   v_invoice_extracts
WHERE  session_id = '${SID}'
ORDER BY source;
" | awk -F'\t' '{ printf "%-10s %-8s %-11s %-10s %-5s %-8s %-12s\n", $1, $2, $3, $4, $5, $6, $7 }'

hr
printf "%s== rule checks by tier ==%s\n" "${BOLD}" "${RESET}"
printf "%-5s %-10s %-10s %-18s %-10s %-12s\n" "tier" "rule_id" "check_type" "status" "severity" "duration_ms"
psql_exec "
SELECT tier, rule_id, check_type, status, COALESCE(severity,''), duration_ms
FROM   v_rule_checks
WHERE  session_id = '${SID}';
" | awk -F'\t' '{ printf "%-5s %-10s %-10s %-18s %-10s %-12s\n", $1, $2, $3, $4, $5, $6 }'

hr
printf "%s== session overview ==%s\n" "${BOLD}" "${RESET}"
psql_exec "
SELECT
  'status='            || status
  || '  compliant='    || COALESCE(compliant::text,'null')
  || '  attempts='     || COALESCE(extract_attempts::text,'0')
  || '  selected='     || COALESCE(selected_source,'-')
  || '  tier1='        || COALESCE(tier1_count::text,'0')
  || '  tier2='        || COALESCE(tier2_count::text,'0')
  || '  tier3='        || COALESCE(tier3_count::text,'0')
  || '  discrepancies='|| COALESCE(discrepancies::text,'0')
FROM v_session_overview WHERE session_id='${SID}';
"

# ── 4. Assertions ────────────────────────────────────────────────────────────
hr
printf "%s== assertions ==%s\n" "${BOLD}" "${RESET}"

EXPECTED_ATTEMPTS=1
[ "$HAVE_DOCLING" = "1" ] && EXPECTED_ATTEMPTS=$((EXPECTED_ATTEMPTS + 1))
[ "$HAVE_MINERU"  = "1" ] && EXPECTED_ATTEMPTS=$((EXPECTED_ATTEMPTS + 1))

ATTEMPTS=$(psql_exec "SELECT extract_attempts FROM v_session_overview WHERE session_id='${SID}';" | tr -d '[:space:]')
SELECTED=$(psql_exec "SELECT selected_source   FROM v_session_overview WHERE session_id='${SID}';" | tr -d '[:space:]')
FINAL_STATUS=$(psql_exec "SELECT status        FROM v_session_overview WHERE session_id='${SID}';" | tr -d '[:space:]')
N_RULES=$(psql_exec "SELECT rules_run          FROM v_session_overview WHERE session_id='${SID}';" | tr -d '[:space:]')

[ "$FINAL_STATUS" = "COMPLETED" ] && ok "session status = COMPLETED" \
                                  || fail "session status = $FINAL_STATUS (expected COMPLETED)"

[ "$ATTEMPTS" = "$EXPECTED_ATTEMPTS" ] && ok "extractor attempts = $ATTEMPTS (all enabled sources fired)" \
                                       || fail "extractor attempts = $ATTEMPTS (expected $EXPECTED_ATTEMPTS)"

[ -n "$SELECTED" ] && [ "$SELECTED" != "-" ] && ok "selected_source = $SELECTED" \
                                              || fail "no source marked is_selected"

[ "$N_RULES" -gt "0" ] 2>/dev/null && ok "rules_run = $N_RULES" \
                                    || fail "rules_run = $N_RULES (expected > 0)"

hr
[ "$exit_code" = "0" ] && printf "%s✓ verification passed%s  (session %s)\n" "${GREEN}${BOLD}" "${RESET}" "$SID" \
                       || printf "%s✗ verification failed%s\n" "${RED}${BOLD}" "${RESET}"
exit "$exit_code"
