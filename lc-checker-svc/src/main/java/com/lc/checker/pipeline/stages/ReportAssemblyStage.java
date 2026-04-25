package com.lc.checker.pipeline.stages;

import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.observability.PipelineMetrics;
import com.lc.checker.infra.stream.CheckEvent;
import com.lc.checker.pipeline.PipelineErrorHandler;
import com.lc.checker.pipeline.Stage;
import com.lc.checker.pipeline.StageContext;
import com.lc.checker.stage.assemble.ReportAssembler;
import java.util.Map;
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
        ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.STAGE_STARTED, name(), null));
        try {
            MDC.put(MdcKeys.STAGE, name());
            DiscrepancyReport report = assembler.assemble(ctx.sessionId, ctx.lc, ctx.invoice, ctx.checkResults);
            ctx.report = report;
            long assembleDur = System.currentTimeMillis() - t5;
            metrics.recordStage(PipelineMetrics.TIMER_ASSEMBLE, assembleDur, "success");
            ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.STAGE_COMPLETED, name(),
                    Map.of("durationMs", assembleDur)));
        } catch (RuntimeException e) {
            metrics.recordStage(PipelineMetrics.TIMER_ASSEMBLE,
                    System.currentTimeMillis() - t5, "failed");
            ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.ERROR, name(),
                    Map.of("message", String.valueOf(e.getMessage()))));
            errorHandler.onStageFailure(ctx.sessionId, name(), e);
            throw e;
        } finally {
            MDC.remove(MdcKeys.STAGE);
        }
    }
}
