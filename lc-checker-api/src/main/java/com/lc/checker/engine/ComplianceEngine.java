package com.lc.checker.engine;

import com.lc.checker.extractor.InvoiceExtractor;
import com.lc.checker.model.CheckResult;
import com.lc.checker.model.CheckSession;
import com.lc.checker.model.CheckTrace;
import com.lc.checker.model.DiscrepancyReport;
import com.lc.checker.model.InvoiceDocument;
import com.lc.checker.model.LcDocument;
import com.lc.checker.model.LlmTrace;
import com.lc.checker.model.Rule;
import com.lc.checker.model.StageTrace;
import com.lc.checker.model.enums.StageStatus;
import com.lc.checker.observability.MdcKeys;
import com.lc.checker.observability.PipelineMetrics;
import com.lc.checker.parser.Mt700LlmParser;
import com.lc.checker.parser.Mt700Parser;
import com.lc.checker.store.CheckSessionStore;
import com.lc.checker.validation.InputValidator;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Top-level pipeline orchestrator. Owns the stage sequence described in
 * {@code refer-doc/logic-flow.md} and ties together parser → extractor → activator →
 * executor → assembler → session store.
 *
 * <p>Every stage is wrapped in a {@link StageTrace} so {@code /trace} can show the
 * reviewer exactly what happened at each step, including durations and per-call LLM
 * prompts/responses. Failures at any stage are captured as a failed StageTrace and the
 * session is still persisted — the trace is the primary forensic artefact, not an
 * afterthought.
 */
@Service
public class ComplianceEngine {

    private static final Logger log = LoggerFactory.getLogger(ComplianceEngine.class);

    private final InputValidator validator;
    private final Mt700Parser parser;
    private final Mt700LlmParser llmParser;
    private final InvoiceExtractor extractor;
    private final RuleActivator activator;
    private final CheckExecutor executor;
    private final ReportAssembler assembler;
    private final CheckSessionStore store;
    private final PipelineMetrics metrics;

    public ComplianceEngine(InputValidator validator,
                            Mt700Parser parser,
                            Mt700LlmParser llmParser,
                            InvoiceExtractor extractor,
                            RuleActivator activator,
                            CheckExecutor executor,
                            ReportAssembler assembler,
                            CheckSessionStore store,
                            PipelineMetrics metrics) {
        this.validator = validator;
        this.parser = parser;
        this.llmParser = llmParser;
        this.extractor = extractor;
        this.activator = activator;
        this.executor = executor;
        this.assembler = assembler;
        this.store = store;
        this.metrics = metrics;
    }

    /**
     * Run the full pipeline. The {@code sessionId} comes from the caller (the MdcFilter
     * put one in MDC for us); we pass it through every trace record so the reviewer can
     * correlate logs ↔ session ↔ trace.
     */
    public CheckSession run(String sessionId, String lcText, byte[] pdfBytes, String pdfName) {
        Instant started = Instant.now();
        long pipelineStart = System.currentTimeMillis();

        // Validate first — failures here bypass the pipeline entirely (mapped to 400).
        validator.validateLcText(lcText);
        validator.validatePdf(pdfBytes);

        // Stage 1a — MT700 parse (Part A).
        LcDocument lc;
        long parseStart = System.currentTimeMillis();
        StageTrace parseTrace;
        try {
            MDC.put(MdcKeys.STAGE, "parse");
            lc = parser.parse(lcText);
            // Stage 1b — Part B LLM parsing of :45A:/:46A:/:47A:.
            Mt700LlmParser.Enrichment enriched = llmParser.enrich(lc);
            lc = enriched.lc();
            long dur = System.currentTimeMillis() - parseStart;
            parseTrace = new StageTrace("mt700.parse", StageStatus.SUCCESS,
                    Instant.now(), dur, lc, enriched.traces(), null);
            metrics.recordStage(PipelineMetrics.TIMER_PARSE, dur, "success");
        } catch (RuntimeException e) {
            long dur = System.currentTimeMillis() - parseStart;
            metrics.recordStage(PipelineMetrics.TIMER_PARSE, dur, "failed");
            // Parser / validator exceptions are surfaced as 400s by the controller.
            throw e;
        } finally {
            MDC.remove(MdcKeys.STAGE);
        }

        // Stage 1c — Invoice extract via Docling.
        InvoiceDocument inv;
        long extractStart = System.currentTimeMillis();
        StageTrace extractTrace;
        try {
            MDC.put(MdcKeys.STAGE, "extract");
            inv = extractor.extract(pdfBytes, pdfName);
            long dur = System.currentTimeMillis() - extractStart;
            extractTrace = new StageTrace("invoice.extract", StageStatus.SUCCESS,
                    Instant.now(), dur, inv, List.<LlmTrace>of(), null);
            metrics.recordStage(PipelineMetrics.TIMER_EXTRACT, dur, "success");
        } catch (RuntimeException e) {
            long dur = System.currentTimeMillis() - extractStart;
            metrics.recordStage(PipelineMetrics.TIMER_EXTRACT, dur, "failed");
            throw e;
        } finally {
            MDC.remove(MdcKeys.STAGE);
        }

        // Stage 2 — Activation.
        long actStart = System.currentTimeMillis();
        MDC.put(MdcKeys.STAGE, "activate");
        RuleActivator.Result activation = activator.activate(lc);
        metrics.recordStage(PipelineMetrics.TIMER_ACTIVATE,
                System.currentTimeMillis() - actStart, "success");
        MDC.remove(MdcKeys.STAGE);

        // Stage 3 — Check execution.
        long execStart = System.currentTimeMillis();
        MDC.put(MdcKeys.STAGE, "check");
        List<Rule> activeRules = activation.activeRules();
        CheckExecutor.Result checkResults = executor.run(activeRules, lc, inv);
        for (CheckResult r : checkResults.results()) {
            metrics.recordCheck(0L, r.ruleId(), r.checkType().name(), r.status().name());
        }
        metrics.recordStage(PipelineMetrics.TIMER_CHECK,
                System.currentTimeMillis() - execStart, "success");
        MDC.remove(MdcKeys.STAGE);

        // Stage 5 — Assembly.
        long asmStart = System.currentTimeMillis();
        MDC.put(MdcKeys.STAGE, "assemble");
        DiscrepancyReport report = assembler.assemble(sessionId, lc, inv, checkResults.results());
        metrics.recordStage(PipelineMetrics.TIMER_ASSEMBLE,
                System.currentTimeMillis() - asmStart, "success");
        MDC.remove(MdcKeys.STAGE);

        CheckSession session = new CheckSession(
                sessionId,
                null, null,
                started, Instant.now(),
                parseTrace, extractTrace, activation.trace(),
                List.<CheckTrace>copyOf(checkResults.traces()),
                report,
                null);
        store.put(session);
        long total = System.currentTimeMillis() - pipelineStart;
        log.info("Pipeline complete in {}ms: compliant={} discrepancies={}",
                total, report.compliant(), report.discrepancies().size());
        return session;
    }
}
