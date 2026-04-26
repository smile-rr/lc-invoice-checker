-- ============================================================================
-- LC Checker schema — unified pipeline_steps table
--
-- Two domain tables:
--   check_sessions   — session umbrella (request metadata + final report)
--   pipeline_steps   — every pipeline step (lc_parse / invoice_extract /
--                      lc_check / report_assembly) as one row.
--                      level-1 = stage, level-2 = step_key.
--
-- The merged `lc_check` stage discriminates via step_key:
--   'phase:<name>'   — per-phase summary row (activation, parties, money, …)
--   '<RULE_ID>'      — per-rule outcome row (e.g. 'INV-011')
--
-- Why one table: adding a new stage is zero-DDL; every step uniformly carries
-- (status, started_at, completed_at, duration_ms, result JSONB, error). Views
-- project stage-specific scalars from the JSONB when needed.
-- ============================================================================

CREATE TABLE IF NOT EXISTS check_sessions (
    id                  UUID        PRIMARY KEY,
    lc_reference        VARCHAR(50),
    beneficiary         VARCHAR(255),
    applicant           VARCHAR(255),
    status              VARCHAR(20) NOT NULL DEFAULT 'RUNNING',  -- RUNNING | COMPLETED | FAILED
    compliant           BOOLEAN,
    error               TEXT,
    final_report        JSONB,                                     -- assembled DiscrepancyReport
    -- Original upload metadata. Kept on the session row (not a side table) so
    -- the listing/history UI gets filename + content-address in one query.
    -- Bytes themselves live in MinIO at content-addressed keys derived from
    -- the SHA-256 columns; see infra/storage/MinioFileStore.java.
    lc_filename         TEXT,
    lc_sha256           CHAR(64),
    invoice_filename    TEXT,
    invoice_sha256      CHAR(64),
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sessions_lc_ref         ON check_sessions(lc_reference);
CREATE INDEX IF NOT EXISTS idx_sessions_status         ON check_sessions(status);
CREATE INDEX IF NOT EXISTS idx_sessions_created_at     ON check_sessions(created_at DESC);
-- Composite index supports "find prior runs of this same LC + invoice pair"
-- (used by the optional re-run hint and any future evaluation query).
CREATE INDEX IF NOT EXISTS idx_sessions_files_pair     ON check_sessions(invoice_sha256, lc_sha256, created_at DESC);

-- Idempotent upgrade for existing installations — runs safely on every init.
ALTER TABLE check_sessions ADD COLUMN IF NOT EXISTS lc_filename      TEXT;
ALTER TABLE check_sessions ADD COLUMN IF NOT EXISTS lc_sha256        CHAR(64);
ALTER TABLE check_sessions ADD COLUMN IF NOT EXISTS invoice_filename TEXT;
ALTER TABLE check_sessions ADD COLUMN IF NOT EXISTS invoice_sha256   CHAR(64);

-- ---------------------------------------------------------------------------
-- Unified pipeline step table — one row per step, any stage.
--
-- stage values (level 1):
--   lc_parse          — Stage 1a   (step_key: '-')
--   invoice_extract   — Stage 1b   (step_key: vision | docling | mineru)
--   lc_check          — Stage 2    (step_key: 'phase:<name>' OR rule_id)
--   report_assembly   — Stage 3    (step_key: '-')
--
-- status values:
--   SUCCESS | FAILED | SKIPPED | PASS | DISCREPANT |
--   UNABLE_TO_VERIFY | NOT_APPLICABLE | REQUIRES_HUMAN_REVIEW | HUMAN_REVIEW
--
-- result JSONB — stage-specific payload:
--   lc_parse         {lc_output: LcDocument}
--   invoice_extract  {document: InvoiceDocument, llm_calls: [LlmTrace], is_selected: bool,
--                     confidence, pages, is_image_based, raw_markdown, raw_text}
--   lc_check         (phase:<name>) {phase, ran, passed, discrepant,
--                                    unable_to_verify, not_applicable,
--                                    requires_human_review, rule_ids,
--                                    activations: [RuleActivation] (activation only)}
--   lc_check         (rule_id)      {phase, rule_name, check_type, severity, field,
--                                    lc_value, presented_value, ucp_ref, isbp_ref,
--                                    description, trace: CheckTrace}
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pipeline_steps (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id    UUID         NOT NULL REFERENCES check_sessions(id) ON DELETE CASCADE,
    stage         VARCHAR(40)  NOT NULL,
    step_key      VARCHAR(100) NOT NULL DEFAULT '-',
    status        VARCHAR(30)  NOT NULL,
    started_at    TIMESTAMP    NOT NULL,
    completed_at  TIMESTAMP,
    duration_ms   BIGINT,
    result        JSONB,
    error         TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (session_id, stage, step_key)
);
CREATE INDEX IF NOT EXISTS idx_ps_session       ON pipeline_steps(session_id);
CREATE INDEX IF NOT EXISTS idx_ps_session_stage ON pipeline_steps(session_id, stage);
CREATE INDEX IF NOT EXISTS idx_ps_status        ON pipeline_steps(status);
CREATE INDEX IF NOT EXISTS idx_ps_started_at    ON pipeline_steps(started_at DESC);

-- ---------------------------------------------------------------------------
-- Append-only event log — backs the unified envelope SSE stream and the
-- /trace API's events[] reply. Frontend reducer consumes both live SSE and
-- replayed trace events through the same code path; this table is the source
-- of truth for the latter.
--
-- No FK constraint to check_sessions: the session.started event is emitted
-- before lc_parse populates the session row, and we don't want one race to
-- lose those early events. session_id is just an opaque correlation key here.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pipeline_events (
    session_id UUID    NOT NULL,
    seq        BIGINT  NOT NULL,
    event      JSONB   NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_pe_session ON pipeline_events(session_id, seq);

-- ============================================================================
-- Views — project scalars from the unified table.
-- ============================================================================

-- Full timeline: one row per step across all stages, chronological.
CREATE OR REPLACE VIEW v_pipeline_steps AS
SELECT session_id, stage, step_key, status, started_at, completed_at, duration_ms, error
FROM   pipeline_steps
ORDER BY session_id, started_at;

-- Drop views whose column lists changed shape — CREATE OR REPLACE refuses to
-- rename or reorder columns, so we drop them before recreating below.
DROP VIEW IF EXISTS v_latest_session         CASCADE;
DROP VIEW IF EXISTS v_session_overview       CASCADE;
DROP VIEW IF EXISTS v_latest_rule_checks     CASCADE;
DROP VIEW IF EXISTS v_rule_checks            CASCADE;

-- Session overview: aggregated per-session progress.
CREATE OR REPLACE VIEW v_session_overview AS
SELECT  s.id                                  AS session_id,
        s.lc_reference, s.beneficiary, s.applicant,
        s.status, s.compliant, s.error, s.created_at, s.completed_at,
        s.lc_filename, s.lc_sha256,
        s.invoice_filename, s.invoice_sha256,
        (SELECT status FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_parse')        AS lc_parse_status,
        (SELECT duration_ms FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_parse')   AS lc_parse_ms,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'invoice_extract')                                            AS extract_attempts,
        (SELECT step_key FROM pipeline_steps WHERE session_id = s.id AND stage = 'invoice_extract' AND (result->>'is_selected')::boolean)      AS selected_source,
        (SELECT status FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_check' AND step_key = 'phase:activation')                   AS activation_status,
        (SELECT duration_ms FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_check' AND step_key = 'phase:activation')              AS activation_ms,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_check' AND step_key NOT LIKE 'phase:%')                   AS rules_run,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_check' AND step_key NOT LIKE 'phase:%'
                                              AND result->>'phase' = 'parties')                                                                 AS parties_count,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_check' AND step_key NOT LIKE 'phase:%'
                                              AND result->>'phase' = 'money')                                                                   AS money_count,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_check' AND step_key NOT LIKE 'phase:%'
                                              AND result->>'phase' = 'goods')                                                                   AS goods_count,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_check' AND step_key NOT LIKE 'phase:%'
                                              AND result->>'phase' = 'logistics')                                                               AS logistics_count,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_check' AND step_key NOT LIKE 'phase:%'
                                              AND result->>'phase' = 'procedural')                                                              AS procedural_count,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_check' AND step_key NOT LIKE 'phase:%'
                                              AND status = 'DISCREPANT')                                                                        AS discrepancies,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_check' AND step_key NOT LIKE 'phase:%'
                                              AND status IN ('REQUIRES_HUMAN_REVIEW', 'HUMAN_REVIEW'))                                          AS requires_human_review
FROM    check_sessions s;

-- LC parse — one row per session, every LcDocument field projected as a scalar
-- column. psql with `\x` expanded mode renders this as key: value text (no JSON).
CREATE OR REPLACE VIEW v_lc_parse AS
SELECT session_id,
       status, duration_ms, started_at, completed_at, error,
       result->'lc_output'->>'lc_number'                      AS lc_number,
       (result->'lc_output'->>'issue_date')::date             AS issue_date,
       (result->'lc_output'->>'expiry_date')::date            AS expiry_date,
       result->'lc_output'->>'expiry_place'                   AS expiry_place,
       result->'lc_output'->>'currency'                       AS currency,
       (result->'lc_output'->>'amount')::numeric              AS amount,
       (result->'lc_output'->>'tolerance_plus')::int          AS tolerance_plus,
       (result->'lc_output'->>'tolerance_minus')::int         AS tolerance_minus,
       result->'lc_output'->>'max_amount_flag'                AS max_amount_flag,
       result->'lc_output'->>'partial_shipment'               AS partial_shipment,
       result->'lc_output'->>'transhipment'                   AS transhipment,
       result->'lc_output'->>'place_of_receipt'               AS place_of_receipt,
       result->'lc_output'->>'place_of_delivery'              AS place_of_delivery,
       (result->'lc_output'->>'latest_shipment_date')::date   AS latest_shipment_date,
       result->'lc_output'->>'shipment_period'                AS shipment_period,
       result->'lc_output'->>'port_of_loading'                AS port_of_loading,
       result->'lc_output'->>'port_of_discharge'              AS port_of_discharge,
       (result->'lc_output'->>'presentation_days')::int       AS presentation_days,
       result->'lc_output'->>'applicable_rules'               AS applicable_rules,
       result->'lc_output'->>'applicant_name'                 AS applicant_name,
       result->'lc_output'->>'applicant_address'              AS applicant_address,
       result->'lc_output'->>'beneficiary_name'               AS beneficiary_name,
       result->'lc_output'->>'beneficiary_address'            AS beneficiary_address,
       result->'lc_output'->>'field45_araw'                   AS field_45a_raw,
       result->'lc_output'->>'field46_araw'                   AS field_46a_raw,
       result->'lc_output'->>'field47_araw'                   AS field_47a_raw
FROM   pipeline_steps
WHERE  stage = 'lc_parse';

-- Invoice extract — one row per (session, source). Focus fields projected
-- from the nested InvoiceDocument JSON.
CREATE OR REPLACE VIEW v_invoice_extracts AS
SELECT session_id, step_key AS source,
       status, duration_ms, started_at, completed_at, error,
       (result->>'is_selected')::boolean                         AS is_selected,
       (result->'document'->>'extractor_confidence')::numeric    AS confidence,
       (result->'document'->>'pages')::int                        AS pages,
       (result->'document'->>'image_based')::boolean              AS is_image_based,
       result->'document'->>'invoice_number'                     AS invoice_number,
       (result->'document'->>'invoice_date')::date               AS invoice_date,
       result->'document'->>'seller_name'                        AS seller_name,
       result->'document'->>'buyer_name'                         AS buyer_name,
       result->'document'->>'goods_description'                  AS goods_description,
       (result->'document'->>'quantity')::numeric                AS quantity,
       result->'document'->>'unit'                               AS unit,
       (result->'document'->>'unit_price')::numeric              AS unit_price,
       (result->'document'->>'total_amount')::numeric            AS total_amount,
       result->'document'->>'currency'                           AS currency,
       result->'document'->>'lc_reference'                       AS lc_reference,
       result->'document'->>'trade_terms'                        AS trade_terms
FROM   pipeline_steps
WHERE  stage = 'invoice_extract'
ORDER BY session_id, started_at;

-- Rule activation — one row per rule per session (unnested from the activations array).
-- Source row: stage='lc_check' AND step_key='phase:activation'.
CREATE OR REPLACE VIEW v_rule_activation AS
SELECT ps.session_id,
       act->>'rule_id'               AS rule_id,
       act->>'trigger'               AS trigger,
       (act->>'activated')::boolean  AS activated,
       act->>'reason'                AS reason,
       act->>'evaluated_expression'  AS evaluated_expression,
       ps.duration_ms                AS activation_ms,
       ps.started_at, ps.completed_at
FROM   pipeline_steps ps
CROSS JOIN LATERAL jsonb_array_elements(ps.result->'activations') AS act
WHERE  ps.stage = 'lc_check' AND ps.step_key = 'phase:activation'
ORDER BY ps.session_id, rule_id;

-- Rule-check results projected for easy querying. Excludes phase summary rows.
CREATE OR REPLACE VIEW v_rule_checks AS
SELECT session_id,
       result->>'phase'           AS business_phase,
       step_key                   AS rule_id,
       result->>'rule_name'       AS rule_name,
       result->>'check_type'      AS check_type,
       status,
       result->>'severity'        AS severity,
       result->>'field'           AS field,
       result->>'lc_value'        AS lc_value,
       result->>'presented_value' AS presented_value,
       result->>'ucp_ref'         AS ucp_ref,
       result->>'isbp_ref'        AS isbp_ref,
       result->>'description'     AS description,
       duration_ms, started_at, completed_at, error
FROM   pipeline_steps
WHERE  stage = 'lc_check' AND step_key NOT LIKE 'phase:%'
ORDER BY session_id, started_at, rule_id;

-- Phase summaries — one row per phase per session.
CREATE OR REPLACE VIEW v_lc_check_phases AS
SELECT session_id,
       result->>'phase'                                AS phase,
       (result->>'ran')::int                           AS ran,
       (result->>'passed')::int                        AS passed,
       (result->>'discrepant')::int                    AS discrepant,
       (result->>'unable_to_verify')::int              AS unable_to_verify,
       (result->>'not_applicable')::int                AS not_applicable,
       (result->>'requires_human_review')::int         AS requires_human_review,
       duration_ms, started_at, completed_at
FROM   pipeline_steps
WHERE  stage = 'lc_check' AND step_key LIKE 'phase:%'
ORDER BY session_id, started_at;

-- ---------------------------------------------------------------------------
-- Latest-session convenience views (always 1 session, most recent by created_at).
-- Useful for default `SELECT * FROM …` without needing the session_id.
-- ---------------------------------------------------------------------------

-- v_latest_session — single row with session metadata + aggregated counts
-- (delegates to v_session_overview, just picks the newest row).
CREATE OR REPLACE VIEW v_latest_session AS
SELECT *
FROM   v_session_overview
WHERE  created_at = (SELECT MAX(created_at) FROM check_sessions)
LIMIT  1;

-- ---------------------------------------------------------------------------
-- Latest-session "no session_id required" focus views. Each mirrors the base
-- view (v_lc_parse / v_invoice_extracts / v_rule_activation / v_rule_checks)
-- but filtered to the most-recent session.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_latest_lc_parse AS
SELECT * FROM v_lc_parse
WHERE  session_id = (SELECT id FROM check_sessions ORDER BY created_at DESC LIMIT 1);

CREATE OR REPLACE VIEW v_latest_invoice_extracts AS
SELECT * FROM v_invoice_extracts
WHERE  session_id = (SELECT id FROM check_sessions ORDER BY created_at DESC LIMIT 1);

-- Selected extractor only — scalar focus fields + full markdown + raw text.
CREATE OR REPLACE VIEW v_latest_invoice_selected AS
SELECT v.*,
       ps.result->'document'->>'raw_markdown' AS raw_markdown,
       ps.result->'document'->>'raw_text'     AS raw_text
FROM   v_invoice_extracts v
JOIN   pipeline_steps ps
  ON   ps.session_id = v.session_id
  AND  ps.stage = 'invoice_extract'
  AND  ps.step_key = v.source
WHERE  v.is_selected
  AND  v.session_id = (SELECT id FROM check_sessions ORDER BY created_at DESC LIMIT 1);

CREATE OR REPLACE VIEW v_latest_rule_activation AS
SELECT * FROM v_rule_activation
WHERE  session_id = (SELECT id FROM check_sessions ORDER BY created_at DESC LIMIT 1);

CREATE OR REPLACE VIEW v_latest_rule_checks AS
SELECT * FROM v_rule_checks
WHERE  session_id = (SELECT id FROM check_sessions ORDER BY created_at DESC LIMIT 1);

CREATE OR REPLACE VIEW v_latest_lc_check_phases AS
SELECT * FROM v_lc_check_phases
WHERE  session_id = (SELECT id FROM check_sessions ORDER BY created_at DESC LIMIT 1);

-- ---------------------------------------------------------------------------
-- Raw-JSON dump views — when you need the full payload verbatim. Returns one row
-- with `jsonb_pretty()` so psql renders it nicely. Latest session only.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_latest_lc_json AS
SELECT session_id,
       jsonb_pretty(result->'lc_output') AS lc_output_json
FROM   pipeline_steps
WHERE  stage = 'lc_parse'
  AND  session_id = (SELECT id FROM check_sessions ORDER BY created_at DESC LIMIT 1);

CREATE OR REPLACE VIEW v_latest_invoice_json AS
SELECT session_id, step_key AS source,
       jsonb_pretty(result->'document')  AS document_json,
       jsonb_pretty(result->'llm_calls') AS llm_calls_json
FROM   pipeline_steps
WHERE  stage = 'invoice_extract'
  AND  (result->>'is_selected')::boolean
  AND  session_id = (SELECT id FROM check_sessions ORDER BY created_at DESC LIMIT 1);

-- v_latest_pipeline — every step of the latest session, chronological.
-- Session header is denormalized onto each row so one SELECT answers
-- "what happened in the most recent run, step-by-step".
CREATE OR REPLACE VIEW v_latest_pipeline AS
SELECT  s.id                  AS session_id,
        s.lc_reference,
        s.beneficiary,
        s.applicant,
        s.status              AS session_status,
        s.compliant           AS session_compliant,
        s.created_at          AS session_created_at,
        s.completed_at        AS session_completed_at,
        ps.stage,
        ps.step_key,
        ps.status             AS step_status,
        ps.duration_ms,
        ps.started_at         AS step_started_at,
        ps.completed_at       AS step_completed_at,
        ps.error
FROM    check_sessions s
LEFT JOIN pipeline_steps ps ON ps.session_id = s.id
WHERE   s.created_at = (SELECT MAX(created_at) FROM check_sessions)
ORDER BY ps.started_at NULLS FIRST;
