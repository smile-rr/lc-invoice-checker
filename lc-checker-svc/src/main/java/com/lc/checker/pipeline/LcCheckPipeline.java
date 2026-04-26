package com.lc.checker.pipeline;

import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.domain.result.Summary;
import com.lc.checker.domain.session.CheckSession;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.stream.CheckEventPublisher;
import com.lc.checker.infra.validation.InputValidator;
import com.lc.checker.pipeline.stages.AgentChecksStage;
import com.lc.checker.pipeline.stages.InvoiceExtractStage;
import com.lc.checker.pipeline.stages.LcParseStage;
import com.lc.checker.pipeline.stages.ProgrammaticChecksStage;
import com.lc.checker.pipeline.stages.ReportAssemblyStage;
import java.time.Instant;
import java.util.List;
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
    private final List<Stage> stages;

    public LcCheckPipeline(InputValidator validator,
                           CheckSessionStore store,
                           LcParseStage              lcParse,
                           InvoiceExtractStage       invoiceExtract,
                           ProgrammaticChecksStage   programmaticChecks,
                           AgentChecksStage          agentChecks,
                           ReportAssemblyStage       reportAssembly) {
        this.validator = validator;
        this.store = store;

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
        Instant started = Instant.now();
        long pipelineStart = System.currentTimeMillis();

        // Stage 0 — input validation. No session row yet, so failure surfaces
        // straight to the controller without any DB write.
        validator.validateLcText(lcText);
        validator.validatePdf(pdfBytes);

        StageContext ctx = new StageContext(sessionId, lcText, pdfBytes, pdfName, publisher, started);

        for (Stage stage : stages) {
            stage.execute(ctx);
        }

        // If the chain didn't include reportAssembly, the report was never built —
        // synthesize a halted outcome so the session row finalizes cleanly.
        if (ctx.report == null) {
            String lastRan = stages.isEmpty() ? "(none)" : stages.get(stages.size() - 1).name();
            log.info("Pipeline halted after stage '{}' (chain ends before report_assembly)", lastRan);
            return earlyFinalize(ctx, lastRan);
        }

        Instant completedAt = Instant.now();
        store.finalizeSession(sessionId, ctx.report.compliant(), null, ctx.report, completedAt);

        long total = System.currentTimeMillis() - pipelineStart;
        log.info("Pipeline complete in {}ms: compliant={} discrepancies={}",
                total, ctx.report.compliant(), ctx.report.discrepancies().size());
        publisher.complete(ctx.report);

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
                List.of(),                              // discrepancies
                List.of(),                              // requires_human_review
                List.of(), List.of(), List.of(), List.of(),  // typed lists
                new Summary(0, 0, 0, 0, 0, 0));
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
