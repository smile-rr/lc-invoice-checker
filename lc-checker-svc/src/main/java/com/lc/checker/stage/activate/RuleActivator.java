package com.lc.checker.stage.activate;

import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.result.RuleActivationTrace;
import com.lc.checker.domain.result.RuleActivationTrace.RuleActivation;
import com.lc.checker.domain.rule.enums.ActivationTrigger;
import com.lc.checker.domain.rule.RuleCatalog;
import com.lc.checker.stage.check.strategy.SpelBinder;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.stereotype.Component;

/**
 * Stage 2 of the pipeline. Iterates the catalog and decides which rules will run for
 * this particular LC, writing every decision (activated or not, plus reason) into a
 * {@link RuleActivationTrace}.
 *
 * <p>Activation logic per {@link ActivationTrigger}:
 * <ul>
 *   <li>{@link ActivationTrigger#UNCONDITIONAL} — always activate.</li>
 *   <li>{@link ActivationTrigger#LC_STIPULATED} — evaluate {@code rule.activationExpr}
 *       (SpEL) against the LC. Null/missing activation_expr is treated as "missing
 *       config" and produces NOT activated + reason explaining the gap.</li>
 *   <li>{@link ActivationTrigger#DYNAMIC_47A} — V1: always NOT activated (parse only).
 *       The trace records it for transparency; V2 emits per-condition check nodes.</li>
 * </ul>
 */
@Component
public class RuleActivator {

    private static final Logger log = LoggerFactory.getLogger(RuleActivator.class);

    private final RuleCatalog catalog;
    private final SpelBinder binder;

    public RuleActivator(RuleCatalog catalog, SpelBinder binder) {
        this.catalog = catalog;
        this.binder = binder;
    }

    public Result activate(LcDocument lc) {
        long start = System.currentTimeMillis();
        List<Rule> activeRules = new ArrayList<>();
        List<RuleActivation> decisions = new ArrayList<>();

        for (Rule rule : catalog.all()) {
            if (!rule.isEnabled()) {
                decisions.add(new RuleActivation(
                        rule.id(), rule.trigger(), false,
                        "Rule disabled in catalog (enabled=false)", null));
                continue;
            }

            switch (rule.trigger()) {
                case UNCONDITIONAL -> {
                    activeRules.add(rule);
                    decisions.add(new RuleActivation(
                            rule.id(), rule.trigger(), true, "Unconditional — UCP bottom-line", null));
                }
                case LC_STIPULATED -> {
                    String expr = rule.activationExpr();
                    if (expr == null || expr.isBlank()) {
                        decisions.add(new RuleActivation(
                                rule.id(), rule.trigger(), false,
                                "LC_STIPULATED rule has no activation_expr; cannot decide",
                                null));
                        continue;
                    }
                    try {
                        EvaluationContext ctx = binder.forActivation(lc);
                        Object result = binder.parser().parseExpression(expr).getValue(ctx);
                        boolean active = result instanceof Boolean b && b;
                        if (active) {
                            activeRules.add(rule);
                            decisions.add(new RuleActivation(
                                    rule.id(), rule.trigger(), true,
                                    "Activation expression evaluated true", expr));
                        } else {
                            decisions.add(new RuleActivation(
                                    rule.id(), rule.trigger(), false,
                                    "Activation expression evaluated " + result, expr));
                        }
                    } catch (Exception e) {
                        // If the LC simply lacks the documents_required data (Part B not run or
                        // failed), treat as NOT activated rather than fatal — NOT_APPLICABLE
                        // fits the reviewer's mental model better than UNABLE_TO_VERIFY.
                        log.debug("Activation expr '{}' threw; treating rule {} as not activated: {}",
                                expr, rule.id(), e.getMessage());
                        decisions.add(new RuleActivation(
                                rule.id(), rule.trigger(), false,
                                "Activation expression error: " + e.getMessage(), expr));
                    }
                }
                case DYNAMIC_47A -> decisions.add(new RuleActivation(
                        rule.id(), rule.trigger(), false,
                        "DYNAMIC_47A not emitted in V1 (parse-only)", null));
            }
        }

        long duration = System.currentTimeMillis() - start;
        return new Result(activeRules, new RuleActivationTrace(decisions, duration));
    }

    public record Result(List<Rule> activeRules, RuleActivationTrace trace) {
    }
}
