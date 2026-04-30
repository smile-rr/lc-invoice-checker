package com.lc.checker.pipeline.stages;

import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.rule.RuleCatalog;
import com.lc.checker.domain.rule.enums.CheckStatus;
import com.lc.checker.domain.rule.enums.CheckType;
import com.lc.checker.infra.observability.LangfuseTags;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.pipeline.Stage;
import com.lc.checker.pipeline.StageContext;
import com.lc.checker.stage.check.CheckExecutor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Stage 3 — runs every enabled {@link CheckType#AGENT} rule. One LLM call per
 * rule with the full LC + full invoice + rule definition inline. The agent
 * decides applicability AND verdict in a single round-trip.
 */
@Component
public class AgentChecksStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(AgentChecksStage.class);

    private final RuleCatalog catalog;
    private final CheckExecutor executor;
    private final Tracer tracer;
    private final CheckSessionStore store;

    public AgentChecksStage(RuleCatalog catalog, CheckExecutor executor,
                            Tracer tracer, CheckSessionStore store) {
        this.catalog = catalog;
        this.executor = executor;
        this.tracer = tracer;
        this.store = store;
    }

    @Override
    public String name() {
        return "agent";
    }

    @Override
    public void execute(StageContext ctx) {
        long t0 = System.currentTimeMillis();
        Instant startedAt = Instant.now();
        List<Rule> rules = catalog.enabled().stream()
                .filter(r -> r.checkType() == CheckType.AGENT)
                .toList();

        // Sub-stage umbrella span for the agentic rule run. Each rule's
        // LLM call appears as a Generation child of this span.
        Span subStageSpan = LangfuseTags.applySession(tracer.nextSpan())
                .name("agentic")
                .tag("langfuse.observation.type", "span")
                .tag("stage", name())
                .tag("rule.count", String.valueOf(rules.size()))
                .start();
        ctx.publisher.status(name(), "started",
                "Running " + rules.size() + " agent check" + (rules.size() == 1 ? "" : "s"));

        try (Tracer.SpanInScope ws = tracer.withSpan(subStageSpan)) {
            MDC.put(MdcKeys.STAGE, name());
            CheckExecutor.Result r = executor.run(name(), ctx.sessionId, rules,
                    ctx.lc, ctx.invoice, ctx.publisher);
            ctx.checkResults.addAll(r.results());
            ctx.checkTraces.addAll(r.traces());
            long dur = System.currentTimeMillis() - t0;
            int passed = count(r.results(), CheckStatus.PASS);
            int failed = count(r.results(), CheckStatus.FAIL);
            int doubts = count(r.results(), CheckStatus.DOUBTS);
            int notReq = count(r.results(), CheckStatus.NOT_REQUIRED);
            ctx.publisher.status(name(), "completed",
                    summary(r.results()),
                    Map.of(
                            "ran", r.results().size(),
                            "passed", passed,
                            "failed", failed,
                            "doubts", doubts,
                            "notRequired", notReq,
                            "durationMs", dur));
            subStageSpan.tag("rules.passed", String.valueOf(passed));
            subStageSpan.tag("rules.failed", String.valueOf(failed));
            subStageSpan.tag("rules.doubts", String.valueOf(doubts));
            subStageSpan.tag("duration.ms", String.valueOf(dur));
            log.debug("Stage 3 (agent) complete: {} rule(s) in {}ms",
                    r.results().size(), dur);

            // Phase summary row (step_key = "phase:agent"). loadRuleChecks
            // filters these out so the trace view sees only per-rule rows.
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ran", r.results().size());
                payload.put("passed", passed);
                payload.put("failed", failed);
                payload.put("doubts", doubts);
                payload.put("notRequired", notReq);
                store.putStep(ctx.sessionId, "lc_check", "phase:" + name(),
                        "SUCCESS", startedAt, Instant.now(), payload, null);
            } catch (Exception persistFail) {
                log.warn("Persisting phase row for {} failed (non-fatal): {}",
                        name(), persistFail.getMessage());
            }
        } finally {
            MDC.remove(MdcKeys.STAGE);
            subStageSpan.end();
        }
    }

    private static String summary(List<com.lc.checker.domain.result.CheckResult> r) {
        return r.size() + " ran · "
                + count(r, CheckStatus.PASS) + " passed · "
                + count(r, CheckStatus.FAIL) + " failed · "
                + count(r, CheckStatus.NOT_REQUIRED) + " n/a";
    }

    private static int count(List<com.lc.checker.domain.result.CheckResult> r, CheckStatus s) {
        int n = 0;
        for (var x : r) if (x.status() == s) n++;
        return n;
    }
}
