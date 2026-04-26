package com.lc.checker.pipeline.stages;

import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.rule.RuleCatalog;
import com.lc.checker.domain.rule.enums.CheckStatus;
import com.lc.checker.domain.rule.enums.CheckType;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.pipeline.Stage;
import com.lc.checker.pipeline.StageContext;
import com.lc.checker.stage.check.CheckExecutor;
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

    public ProgrammaticChecksStage(RuleCatalog catalog, CheckExecutor executor) {
        this.catalog = catalog;
        this.executor = executor;
    }

    @Override
    public String name() {
        return "programmatic";
    }

    @Override
    public void execute(StageContext ctx) {
        long t0 = System.currentTimeMillis();
        List<Rule> rules = catalog.enabled().stream()
                .filter(r -> r.checkType() == CheckType.PROGRAMMATIC)
                .toList();

        ctx.publisher.status(name(), "started",
                "Running " + rules.size() + " deterministic check" + (rules.size() == 1 ? "" : "s"));

        try {
            MDC.put(MdcKeys.STAGE, name());
            CheckExecutor.Result r = executor.run(name(), rules, ctx.lc, ctx.invoice, ctx.publisher);
            ctx.checkResults.addAll(r.results());
            ctx.checkTraces.addAll(r.traces());
            long dur = System.currentTimeMillis() - t0;
            ctx.publisher.status(name(), "completed",
                    summary(r.results()),
                    Map.of(
                            "ran", r.results().size(),
                            "passed", count(r.results(), CheckStatus.PASS),
                            "discrepant", count(r.results(), CheckStatus.DISCREPANT),
                            "unable", count(r.results(), CheckStatus.UNABLE_TO_VERIFY),
                            "notApplicable", count(r.results(), CheckStatus.NOT_APPLICABLE),
                            "durationMs", dur));
            log.debug("Stage 2 (programmatic) complete: {} rule(s) in {}ms",
                    r.results().size(), dur);
        } finally {
            MDC.remove(MdcKeys.STAGE);
        }
    }

    private static String summary(List<com.lc.checker.domain.result.CheckResult> r) {
        return r.size() + " ran · "
                + count(r, CheckStatus.PASS) + " passed · "
                + count(r, CheckStatus.DISCREPANT) + " discrepant";
    }

    private static int count(List<com.lc.checker.domain.result.CheckResult> r, CheckStatus s) {
        int n = 0;
        for (var x : r) if (x.status() == s) n++;
        return n;
    }
}
