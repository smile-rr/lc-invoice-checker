#!/usr/bin/env bash
# test/stream.sh — fire one LC compliance run and stream SSE events live.
#
# Iterate fast on a single rule:
#   RULE=ISBP-C3 test/stream.sh                    # show only events for this rule
#   VERDICT_ONLY=1 test/stream.sh                  # only print final report
#   API=http://localhost:8080 test/stream.sh
#   test/stream.sh path/to/lc.txt path/to/invoice.pdf
#
# Requires: curl, jq.
#
# ─── Copy-pasteable terminal recipes (live SSE) ──────────────────────────────
# The streaming pipeline is TWO endpoints. A bare POST returns the session_id
# and exits — the run continues server-side and you tail it on a separate
# stream URL. The two calls are independent: it's perfectly fine to run them
# in two terminal commands, copying the session_id from the first response
# into the second URL by hand. The combined recipes below just automate that.
# To customise, modify only the two -F file paths.
#
# ── Step 1 ── POST kicks off the run.
#
#    curl -sS -X POST http://localhost:8080/api/v1/lc-check/start \
#         -F "lc=@test/sample_mt700.txt;type=text/plain" \
#         -F "invoice=@test/sample_invoice.pdf;type=application/pdf"
#
#    Response (JSON, ~immediate):
#    {
#      "session_id":      "0ff7b0e3-0b7e-4348-8ae1-85af4218e8f8",
#      "invoice_filename":"sample_invoice.pdf",
#      "invoice_bytes":   3264,
#      "lc_length":       1591
#    }
#
# ── Step 2 ── tail the SSE stream for that session_id (paste it in by hand,
#              or use one of the combined recipes below).
#
#    curl -sS -N "http://localhost:8080/api/v1/lc-check/<SESSION_ID>/stream"
#
#    SSE format — one event per blank-line-separated block:
#    event: status
#    data: {"type":"status","stage":"lc_parse","state":"completed","message":"..."}
#
#    event: rule
#    data: {"type":"rule","data":{"rule_id":"ISBP-C3","status":"PASS",
#           "lc_value":"...","presented_value":"...","description":"..."}}
#
#    event: complete
#    data: {"type":"complete","data":{"compliant":true,"discrepancies":[]}}
#
# ── Combined recipes (capture session_id + tail in one block) ────────────────
#
# A) Minimal — works without jq pretty-printing:
#
#    SID=$(curl -sS -X POST http://localhost:8080/api/v1/lc-check/start \
#              -F "lc=@test/sample_mt700.txt;type=text/plain" \
#              -F "invoice=@test/sample_invoice.pdf;type=application/pdf" \
#          | jq -r '.session_id') && echo "session: $SID"
#    curl -sS -N "http://localhost:8080/api/v1/lc-check/$SID/stream"
#
# B) Coloured JSON per event — strips SSE framing, pipes data: lines to jq -C:
#
#    SID=$(curl -sS -X POST http://localhost:8080/api/v1/lc-check/start \
#              -F "lc=@test/sample_mt700.txt;type=text/plain" \
#              -F "invoice=@test/sample_invoice.pdf;type=application/pdf" \
#          | jq -r '.session_id') && \
#    curl -sS -N "http://localhost:8080/api/v1/lc-check/$SID/stream" \
#      | sed -n 's/^data: //p' | jq -C '.'
#
# C) Final report only — skip the stream, hit the trace endpoint after the run:
#
#    curl -sS "http://localhost:8080/api/v1/lc-check/$SID/trace" | jq -C '.'
#
# This script glues (B) together with a richer per-event header parser and a
# RULE=<id> filter; modify the file paths above to point at any LC + invoice
# pair you want to test.
# ─────────────────────────────────────────────────────────────────────────────

set -u
set -o pipefail

API="${API:-http://localhost:8080}"
LC_FILE="${1:-test/sample_mt700.txt}"
INVOICE_FILE="${2:-test/sample_invoice.pdf}"
RULE="${RULE:-}"
VERDICT_ONLY="${VERDICT_ONLY:-0}"

GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; CYAN=$'\033[36m'
BOLD=$'\033[1m'; DIM=$'\033[2m'; RESET=$'\033[0m'

die() { printf "%s✗%s %s\n" "$RED" "$RESET" "$*" >&2; exit 1; }

command -v curl >/dev/null || die "curl is required"
command -v jq   >/dev/null || die "jq is required (brew install jq)"
[ -f "$LC_FILE" ]      || die "LC file not found: $LC_FILE"
[ -f "$INVOICE_FILE" ] || die "Invoice file not found: $INVOICE_FILE"

cd "$(dirname "$0")/.." || exit 1

# ── 1. POST /start — copy-paste this block to start a session manually ──────
printf "%s== POST %s/api/v1/lc-check/start ==%s\n" "$BOLD" "$API" "$RESET"
printf "%s  curl -sS -X POST %s/api/v1/lc-check/start \\\\\n" "$DIM" "$API"
printf "    -F \"lc=@%s;type=text/plain\" \\\\\n" "$LC_FILE"
printf "    -F \"invoice=@%s;type=application/pdf\"%s\n\n" "$INVOICE_FILE" "$RESET"

START_JSON=$(curl -sS -X POST "$API/api/v1/lc-check/start" \
    -F "lc=@${LC_FILE};type=text/plain" \
    -F "invoice=@${INVOICE_FILE};type=application/pdf") || die "POST failed"

SID=$(echo "$START_JSON" | jq -r '.session_id // empty')
[ -n "$SID" ] || { echo "$START_JSON" | jq -C '.'; die "no session_id in response"; }

printf "%s✓%s session: %s\n\n" "$GREEN" "$RESET" "$SID"

# ── 2. GET SSE stream — copy-paste this block to tail events manually ───────
printf "%s== GET %s/api/v1/lc-check/%s/stream ==%s\n" "$BOLD" "$API" "$SID" "$RESET"
printf "%s  curl -N %s/api/v1/lc-check/%s/stream%s\n\n" "$DIM" "$API" "$SID" "$RESET"

# Trap so Ctrl-C exits cleanly.
trap 'printf "\n%s(stream interrupted; session %s left running)%s\n" "$YELLOW" "$SID" "$RESET"; exit 130' INT

# Stream + parse: each SSE block is (optional `event:`) + `data:` + blank line.
# We only care about `data:` lines — the JSON payload IS the type/content.
curl -sS -N "$API/api/v1/lc-check/${SID}/stream" \
| while IFS= read -r line; do
    case "$line" in
      data:*)
        json="${line#data:}"
        json="${json# }"
        [ -z "$json" ] && continue

        # Extract type + key fields without colour to drive filtering.
        type=$(echo "$json" | jq -r '.type // ""' 2>/dev/null)
        rule_id=$(echo "$json" | jq -r '.data.rule_id // ""' 2>/dev/null)
        stage=$(echo "$json" | jq -r '.stage // ""' 2>/dev/null)
        state=$(echo "$json" | jq -r '.state // ""' 2>/dev/null)
        status=$(echo "$json" | jq -r '.data.status // ""' 2>/dev/null)

        # RULE filter — drop rule events for other rule_ids.
        if [ -n "$RULE" ] && [ "$type" = "rule" ] && [ "$rule_id" != "$RULE" ]; then
          continue
        fi

        # VERDICT_ONLY — only the final complete event.
        if [ "$VERDICT_ONLY" = "1" ] && [ "$type" != "complete" ]; then
          continue
        fi

        # Pretty header per event so the eye finds rule completions fast.
        case "$type" in
          rule)
            colour="$CYAN"
            [ "$status" = "PASS" ] && colour="$GREEN"
            [ "$status" = "FAIL" ] && colour="$RED"
            [ "$status" = "DOUBTS" ] && colour="$YELLOW"
            printf "%s[rule]%s %s %s\n" "$colour" "$RESET" "$rule_id" "$status"
            ;;
          status)
            printf "%s[status]%s %s/%s\n" "$DIM" "$RESET" "$stage" "$state"
            ;;
          error)
            printf "%s[error]%s %s\n" "$RED" "$RESET" "$(echo "$json" | jq -r '.message // .')"
            ;;
          complete)
            printf "%s[complete]%s\n" "$BOLD$GREEN" "$RESET"
            ;;
          *)
            printf "[%s]\n" "$type"
            ;;
        esac

        # Full payload, colour-formatted.
        echo "$json" | jq -C '.'

        # Bail on terminal events so the loop exits.
        if [ "$type" = "complete" ] || [ "$type" = "error" ]; then
          printf "\n%s(stream done; trace: %s/api/v1/lc-check/%s/trace)%s\n" \
                 "$DIM" "$API" "$SID" "$RESET"
          break
        fi
        ;;
      event:*|id:*|retry:*|"")
        # SSE control lines — ignore.
        ;;
      *)
        # Anything else (e.g. server errors that leak through).
        printf "%s\n" "$line"
        ;;
    esac
  done
