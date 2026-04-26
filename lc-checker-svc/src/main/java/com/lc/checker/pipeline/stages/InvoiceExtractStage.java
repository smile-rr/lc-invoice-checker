package com.lc.checker.pipeline.stages;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.result.StageTrace;
import com.lc.checker.domain.session.enums.StageStatus;
import com.lc.checker.infra.observability.LangfuseTags;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.observability.PipelineMetrics;
import com.lc.checker.pipeline.PipelineErrorHandler;
import com.lc.checker.pipeline.Stage;
import com.lc.checker.pipeline.StageContext;
import com.lc.checker.stage.extract.InvoiceExtractionOrchestrator;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Stage 1b — invoice PDF extraction. Runs every enabled extractor source via the
 * orchestrator, persists one row per source, picks one as authoritative.
 */
@Component
public class InvoiceExtractStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(InvoiceExtractStage.class);

    private final InvoiceExtractionOrchestrator extractionOrchestrator;
    private final PipelineMetrics metrics;
    private final PipelineErrorHandler errorHandler;
    private final Tracer tracer;

    public InvoiceExtractStage(InvoiceExtractionOrchestrator extractionOrchestrator,
                               PipelineMetrics metrics,
                               PipelineErrorHandler errorHandler,
                               Tracer tracer) {
        this.extractionOrchestrator = extractionOrchestrator;
        this.metrics = metrics;
        this.errorHandler = errorHandler;
        this.tracer = tracer;
    }

    @Override
    public String name() {
        return "invoice_extract";
    }

    @Override
    public void execute(StageContext ctx) {
        // Stage umbrella span — every extractor lane (docling, mineru,
        // cloud-vl, local-vl) emits its own LLM Generation as a child of
        // this span via the orchestrator's CompletableFuture context capture.
        Span stageSpan = LangfuseTags.applySession(tracer.nextSpan())
                .name("invoice-extract")
                .tag("langfuse.observation.type", "span")
                .tag("stage", name())
                .tag("invoice.bytes", String.valueOf(ctx.pdfBytes == null ? 0 : ctx.pdfBytes.length))
                .tag("invoice.filename", ctx.pdfName == null ? "" : ctx.pdfName)
                .start();
        long t0 = System.currentTimeMillis();
        ctx.publisher.status(name(), "started", "Extracting invoice fields");
        try (Tracer.SpanInScope ws = tracer.withSpan(stageSpan)) {
            MDC.put(MdcKeys.STAGE, name());
            InvoiceDocument inv = extractionOrchestrator.runAndPersist(
                    ctx.sessionId, ctx.pdfBytes, ctx.pdfName, ctx.publisher);
            long dur = System.currentTimeMillis() - t0;
            ctx.invoice = inv;
            ctx.invoiceExtractTrace = new StageTrace(name(), StageStatus.SUCCESS,
                    Instant.now(), dur, inv, List.of(), null);
            metrics.recordStage(PipelineMetrics.TIMER_EXTRACT, dur, "success");
            ctx.publisher.status(name(), "completed",
                    "Invoice extracted via " + extractionOrchestrator.lastUsedSource(), inv);
            stageSpan.tag("extractor.selected", String.valueOf(extractionOrchestrator.lastUsedSource()));
            stageSpan.tag("duration.ms", String.valueOf(dur));
            log.debug("Stage 1b complete: invoice_extract selected={}",
                    extractionOrchestrator.lastUsedSource());
        } catch (RuntimeException e) {
            long dur = System.currentTimeMillis() - t0;
            metrics.recordStage(PipelineMetrics.TIMER_EXTRACT, dur, "failed");
            ctx.publisher.error(name(), String.valueOf(e.getMessage()));
            stageSpan.error(e);
            errorHandler.onStageFailure(ctx.sessionId, name(), e);
            throw e;
        } finally {
            MDC.remove(MdcKeys.STAGE);
            stageSpan.end();
        }
    }
}
