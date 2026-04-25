package com.lc.checker.pipeline.stages;

import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.result.StageTrace;
import com.lc.checker.domain.session.enums.StageStatus;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.observability.PipelineMetrics;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.stream.CheckEvent;
import com.lc.checker.pipeline.Stage;
import com.lc.checker.pipeline.StageContext;
import com.lc.checker.stage.parse.Mt700Parser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Stage 1a — SWIFT MT700 parse (regex / Prowide only, no LLM).
 *
 * <p>Special-cases error handling: pre-parse failure has no session row to mark as
 * failed, so this stage rethrows without calling the shared error handler. After a
 * successful parse the session row is created here (we now have the LC reference,
 * beneficiary, applicant scalars to populate it).
 */
@Component
public class LcParseStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(LcParseStage.class);

    private final Mt700Parser parser;
    private final CheckSessionStore store;
    private final PipelineMetrics metrics;

    public LcParseStage(Mt700Parser parser, CheckSessionStore store, PipelineMetrics metrics) {
        this.parser = parser;
        this.store = store;
        this.metrics = metrics;
    }

    @Override
    public String name() {
        return "lc_parse";
    }

    @Override
    public void execute(StageContext ctx) {
        long t0 = System.currentTimeMillis();
        ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.STAGE_STARTED, name(), null));
        try {
            MDC.put(MdcKeys.STAGE, name());
            LcDocument lc = parser.parse(ctx.lcText);
            long dur = System.currentTimeMillis() - t0;
            ctx.lc = lc;
            ctx.lcParseTrace = new StageTrace(name(), StageStatus.SUCCESS,
                    Instant.now(), dur, lc, List.of(), null);
            metrics.recordStage(PipelineMetrics.TIMER_PARSE, dur, "success");
            ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.STAGE_COMPLETED, name(),
                    Map.of("durationMs", dur, "output", lc)));
        } catch (RuntimeException e) {
            long dur = System.currentTimeMillis() - t0;
            metrics.recordStage(PipelineMetrics.TIMER_PARSE, dur, "failed");
            log.error("Stage 1a (lc_parse) failed before session persistence: {}", e.getMessage());
            ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.ERROR, name(),
                    Map.of("message", String.valueOf(e.getMessage()))));
            throw e;
        } finally {
            MDC.remove(MdcKeys.STAGE);
        }

        // Session row creation — moved here from the runner because it depends on
        // the just-parsed LC scalars and only makes sense once parse has succeeded.
        store.createSession(ctx.sessionId, ctx.lc.lcNumber(),
                ctx.lc.beneficiaryName(), ctx.lc.applicantName());
        store.putStep(ctx.sessionId, name(), "-", "SUCCESS",
                Instant.ofEpochMilli(t0), Instant.now(),
                Map.of("lc_output", ctx.lc), null);
        log.debug("Stage 1a complete: lc_parse recorded");
    }
}
