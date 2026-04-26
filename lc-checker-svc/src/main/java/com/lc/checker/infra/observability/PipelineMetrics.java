package com.lc.checker.infra.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around Micrometer counters / timers for the pipeline stages. Tags are
 * conservative in V1 — we keep the Prometheus cardinality manageable. Call sites ask
 * this class rather than build tags inline so metric names stay consistent.
 */
@Component
public class PipelineMetrics {

    public static final String TIMER_PIPELINE = "lc_checker.pipeline.duration";
    public static final String TIMER_PARSE = "lc_checker.parse.duration";
    public static final String TIMER_EXTRACT = "lc_checker.extract.duration";
    public static final String TIMER_ACTIVATE = "lc_checker.activate.duration";
    /** Stage-level timer for the merged {@code lc_check} stage (one tick per pipeline run). */
    public static final String TIMER_LC_CHECK = "lc_checker.lc_check.duration";
    /** Per-rule timer — tagged with rule_id/type. Distinct meter name from stage-level
     *  to avoid Prometheus tag-key conflict with {@link #TIMER_LC_CHECK}. */
    public static final String TIMER_CHECK = "lc_checker.check.duration";
    public static final String TIMER_ASSEMBLE = "lc_checker.assemble.duration";
    public static final String COUNTER_LLM_CALLS = "lc_checker.llm.calls";

    private final MeterRegistry registry;

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordStage(String name, long durationMs, String status) {
        Timer.builder(name)
                .tag("status", status)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordCheck(long durationMs, String ruleId, String checkType, String status) {
        Timer.builder(TIMER_CHECK)
                .tag("rule_id", ruleId)
                .tag("type", checkType)
                .tag("status", status)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void incrementLlmCall(String model, String purpose, String outcome) {
        Counter.builder(COUNTER_LLM_CALLS)
                .tag("model", model == null ? "unknown" : model)
                .tag("purpose", purpose)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public Timer pipelineTimer() {
        return Timer.builder(TIMER_PIPELINE)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}
