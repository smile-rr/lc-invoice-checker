package com.lc.checker.pipeline;

import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.domain.result.Summary;
import com.lc.checker.domain.session.CheckSession;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.stream.CheckEventPublisher;
import com.lc.checker.infra.validation.InputValidator;
import com.lc.checker.infra.observability.LangfuseTags;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.pipeline.stages.AgentChecksStage;
import com.lc.checker.pipeline.stages.InvoiceExtractStage;
import com.lc.checker.pipeline.stages.LcParseStage;
import com.lc.checker.pipeline.stages.ProgrammaticChecksStage;
import com.lc.checker.pipeline.stages.ReportAssemblyStage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Top-level LC checking pipeline. The {@link Flow} declaration in the
 * constructor is the single source of truth — it mirrors
 * {@code refer-doc/logic-flow.md}.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │                       LC CHECK PIPELINE FLOW                        │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │   Stage 0   ─ Input validation       (no session row yet)           │
 * │   Stage 1a  ─ lc_parse               MT700 SWIFT regex parse        │
 * │   Stage 1b  ─ invoice_extract        PDF → InvoiceDocument          │
 * │   Stage 2   ─ lc_check               Activation → 5 business phases │
 * │                                      → holistic sweep (slice 4)     │
 * │   Stage 3   ─ report_assembly        DiscrepancyReport + finalize   │
 * └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The merged {@code lc_check} stage replaces the former three (rule_activation,
 * rule_check, holistic_sweep). It emits {@code PHASE_*} sub-events around each
 * business phase the banker walks (parties / money / goods / logistics /
 * procedural / holistic) — the UI renders these as a vertical phase stepper inside
 * one Compliance Check card.
 *
 * <p><b>Controlling the flow (debug only)</b><br>
 * Insert {@code .endHere()} between any two {@code .then(...)} lines in the
 * constructor's flow chain to end execution at that point. Comment the
 * {@code .endHere()} line to re-enable downstream stages. There is no env
 * var, no yaml knob — flow control is a coding-level concern only.
 *
 * <p>If the chain ends before {@link ReportAssemblyStage} runs, the runner
 * emits a synthetic "halted" {@link DiscrepancyReport} so the session row,
 * SSE stream, and trace endpoint all reach a clean terminal state, then
 * returns immediately to the HTTP caller.
 */
@Service
public class LcCheckPipeline {

    private static final Logger log = LoggerFactory.getLogger(LcCheckPipeline.class);

    private final InputValidator validator;
    private final CheckSessionStore store;
    private final Tracer tracer;
    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final List<Stage> stages;

    public LcCheckPipeline(InputValidator validator,
                           CheckSessionStore store,
                           Tracer tracer,
                           LcParseStage              lcParse,
                           InvoiceExtractStage       invoiceExtract,
                           ProgrammaticChecksStage   programmaticChecks,
                           AgentChecksStage          agentChecks,
                           ReportAssemblyStage       reportAssembly) {
        this.validator = validator;
        this.store = store;
        this.tracer = tracer;

        this.stages = Flow.start()
                .then(lcParse)             // Stage 1a │ MT700 SWIFT parse  → LcDocument
                .then(invoiceExtract)      // Stage 1b │ PDF extract        → InvoiceDocument
                .then(programmaticChecks)  // Stage 2  │ Deterministic SpEL rules
                .then(agentChecks)         // Stage 3  │ One LLM call per AGENT rule
                .then(reportAssembly)      // Stage 4  │ Assemble DiscrepancyReport
                .build();

        log.info("Pipeline configured: stages={}",
                stages.stream().map(Stage::name).toList());
    }

    /** Names of the stages currently configured to run, in order. */
    public List<String> configuredStageNames() {
        return stages.stream().map(Stage::name).toList();
    }

    public CheckSession run(String sessionId, String lcText, byte[] pdfBytes, String pdfName) {
        return run(sessionId, lcText, pdfBytes, pdfName, CheckEventPublisher.NOOP);
    }

    public CheckSession run(String sessionId, String lcText, byte[] pdfBytes, String pdfName,
                            CheckEventPublisher publisher) {
        // Pin sessionId into MDC for the duration of the run. Every span we
        // create downstream reads this via LangfuseTags.applySession(...) and
        // sets langfuse.trace.id, which forces Langfuse to merge ALL spans
        // for this session into one trace, regardless of OTel tree quirks.
        boolean mdcOwned = MDC.get(MdcKeys.SESSION_ID) == null;
        if (mdcOwned) MDC.put(MdcKeys.SESSION_ID, sessionId);
        try {
            return runWithSpan(sessionId, lcText, pdfBytes, pdfName, publisher);
        } finally {
            if (mdcOwned) MDC.remove(MdcKeys.SESSION_ID);
        }
    }

    private CheckSession runWithSpan(String sessionId, String lcText, byte[] pdfBytes, String pdfName,
                                     CheckEventPublisher publisher) {
        // Parent span for the whole session — every downstream LLM call, every
        // programmatic rule span, and every HTTP call to docling/mineru attaches
        // under this. Notes:
        //   • langfuse.trace.name overrides the default trace title in Langfuse
        //     (otherwise it'd inherit the OTel span name). Setting it to
        //     "LC Check <sessionId>" makes traces scannable by session in the
        //     trace list.
        //   • langfuse.session.id groups multiple traces of the same session
        //     under one Langfuse Session view.
        //   • langfuse.observation.input/output gives the trace overview a
        //     concrete input + verdict at a glance.
        String traceName = "LC Check " + sessionId;
        Span span = LangfuseTags.applySession(tracer.nextSpan())
                .name(traceName)
                // Root orchestrator span — type "span" (no LLM directly here).
                // Span name doubles as trace name (Langfuse uses the root
                // span's name when langfuse.trace.name is unrecognised by the
                // ingestor version, so this is belt-and-braces).
                .tag("langfuse.observation.type", "span")
                .tag("langfuse.trace.name", traceName)
                .tag("langfuse.observation.input", toJson(Map.of(
                        "session_id", sessionId,
                        "invoice_filename", pdfName == null ? "" : pdfName,
                        "invoice_bytes", pdfBytes.length,
                        "lc_text_preview", preview(lcText, 280),
                        "lc_text_length", lcText.length())))
                .tag("session.id", sessionId)
                .tag("invoice.filename", pdfName == null ? "" : pdfName)
                .tag("invoice.bytes", String.valueOf(pdfBytes.length))
                .tag("lc.length", String.valueOf(lcText.length()))
                .start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            return runInScope(sessionId, lcText, pdfBytes, pdfName, publisher, span);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { return String.valueOf(o); }
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private CheckSession runInScope(String sessionId, String lcText, byte[] pdfBytes, String pdfName,
                                    CheckEventPublisher publisher, Span span) {
        Instant started = Instant.now();
        long pipelineStart = System.currentTimeMillis();

        // Pre-pipeline validation now runs in the upload controller before the
        // session is queued, so by the time we get here the input is known
        // good. Re-run it as a defensive belt-and-braces check; cheap relative
        // to the rest of the pipeline.
        validator.validateLcText(lcText);
        validator.validatePdf(pdfBytes);

        StageContext ctx = new StageContext(sessionId, lcText, pdfBytes, pdfName, publisher, started);

        // Insert an "lc-check" umbrella span around the consecutive check
        // stages so the trace tree groups programmatic + agentic together.
        Span lcCheckSpan = null;
        Tracer.SpanInScope lcCheckScope = null;
        for (Stage stage : stages) {
            boolean isCheckStage = stage instanceof ProgrammaticChecksStage
                    || stage instanceof AgentChecksStage;
            if (lcCheckSpan == null && isCheckStage) {
                lcCheckSpan = LangfuseTags.applySession(tracer.nextSpan())
                        .name("lc-check")
                        .tag("langfuse.observation.type", "span")
                        .tag("stage", "lc-check")
                        .start();
                lcCheckScope = tracer.withSpan(lcCheckSpan);
            }
            if (lcCheckSpan != null && !isCheckStage) {
                if (lcCheckScope != null) lcCheckScope.close();
                lcCheckSpan.end();
                lcCheckSpan = null;
                lcCheckScope = null;
            }
            stage.execute(ctx);
        }
        // Close the lc-check umbrella in case agent was the last stage
        // executed (no report-assembly configured downstream).
        if (lcCheckScope != null) lcCheckScope.close();
        if (lcCheckSpan != null) lcCheckSpan.end();

        // If the chain didn't include reportAssembly, the report was never built —
        // synthesize a halted outcome so the session row finalizes cleanly.
        if (ctx.report == null) {
            String lastRan = stages.isEmpty() ? "(none)" : stages.get(stages.size() - 1).name();
            log.info("Pipeline halted after stage '{}' (chain ends before report_assembly)", lastRan);
            span.tag("pipeline.halted_after", lastRan);
            return earlyFinalize(ctx, lastRan);
        }

        Instant completedAt = Instant.now();
        store.finalizeSession(sessionId, ctx.report.compliant(), null, ctx.report, completedAt);

        long total = System.currentTimeMillis() - pipelineStart;
        log.info("Pipeline complete in {}ms: compliant={} discrepancies={}",
                total, ctx.report.compliant(), ctx.report.discrepancies().size());
        publisher.complete(ctx.report);

        span.tag("compliant", String.valueOf(ctx.report.compliant()));
        span.tag("discrepancy.count", String.valueOf(ctx.report.discrepancies().size()));
        span.tag("rules.passed", String.valueOf(ctx.report.summary().passed()));
        span.tag("rules.failed", String.valueOf(ctx.report.summary().failed()));
        span.tag("duration.ms", String.valueOf(total));
        // Pretty session output for the Langfuse trace overview.
        span.tag("langfuse.observation.output", toJson(Map.of(
                "compliant", ctx.report.compliant(),
                "discrepancy_count", ctx.report.discrepancies().size(),
                "rules_passed", ctx.report.summary().passed(),
                "rules_failed", ctx.report.summary().failed(),
                "rules_doubts", ctx.report.summary().doubts(),
                "duration_ms", total)));

        return new CheckSession(
                sessionId, null, null,
                started, completedAt,
                ctx.lcParseTrace, ctx.invoiceExtractTrace,
                List.copyOf(ctx.checkTraces),
                ctx.report, null);
    }

    /**
     * Build a partial {@link CheckSession} when the flow chain ended before
     * {@link ReportAssemblyStage}. Mirrors the regular finalize path so the DB
     * session row reaches a terminal state, SSE clients receive a final
     * {@code REPORT_COMPLETE}, and the trace endpoint returns a populated
     * session for the stages that did run.
     */
    private CheckSession earlyFinalize(StageContext ctx, String haltedAfter) {
        DiscrepancyReport halted = new DiscrepancyReport(
                ctx.sessionId,
                false,                                  // compliant — no decision can be made
                List.of(),                              // discrepancies (FAIL summaries)
                List.of(), List.of(), List.of(), List.of(),  // passed / failed / doubts / notRequired
                new Summary(0, 0, 0, 0, 0));
        ctx.report = halted;
        Instant completedAt = Instant.now();
        store.finalizeSession(ctx.sessionId, null,
                "halted_after:" + haltedAfter, halted, completedAt);
        ctx.publisher.complete(halted);
        return new CheckSession(
                ctx.sessionId, null, null,
                ctx.pipelineStarted, completedAt,
                ctx.lcParseTrace, ctx.invoiceExtractTrace,
                List.copyOf(ctx.checkTraces),
                halted,
                "halted_after:" + haltedAfter);
    }
}
