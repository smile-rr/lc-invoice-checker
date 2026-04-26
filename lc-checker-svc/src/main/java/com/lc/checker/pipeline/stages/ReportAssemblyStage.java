package com.lc.checker.pipeline.stages;

import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.observability.PipelineMetrics;
import com.lc.checker.pipeline.PipelineErrorHandler;
import com.lc.checker.pipeline.Stage;
import com.lc.checker.pipeline.StageContext;
import com.lc.checker.stage.assemble.ReportAssembler;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Stage 5 — assemble the final {@link DiscrepancyReport} from all collected
 * {@code CheckResult}s, populate {@link StageContext#report}, and let the runner
 * finalize the session row.
 */
@Component
public class ReportAssemblyStage implements Stage {

    private final ReportAssembler assembler;
    private final PipelineMetrics metrics;
    private final PipelineErrorHandler errorHandler;

    public ReportAssemblyStage(ReportAssembler assembler, PipelineMetrics metrics,
                               PipelineErrorHandler errorHandler) {
        this.assembler = assembler;
        this.metrics = metrics;
        this.errorHandler = errorHandler;
    }

    @Override
    public String name() {
        return "report_assembly";
    }

    @Override
    public void execute(StageContext ctx) {
        long t5 = System.currentTimeMillis();
        // Report assembly is internal — no STATUS event. The COMPLETE event with
        // the report is emitted by LcCheckPipeline.
        try {
            MDC.put(MdcKeys.STAGE, name());
            DiscrepancyReport report = assembler.assemble(ctx.sessionId, ctx.lc, ctx.invoice, ctx.checkResults);
            ctx.report = report;
            long assembleDur = System.currentTimeMillis() - t5;
            metrics.recordStage(PipelineMetrics.TIMER_ASSEMBLE, assembleDur, "success");
        } catch (RuntimeException e) {
            metrics.recordStage(PipelineMetrics.TIMER_ASSEMBLE,
                    System.currentTimeMillis() - t5, "failed");
            ctx.publisher.error(name(), String.valueOf(e.getMessage()));
            errorHandler.onStageFailure(ctx.sessionId, name(), e);
            throw e;
        } finally {
            MDC.remove(MdcKeys.STAGE);
        }
    }
}
