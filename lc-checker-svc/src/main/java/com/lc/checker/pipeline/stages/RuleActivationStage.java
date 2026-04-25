package com.lc.checker.pipeline.stages;

import com.lc.checker.domain.result.RuleActivationTrace;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.observability.PipelineMetrics;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.stream.CheckEvent;
import com.lc.checker.pipeline.PipelineErrorHandler;
import com.lc.checker.pipeline.Stage;
import com.lc.checker.pipeline.StageContext;
import com.lc.checker.stage.activate.RuleActivator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Stage 2 — rule activation. Walks the catalog and decides which rules apply to
 * THIS LC. Pure code (no LLM); driven by SpEL {@code activation_expr}.
 */
@Component
public class RuleActivationStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(RuleActivationStage.class);

    private final RuleActivator activator;
    private final CheckSessionStore store;
    private final PipelineMetrics metrics;
    private final PipelineErrorHandler errorHandler;

    public RuleActivationStage(RuleActivator activator, CheckSessionStore store,
                               PipelineMetrics metrics, PipelineErrorHandler errorHandler) {
        this.activator = activator;
        this.store = store;
        this.metrics = metrics;
        this.errorHandler = errorHandler;
    }

    @Override
    public String name() {
        return "rule_activation";
    }

    @Override
    public void execute(StageContext ctx) {
        long t0 = System.currentTimeMillis();
        ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.STAGE_STARTED, name(), null));
        List<Rule> activeRules;
        RuleActivationTrace activationTrace;
        try {
            MDC.put(MdcKeys.STAGE, name());
            RuleActivator.Result activation = activator.activate(ctx.lc);
            activeRules = activation.activeRules();
            long dur = System.currentTimeMillis() - t0;
            activationTrace = new RuleActivationTrace(activation.trace().activations(), dur);
            metrics.recordStage(PipelineMetrics.TIMER_ACTIVATE, dur, "success");
            ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.STAGE_COMPLETED, name(),
                    Map.of("durationMs", dur,
                            "output", Map.of("activatedRuleIds",
                                    activeRules.stream().map(Rule::id).toList()))));
        } catch (RuntimeException e) {
            metrics.recordStage(PipelineMetrics.TIMER_ACTIVATE,
                    System.currentTimeMillis() - t0, "failed");
            ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.ERROR, name(),
                    Map.of("message", String.valueOf(e.getMessage()))));
            errorHandler.onStageFailure(ctx.sessionId, name(), e);
            throw e;
        } finally {
            MDC.remove(MdcKeys.STAGE);
        }
        ctx.activeRules = activeRules;
        ctx.activationTrace = activationTrace;
        store.putStep(ctx.sessionId, name(), "-", "SUCCESS",
                Instant.ofEpochMilli(t0), Instant.now(),
                Map.of("activations", activationTrace.activations()),
                null);
        log.debug("Stage 2 complete: {} rules activated", activeRules.size());
    }
}
