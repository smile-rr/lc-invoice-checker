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
 * Stage 2 — runs every enabled {@link CheckType#PROGRAMMATIC} rule. SpEL only,
 * no LLM, no activation gate. Each rule's outcome is appended to
 * {@link StageContext#checkResults} and emitted as a {@code rule} envelope event.
 */
@Component
public class ProgrammaticChecksStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(ProgrammaticChecksStage.class);

    private final RuleCatalog catalog;
    private final CheckExecutor executor;
    private final Tracer tracer;
    private final CheckSessionStore store;

    public ProgrammaticChecksStage(RuleCatalog catalog, CheckExecutor executor,
                                   Tracer tracer, CheckSessionStore store) {
        this.catalog = catalog;
        this.executor = executor;
        this.tracer = tracer;
        this.store = store;
    }

    @Override
    public String name() {
        return "programmatic";
    }

    @Override
    public void execute(StageContext ctx) {
        long t0 = System.currentTimeMillis();
        Instant startedAt = Instant.now();
        List<Rule> rules = catalog.enabled().stream()
                .filter(r -> r.checkType() == CheckType.PROGRAMMATIC)
                .toList();

        // Sub-stage umbrella span for the deterministic rule run. Every
        // rule.<id> span lands as a child of this.
        Span subStageSpan = LangfuseTags.applySession(tracer.nextSpan())
                .name("programmatic")
                .tag("langfuse.observation.type", "span")
                .tag("stage", name())
                .tag("rule.count", String.valueOf(rules.size()))
                .start();
        ctx.publisher.status(name(), "started",
                "Running " + rules.size() + " deterministic check" + (rules.size() == 1 ? "" : "s"));

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
            log.debug("Stage 2 (programmatic) complete: {} rule(s) in {}ms",
                    r.results().size(), dur);

            // Phase summary row (step_key = "phase:programmatic"). loadRuleChecks
            // filters these out so the trace view sees only per-rule rows; the
            // summary row exists for stage-level queries / debugging.
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
                + count(r, CheckStatus.FAIL) + " failed";
    }

    private static int count(List<com.lc.checker.domain.result.CheckResult> r, CheckStatus s) {
        int n = 0;
        for (var x : r) if (x.status() == s) n++;
        return n;
    }
}
