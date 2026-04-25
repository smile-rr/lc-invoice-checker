package com.lc.checker.pipeline.stages;

import com.lc.checker.domain.result.CheckResult;
import com.lc.checker.domain.result.CheckTrace;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.observability.PipelineMetrics;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.stream.CheckEvent;
import com.lc.checker.pipeline.PipelineErrorHandler;
import com.lc.checker.pipeline.Stage;
import com.lc.checker.pipeline.StageContext;
import com.lc.checker.stage.check.CheckExecutor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Stage 3 — execute checks across Tier 1 (Type A, code only), Tier 2 (Type B,
 * LLM), Tier 3 (Type AB, mixed) for every activated rule.
 */
@Component
public class CheckExecutionStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(CheckExecutionStage.class);

    private final CheckExecutor executor;
    private final CheckSessionStore store;
    private final PipelineMetrics metrics;
    private final PipelineErrorHandler errorHandler;

    public CheckExecutionStage(CheckExecutor executor, CheckSessionStore store,
                               PipelineMetrics metrics, PipelineErrorHandler errorHandler) {
        this.executor = executor;
        this.store = store;
        this.metrics = metrics;
        this.errorHandler = errorHandler;
    }

    @Override
    public String name() {
        return "rule_check";
    }

    @Override
    public void execute(StageContext ctx) {
        long t3 = System.currentTimeMillis();
        ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.STAGE_STARTED, name(), null));
        try {
            MDC.put(MdcKeys.STAGE, name());
            List<CheckExecutor.TierResult> tierResults =
                    executor.runAllTiers(ctx.activeRules, ctx.lc, ctx.invoice, ctx.publisher);
            for (CheckExecutor.TierResult tr : tierResults) {
                log.info("Stage 3 Tier {}: ran {} rule(s)", tr.tier(), tr.results().size());
                for (int i = 0; i < tr.results().size(); i++) {
                    CheckResult r = tr.results().get(i);
                    CheckTrace t = i < tr.traces().size() ? tr.traces().get(i) : null;
                    long ruleDur = t != null ? t.durationMs() : 0L;
                    String error = t != null ? t.error() : null;
                    Instant stepEnd = Instant.now();
                    Instant stepStart = ruleDur > 0 ? stepEnd.minusMillis(ruleDur) : stepEnd;
                    Map<String, Object> ruleResult = new LinkedHashMap<>();
                    ruleResult.put("tier", tr.tier());
                    ruleResult.put("rule_name", r.ruleName());
                    ruleResult.put("check_type", r.checkType() == null ? null : r.checkType().name());
                    ruleResult.put("severity", r.severity() == null ? null : r.severity().name());
                    ruleResult.put("field", r.field());
                    ruleResult.put("lc_value", r.lcValue());
                    ruleResult.put("presented_value", r.presentedValue());
                    ruleResult.put("ucp_ref", r.ucpRef());
                    ruleResult.put("isbp_ref", r.isbpRef());
                    ruleResult.put("description", r.description());
                    if (t != null) ruleResult.put("trace", t);
                    store.putStep(ctx.sessionId, name(), r.ruleId(),
                            r.status() == null ? "UNABLE_TO_VERIFY" : r.status().name(),
                            stepStart, stepEnd, ruleResult, error);
                    metrics.recordCheck(ruleDur, r.ruleId(),
                            r.checkType() == null ? "?" : r.checkType().name(),
                            r.status() == null ? "?" : r.status().name());
                    ctx.checkResults.add(r);
                    if (t != null) ctx.checkTraces.add(t);
                }
            }
            long checkDur = System.currentTimeMillis() - t3;
            metrics.recordStage(PipelineMetrics.TIMER_CHECK, checkDur, "success");
            ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.STAGE_COMPLETED, name(),
                    Map.of("durationMs", checkDur,
                            "output", Map.of("totalCompleted", ctx.checkResults.size()))));
        } catch (RuntimeException e) {
            metrics.recordStage(PipelineMetrics.TIMER_CHECK,
                    System.currentTimeMillis() - t3, "failed");
            ctx.publisher.emit(CheckEvent.ofStage(CheckEvent.Type.ERROR, name(),
                    Map.of("message", String.valueOf(e.getMessage()))));
            errorHandler.onStageFailure(ctx.sessionId, name(), e);
            throw e;
        } finally {
            MDC.remove(MdcKeys.STAGE);
        }
        log.debug("Stage 3 complete: {} rule results persisted", ctx.checkResults.size());
    }
}
