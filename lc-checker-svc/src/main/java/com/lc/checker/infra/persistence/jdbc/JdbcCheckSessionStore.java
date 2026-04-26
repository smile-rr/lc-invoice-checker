package com.lc.checker.infra.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.domain.session.CheckSession;
import com.lc.checker.domain.result.CheckTrace;
import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.result.LlmTrace;
import com.lc.checker.domain.result.StageTrace;
import com.lc.checker.infra.stream.CheckEvent;
import com.lc.checker.domain.rule.enums.CheckStatus;
import com.lc.checker.domain.rule.enums.CheckType;
import com.lc.checker.domain.session.enums.StageStatus;
import com.lc.checker.infra.persistence.CheckSessionStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL store against schema v3 (unified {@code pipeline_steps} table).
 *
 * <p>All writes go through {@link #putStep} — one JDBC UPSERT keyed on
 * {@code (session_id, stage, step_key)}. Read-side {@link #find} reassembles a
 * typed {@link CheckSession} by filtering {@code pipeline_steps} rows by stage.
 */
@Repository
public class JdbcCheckSessionStore implements CheckSessionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcCheckSessionStore.class);

    private static final String STAGE_LC_PARSE        = "lc_parse";
    private static final String STAGE_INVOICE_EXTRACT = "invoice_extract";
    /**
     * Stage owning per-rule check rows. Each row's {@code step_key} is the rule id
     * (e.g. {@code "UCP-18b-amount"}); summary phase rows use {@code "phase:<name>"}.
     */
    private static final String STAGE_LC_CHECK        = "lc_check";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcCheckSessionStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mapper = objectMapper;
    }

    // ── Session umbrella ────────────────────────────────────────────────────

    @Override
    public void createSession(String sessionId, String lcReference, String beneficiary, String applicant) {
        // The controller may have already inserted a stub row via
        // recordSessionFiles() before the pipeline started — so on conflict
        // we UPDATE the parsed-LC fields without touching the file metadata.
        jdbc.update("""
                INSERT INTO check_sessions (id, lc_reference, beneficiary, applicant, status, created_at)
                VALUES (?::uuid, ?, ?, ?, 'RUNNING', NOW())
                ON CONFLICT (id) DO UPDATE SET
                    lc_reference = COALESCE(EXCLUDED.lc_reference, check_sessions.lc_reference),
                    beneficiary  = COALESCE(EXCLUDED.beneficiary,  check_sessions.beneficiary),
                    applicant    = COALESCE(EXCLUDED.applicant,    check_sessions.applicant)
                """, sessionId, lcReference, beneficiary, applicant);
        log.debug("Created session record: id={}", sessionId);
    }

    @Override
    public void recordSessionFiles(String sessionId,
                                    String lcFilename, String lcSha256,
                                    String invoiceFilename, String invoiceSha256) {
        // INSERT-or-UPDATE so this is safe whether or not the LC-parse stage
        // has run yet. status defaults to 'RUNNING' on a fresh insert; if a
        // row already exists we never overwrite its status.
        jdbc.update("""
                INSERT INTO check_sessions (id, lc_filename, lc_sha256, invoice_filename, invoice_sha256, status, created_at)
                VALUES (?::uuid, ?, ?, ?, ?, 'RUNNING', NOW())
                ON CONFLICT (id) DO UPDATE SET
                    lc_filename      = EXCLUDED.lc_filename,
                    lc_sha256        = EXCLUDED.lc_sha256,
                    invoice_filename = EXCLUDED.invoice_filename,
                    invoice_sha256   = EXCLUDED.invoice_sha256
                """, sessionId, lcFilename, lcSha256, invoiceFilename, invoiceSha256);
        log.debug("recordSessionFiles: session={} lcFile={} invoiceFile={}", sessionId, lcFilename, invoiceFilename);
    }

    @Override
    public Optional<SessionFileRefs> findSessionFiles(String sessionId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT lc_filename, lc_sha256, invoice_filename, invoice_sha256
                    FROM   check_sessions
                    WHERE  id = ?::uuid
                    """,
                    (rs, i) -> new SessionFileRefs(
                            rs.getString("lc_filename"),
                            rs.getString("lc_sha256"),
                            rs.getString("invoice_filename"),
                            rs.getString("invoice_sha256")),
                    sessionId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void finalizeSession(String sessionId, Boolean compliant, String error,
                                DiscrepancyReport finalReport, Instant completedAt) {
        String status = (error != null) ? "FAILED" : "COMPLETED";
        String reportJson = finalReport == null ? null : toJson(finalReport);
        Timestamp completedTs = completedAt == null ? null : Timestamp.from(completedAt);
        jdbc.update("""
                UPDATE check_sessions
                SET status       = ?,
                    compliant    = ?,
                    error        = ?,
                    final_report = CASE WHEN ?::text IS NULL THEN final_report ELSE to_jsonb(?::json) END,
                    completed_at = COALESCE(?, completed_at)
                WHERE id = ?::uuid
                """,
                status, compliant, error,
                reportJson, reportJson,
                completedTs, sessionId);
        log.debug("Finalized session: id={} status={} compliant={}", sessionId, status, compliant);
    }

    // ── Unified step recording ──────────────────────────────────────────────

    @Override
    public void putStep(String sessionId, String stage, String stepKey, String status,
                        Instant startedAt, Instant completedAt, Object result, String error) {
        String resultJson = (result == null) ? null : toJson(result);
        Timestamp startedTs   = (startedAt   == null) ? Timestamp.from(Instant.now())
                                                      : Timestamp.from(startedAt);
        Timestamp completedTs = (completedAt == null) ? null : Timestamp.from(completedAt);
        Long durationMs = (startedAt == null || completedAt == null)
                ? null
                : completedAt.toEpochMilli() - startedAt.toEpochMilli();
        String key = (stepKey == null || stepKey.isBlank()) ? "-" : stepKey;

        jdbc.update("""
                INSERT INTO pipeline_steps
                    (session_id, stage, step_key, status, started_at, completed_at, duration_ms, result, error)
                VALUES
                    (?::uuid, ?, ?, ?, ?, ?, ?,
                     CASE WHEN ?::text IS NULL THEN NULL ELSE to_jsonb(?::json) END,
                     ?)
                ON CONFLICT (session_id, stage, step_key) DO UPDATE SET
                    status       = EXCLUDED.status,
                    started_at   = EXCLUDED.started_at,
                    completed_at = EXCLUDED.completed_at,
                    duration_ms  = EXCLUDED.duration_ms,
                    result       = EXCLUDED.result,
                    error        = EXCLUDED.error
                """,
                sessionId, stage, key, status, startedTs, completedTs, durationMs,
                resultJson, resultJson, error);
        log.debug("putStep: session={} stage={} step={} status={} dur={}ms",
                sessionId, stage, key, status, durationMs);
    }

    @Override
    public void markExtractSelected(String sessionId, String source) {
        jdbc.update("""
                UPDATE pipeline_steps
                SET result = jsonb_set(
                    COALESCE(result, '{}'::jsonb),
                    '{is_selected}',
                    to_jsonb(step_key = ?)
                )
                WHERE session_id = ?::uuid AND stage = ?
                """, source, sessionId, STAGE_INVOICE_EXTRACT);
        log.debug("markExtractSelected: session={} source={}", sessionId, source);
    }

    // ── Legacy put (no-op — L1 cache handles fully-built sessions) ─────────

    @Override
    public void put(CheckSession session) {
        // Intentional no-op at the JDBC layer: all progressive state is already
        // persisted via putStep/finalizeSession. L1 cache handles fully-built
        // CheckSession storage.
    }

    // ── Read / reconstruct ─────────────────────────────────────────────────

    @Override
    public Optional<CheckSession> find(String sessionId) {
        try {
            SessionHeader header = jdbc.queryForObject("""
                    SELECT lc_reference, beneficiary, applicant, status, compliant, error,
                           final_report::text AS final_report, created_at, completed_at
                    FROM check_sessions
                    WHERE id = ?::uuid
                    """,
                    (rs, i) -> new SessionHeader(
                            rs.getString("lc_reference"),
                            rs.getString("beneficiary"),
                            rs.getString("applicant"),
                            rs.getString("status"),
                            (Boolean) rs.getObject("compliant"),
                            rs.getString("error"),
                            rs.getString("final_report"),
                            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()
                    ),
                    sessionId);

            StageTrace lcParsing       = loadLcParse(sessionId);
            StageTrace invoiceExtract  = loadSelectedInvoiceExtract(sessionId);
            List<CheckTrace> checks    = loadRuleChecks(sessionId);
            DiscrepancyReport report   = fromJson(header.finalReport(), DiscrepancyReport.class);

            return Optional.of(new CheckSession(
                    sessionId, null, null,
                    header.createdAt(), header.completedAt(),
                    lcParsing, invoiceExtract,
                    checks, report, header.error()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.error("find() failed for sessionId={}: {}", sessionId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public long size() {
        return -1;  // L1 cache owns the useful number
    }

    @Override
    public List<ExtractAttempt> findInvoiceExtracts(String sessionId) {
        return jdbc.query("""
                SELECT step_key AS source, status, duration_ms,
                       started_at, result::text AS result, error
                FROM   pipeline_steps
                WHERE  session_id = ?::uuid AND stage = ?
                ORDER BY started_at
                """,
                (rs, i) -> {
                    Map<String, Object> resMap = parseObjectMap(rs.getString("result"));
                    InvoiceDocument inv = resMap == null ? null
                            : mapper.convertValue(resMap.get("document"), InvoiceDocument.class);
                    boolean isSelected = false;
                    if (resMap != null && resMap.get("is_selected") instanceof Boolean b) {
                        isSelected = b;
                    }
                    Instant started = rs.getTimestamp("started_at") == null
                            ? null : rs.getTimestamp("started_at").toInstant();
                    return new ExtractAttempt(
                            rs.getString("source"),
                            rs.getString("status"),
                            isSelected,
                            inv,
                            rs.getLong("duration_ms"),
                            started,
                            rs.getString("error"));
                },
                sessionId, STAGE_INVOICE_EXTRACT);
    }

    @Override
    public List<SessionSummary> findRecent(int limit) {
        int cap = limit <= 0 ? 20 : Math.min(limit, 100);
        return jdbc.query("""
                SELECT s.id::text AS id, s.lc_reference, s.beneficiary, s.applicant,
                       s.status, s.compliant, s.created_at, s.completed_at,
                       s.lc_filename, s.invoice_filename,
                       (SELECT COUNT(*) FROM pipeline_steps
                          WHERE session_id = s.id AND stage = 'lc_check'
                          AND step_key NOT LIKE 'phase:%')                       AS rules_run,
                       (SELECT COUNT(*) FROM pipeline_steps
                          WHERE session_id = s.id AND stage = 'lc_check'
                          AND step_key NOT LIKE 'phase:%'
                          AND status = 'FAIL')                                   AS discrepancies
                FROM   check_sessions s
                ORDER BY s.created_at DESC
                LIMIT ?
                """,
                (rs, i) -> new SessionSummary(
                        rs.getString("id"),
                        rs.getString("lc_reference"),
                        rs.getString("beneficiary"),
                        rs.getString("applicant"),
                        rs.getString("status"),
                        (Boolean) rs.getObject("compliant"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                        rs.getInt("rules_run"),
                        rs.getInt("discrepancies"),
                        rs.getString("lc_filename"),
                        rs.getString("invoice_filename")),
                cap);
    }

    // ── Reconstruction helpers ──────────────────────────────────────────────

    private record SessionHeader(String lcReference, String beneficiary, String applicant,
                                 String status, Boolean compliant, String error,
                                 String finalReport, Instant createdAt, Instant completedAt) {}

    private StageTrace loadLcParse(String sessionId) {
        try {
            return jdbc.queryForObject("""
                    SELECT status, duration_ms, started_at, result::text AS result, error
                    FROM   pipeline_steps
                    WHERE  session_id = ?::uuid AND stage = ?
                    ORDER BY started_at DESC
                    LIMIT 1
                    """,
                    (rs, i) -> {
                        Map<String, Object> resMap = parseObjectMap(rs.getString("result"));
                        LcDocument lc = resMap == null ? null
                                : mapper.convertValue(resMap.get("lc_output"), LcDocument.class);
                        StageStatus st = parseStageStatus(rs.getString("status"));
                        Instant started = rs.getTimestamp("started_at") == null
                                ? null : rs.getTimestamp("started_at").toInstant();
                        return new StageTrace("lc_parse", st, started,
                                rs.getLong("duration_ms"), lc, List.of(), rs.getString("error"));
                    },
                    sessionId, STAGE_LC_PARSE);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private StageTrace loadSelectedInvoiceExtract(String sessionId) {
        try {
            return jdbc.queryForObject("""
                    SELECT step_key, status, duration_ms, started_at, result::text AS result, error
                    FROM   pipeline_steps
                    WHERE  session_id = ?::uuid AND stage = ?
                      AND  (result->>'is_selected')::boolean IS TRUE
                    ORDER BY started_at DESC
                    LIMIT 1
                    """,
                    (rs, i) -> {
                        Map<String, Object> resMap = parseObjectMap(rs.getString("result"));
                        InvoiceDocument inv = resMap == null ? null
                                : mapper.convertValue(resMap.get("document"), InvoiceDocument.class);
                        List<LlmTrace> calls = resMap == null ? List.of()
                                : mapper.convertValue(
                                        resMap.getOrDefault("llm_calls", List.of()),
                                        mapper.getTypeFactory().constructCollectionType(List.class, LlmTrace.class));
                        StageStatus st = parseStageStatus(rs.getString("status"));
                        Instant started = rs.getTimestamp("started_at") == null
                                ? null : rs.getTimestamp("started_at").toInstant();
                        return new StageTrace("invoice_extract_" + rs.getString("step_key"),
                                st, started, rs.getLong("duration_ms"), inv, calls,
                                rs.getString("error"));
                    },
                    sessionId, STAGE_INVOICE_EXTRACT);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private List<CheckTrace> loadRuleChecks(String sessionId) {
        // Filter out the phase:* summary rows — only per-rule outcomes (step_key = rule_id) here.
        return jdbc.query("""
                SELECT step_key, status, duration_ms, result::text AS result, error
                FROM   pipeline_steps
                WHERE  session_id = ?::uuid AND stage = ?
                  AND  step_key NOT LIKE 'phase:%'
                ORDER BY started_at, step_key
                """,
                (rs, i) -> {
                    Map<String, Object> resMap = parseObjectMap(rs.getString("result"));
                    if (resMap != null && resMap.get("trace") != null) {
                        CheckTrace trace = mapper.convertValue(resMap.get("trace"), CheckTrace.class);
                        if (trace != null) return trace;
                    }
                    // Fallback — synthesize minimal CheckTrace from the row.
                    CheckType ct = resMap == null ? CheckType.PROGRAMMATIC
                            : parseCheckType(String.valueOf(resMap.get("check_type")));
                    CheckStatus st = parseCheckStatus(rs.getString("status"));
                    return new CheckTrace(rs.getString("step_key"), ct, st,
                            Map.of(), null, null,
                            rs.getLong("duration_ms"), rs.getString("error"));
                },
                sessionId, STAGE_LC_CHECK);
    }

    // ── JSON helpers ────────────────────────────────────────────────────────

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize object to JSON", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("fromJson({}) failed: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObjectMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JavaType mapType = mapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class);
            return mapper.readValue(json, mapType);
        } catch (JsonProcessingException e) {
            log.warn("parseObjectMap failed: {}", e.getMessage());
            return null;
        }
    }

    private static StageStatus parseStageStatus(String s) {
        if (s == null) return StageStatus.SUCCESS;
        try {
            return StageStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return StageStatus.SUCCESS;
        }
    }

    private static CheckType parseCheckType(String s) {
        if (s == null) return CheckType.PROGRAMMATIC;
        try {
            return CheckType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return CheckType.PROGRAMMATIC;
        }
    }

    // ── Unified event log (Step 7 envelope) ────────────────────────────────

    @Override
    public void appendEvent(String sessionId, CheckEvent event) {
        if (event == null) return;
        String eventJson = toJson(event);
        try {
            jdbc.update("""
                    INSERT INTO pipeline_events (session_id, seq, event)
                    VALUES (?::uuid, ?, to_jsonb(?::json))
                    ON CONFLICT (session_id, seq) DO NOTHING
                    """,
                    sessionId, event.seq(), eventJson);
        } catch (Exception e) {
            log.warn("appendEvent failed for session={} seq={}: {}",
                    sessionId, event.seq(), e.getMessage());
        }
    }

    @Override
    public List<CheckEvent> findEvents(String sessionId) {
        try {
            return jdbc.query("""
                    SELECT event::text AS event
                    FROM   pipeline_events
                    WHERE  session_id = ?::uuid
                    ORDER BY seq
                    """,
                    (rs, i) -> {
                        String json = rs.getString("event");
                        return fromJson(json, CheckEvent.class);
                    },
                    sessionId);
        } catch (Exception e) {
            log.warn("findEvents failed for session={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private static CheckStatus parseCheckStatus(String s) {
        if (s == null) return CheckStatus.DOUBTS;
        try {
            return CheckStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return CheckStatus.DOUBTS;
        }
    }
}
