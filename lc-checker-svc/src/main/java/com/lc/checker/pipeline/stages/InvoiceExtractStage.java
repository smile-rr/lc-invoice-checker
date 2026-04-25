package com.lc.checker.pipeline.stages;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.result.StageTrace;
import com.lc.checker.domain.session.enums.StageStatus;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.observability.PipelineMetrics;
import com.lc.checker.infra.stream.CheckEvent;
import com.lc.checker.pipeline.PipelineErrorHandler;
import com.lc.checker.pipeline.Stage;
import com.lc.checker.pipeline.StageContext;
import com.lc.checker.stage.extract.InvoiceExtractionOrchestrator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    public InvoiceExtractStage(InvoiceExtractionOrchestrator extractionOrchestrator,
                               PipelineMetrics metrics,
                               PipelineErrorHandler errorHandler) {
        this.extractionOrchestrator = extractionOrchestrator;
        this.metrics = metrics;
        this.errorHandler = errorHandler;
    }

    @Override
    public String name() {
        return "invoice_extract";
    }

    @Override
    public void execute(StageContext ctx) {
        long t0 = System.currentTimeMillis();
        ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.STAGE_STARTED, name(), null));
        try {
            MDC.put(MdcKeys.STAGE, name());
            InvoiceDocument inv = extractionOrchestrator.runAndPersist(
                    ctx.sessionId, ctx.pdfBytes, ctx.pdfName, ctx.publisher);
            long dur = System.currentTimeMillis() - t0;
            ctx.invoice = inv;
            ctx.invoiceExtractTrace = new StageTrace(name(), StageStatus.SUCCESS,
                    Instant.now(), dur, inv, List.of(), null);
            metrics.recordStage(PipelineMetrics.TIMER_EXTRACT, dur, "success");
            ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.STAGE_COMPLETED, name(),
                    Map.of("durationMs", dur, "output", inv)));
            log.debug("Stage 1b complete: invoice_extract selected={}",
                    extractionOrchestrator.lastUsedSource());
        } catch (RuntimeException e) {
            long dur = System.currentTimeMillis() - t0;
            metrics.recordStage(PipelineMetrics.TIMER_EXTRACT, dur, "failed");
            ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.ERROR, name(),
                    Map.of("message", String.valueOf(e.getMessage()))));
            errorHandler.onStageFailure(ctx.sessionId, name(), e);
            throw e;
        } finally {
            MDC.remove(MdcKeys.STAGE);
        }
    }
}
