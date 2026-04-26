package com.lc.checker.infra.persistence;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.session.CheckSession;
import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.infra.stream.CheckEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Session + pipeline-step storage. Matches schema v3 in
 * {@code infra/postgres/init/01-schema.sql}:
 *
 * <ul>
 *   <li>{@code check_sessions} — session umbrella (Stage 0 creates, Stage 5 finalizes).</li>
 *   <li>{@code pipeline_steps} — one row per step across all stages.
 *       Uniform shape: {@code (stage, step_key, status, started_at, completed_at,
 *       duration_ms, result JSONB, error)}.</li>
 * </ul>
 *
 * <p>Level-1 {@code stage} values: {@code lc_parse}, {@code invoice_extract},
 * {@code lc_check}, {@code report_assembly}.
 * Level-2 {@code stepKey} distinguishes multiple steps inside one stage:
 * <ul>
 *   <li>{@code invoice_extract}: source name (vision / docling / mineru)</li>
 *   <li>{@code lc_check}: {@code "phase:<name>"} for phase summaries
 *       (activation / parties / money / goods / logistics / procedural / holistic)
 *       OR rule id (e.g. {@code "INV-011"}) for per-rule outcomes</li>
 *   <li>everything else: {@code "-"}</li>
 * </ul>
 */
public interface CheckSessionStore {

    // ── Legacy in-process cache API ─────────────────────────────────────────

    void put(CheckSession session);

    Optional<CheckSession> find(String sessionId);

    long size();

    // ── Session umbrella (Stage 0 + Stage 5) ─────────────────────────────────

    void createSession(String sessionId, String lcReference, String beneficiary, String applicant);

    void finalizeSession(String sessionId, Boolean compliant, String error,
                         DiscrepancyReport finalReport, Instant completedAt);

    // ── Unified step recording ──────────────────────────────────────────────

    /**
     * Upsert a pipeline-step row. Idempotent on {@code (sessionId, stage, stepKey)}.
     *
     * @param stage        level-1 stage — {@code lc_parse | invoice_extract | lc_check | report_assembly}
     * @param stepKey      level-2 step key — source name / rule id / {@code "phase:<name>"} /
     *                     {@code "-"} when not applicable
     * @param status       SUCCESS / FAILED / PASS / DISCREPANT / UNABLE_TO_VERIFY /
     *                     NOT_APPLICABLE / REQUIRES_HUMAN_REVIEW / SKIPPED
     * @param startedAt    wall-clock start of this step
     * @param completedAt  wall-clock end; may equal {@code startedAt} for pre-gate only steps
     * @param result       stage-specific payload (will be serialized to JSONB); may be {@code null}
     * @param error        non-null if the step failed
     */
    void putStep(String sessionId,
                 String stage,
                 String stepKey,
                 String status,
                 Instant startedAt,
                 Instant completedAt,
                 Object result,
                 String error);

    /**
     * Mark exactly one {@code invoice_extract} row (by source) as selected and clear
     * the flag on any other source. No-op if the stage row isn't present yet.
     */
    void markExtractSelected(String sessionId, String source);

    // ── Listing (for UI "Recent Sessions") ──────────────────────────────────

    /**
     * Lightweight summary row for the list page. Mirrors scalars from
     * {@code v_session_overview} so the UI doesn't have to request the full trace
     * for each session just to render a table row.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SessionSummary(
            String sessionId,
            String lcReference,
            String beneficiary,
            String applicant,
            String status,
            Boolean compliant,
            Instant createdAt,
            Instant completedAt,
            int rulesRun,
            int discrepancies) {}

    /**
     * Returns most recent sessions first, capped at {@code limit}. Default
     * implementation returns empty so non-persistent stores (e.g. pure L1 cache)
     * stay safe when listing is not meaningful for them.
     */
    default List<SessionSummary> findRecent(int limit) {
        return List.of();
    }

    // ── Per-extractor attempts (Stage 1b inspection) ────────────────────────

    /**
     * One row per extractor attempt, including failed ones. The orchestrator
     * runs every enabled extractor against the same PDF and persists each
     * outcome in {@code pipeline_steps}; this exposes those rows so the UI
     * can let the operator switch between attempts and compare extractor
     * performance.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ExtractAttempt(
            String source,
            String status,
            boolean isSelected,
            InvoiceDocument document,
            long durationMs,
            Instant startedAt,
            String error) {}

    default List<ExtractAttempt> findInvoiceExtracts(String sessionId) {
        return List.of();
    }

    // ── Unified event log ──────────────────────────────────────────────────

    /**
     * Append one envelope event to the session's append-only event log. Called
     * by {@code CheckEventBus} on every emission so the {@code /trace} API can
     * replay the full sequence, even after the in-memory ring buffer rolls.
     * Default impl is a no-op for tests / pure-cache stores.
     */
    default void appendEvent(String sessionId, CheckEvent event) {
        // no-op in non-persistent stores
    }

    /** Returns all events for a session, in seq order. Empty if unknown session. */
    default List<CheckEvent> findEvents(String sessionId) {
        return List.of();
    }
}
