-- ============================================================================
-- LC Checker v3 schema — unified pipeline_steps table
--
-- Two domain tables:
--   check_sessions   — session umbrella (request metadata + final report)
--   pipeline_steps   — every pipeline step (lc_parse / invoice_extract /
--                      rule_activation / rule_check / holistic_sweep)
--                      as one row. level-1 = stage, level-2 = step_key.
--
-- Why one table: adding a new stage is zero-DDL; every step uniformly carries
-- (status, started_at, completed_at, duration_ms, result JSONB, error). Views
-- project stage-specific scalars from the JSONB when needed.
-- ============================================================================

CREATE TABLE IF NOT EXISTS check_sessions (
    id              UUID        PRIMARY KEY,
    lc_reference    VARCHAR(50),
    beneficiary     VARCHAR(255),
    applicant       VARCHAR(255),
    status          VARCHAR(20) NOT NULL DEFAULT 'RUNNING',  -- RUNNING | COMPLETED | FAILED
    compliant       BOOLEAN,
    error           TEXT,
    final_report    JSONB,                                     -- assembled DiscrepancyReport
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sessions_lc_ref     ON check_sessions(lc_reference);
CREATE INDEX IF NOT EXISTS idx_sessions_status     ON check_sessions(status);
CREATE INDEX IF NOT EXISTS idx_sessions_created_at ON check_sessions(created_at DESC);

-- ---------------------------------------------------------------------------
-- Unified pipeline step table — one row per step, any stage.
--
-- stage values (level 1):
--   lc_parse          — Stage 1a  (step_key: '-')
--   invoice_extract   — Stage 1b  (step_key: vision | docling | mineru)
--   rule_activation   — Stage 2   (step_key: '-')
--   rule_check        — Stage 3   (step_key: rule_id, e.g. 'INV-011')
--   holistic_sweep    — Stage 4   (step_key: 'pass1' | 'pass2')
--
-- status values:
--   SUCCESS | FAILED | SKIPPED | PASS | DISCREPANT |
--   UNABLE_TO_VERIFY | NOT_APPLICABLE | REQUIRES_HUMAN_REVIEW
--
-- result JSONB — stage-specific payload:
--   lc_parse         {lc_output: LcDocument}
--   invoice_extract  {document: InvoiceDocument, llm_calls: [LlmTrace], is_selected: bool,
--                     confidence, pages, is_image_based, raw_markdown, raw_text}
--   rule_activation  {activations: [RuleActivation]}
--   rule_check       {tier, check_type, severity, field, lc_value, presented_value,
--                     ucp_ref, isbp_ref, description, trace: CheckTrace}
--   holistic_sweep   {pass_number, potential_issues: [...], llm_calls: [LlmTrace]}
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

-- ============================================================================
-- Views — project scalars from the unified table.
-- ============================================================================

-- Full timeline: one row per step across all stages, chronological.
CREATE OR REPLACE VIEW v_pipeline_steps AS
SELECT session_id, stage, step_key, status, started_at, completed_at, duration_ms, error
FROM   pipeline_steps
ORDER BY session_id, started_at;

-- Session overview: aggregated per-session progress.
CREATE OR REPLACE VIEW v_session_overview AS
SELECT  s.id                                  AS session_id,
        s.lc_reference, s.beneficiary, s.applicant,
        s.status, s.compliant, s.error, s.created_at, s.completed_at,
        (SELECT status FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_parse')        AS lc_parse_status,
        (SELECT duration_ms FROM pipeline_steps WHERE session_id = s.id AND stage = 'lc_parse')   AS lc_parse_ms,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'invoice_extract')                                            AS extract_attempts,
        (SELECT step_key FROM pipeline_steps WHERE session_id = s.id AND stage = 'invoice_extract' AND (result->>'is_selected')::boolean)      AS selected_source,
        (SELECT status FROM pipeline_steps WHERE session_id = s.id AND stage = 'rule_activation') AS activation_status,
        (SELECT duration_ms FROM pipeline_steps WHERE session_id = s.id AND stage = 'rule_activation')                                         AS activation_ms,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'rule_check')                                                 AS rules_run,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'rule_check' AND (result->>'tier')::int = 1)                  AS tier1_count,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'rule_check' AND (result->>'tier')::int = 2)                  AS tier2_count,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'rule_check' AND (result->>'tier')::int = 3)                  AS tier3_count,
        (SELECT COUNT(*) FROM pipeline_steps WHERE session_id = s.id AND stage = 'rule_check' AND status = 'DISCREPANT')                       AS discrepancies
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
WHERE  ps.stage = 'rule_activation'
ORDER BY ps.session_id, rule_id;

-- Rule-check results projected for easy querying.
CREATE OR REPLACE VIEW v_rule_checks AS
SELECT session_id,
       (result->>'tier')::int AS tier,
       step_key               AS rule_id,
       result->>'rule_name'   AS rule_name,
       result->>'check_type'  AS check_type,
       status,
       result->>'severity'    AS severity,
       result->>'field'       AS field,
       result->>'lc_value'    AS lc_value,
       result->>'presented_value' AS presented_value,
       result->>'ucp_ref'     AS ucp_ref,
       result->>'isbp_ref'    AS isbp_ref,
       result->>'description' AS description,
       duration_ms, started_at, completed_at, error
FROM   pipeline_steps
WHERE  stage = 'rule_check'
ORDER BY session_id, tier NULLS LAST, rule_id;

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
