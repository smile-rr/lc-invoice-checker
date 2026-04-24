package com.lc.checker.strategy;

import com.lc.checker.model.CheckResult;
import com.lc.checker.model.CheckTrace;
import com.lc.checker.model.ExpressionTrace;
import com.lc.checker.model.InvoiceDocument;
import com.lc.checker.model.LcDocument;
import com.lc.checker.model.Rule;
import com.lc.checker.model.enums.CheckStatus;
import com.lc.checker.model.enums.CheckType;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.stereotype.Component;

/**
 * Hybrid strategy. Evaluates the deterministic {@code pre_gate_expression} first —
 * if the pre-gate fails, the rule is flagged {@link CheckStatus#DISCREPANT} without
 * an LLM round-trip. If the pre-gate passes, control delegates to the Type-B flow for
 * semantic confirmation.
 *
 * <p>The merged trace carries both the {@link ExpressionTrace} from the pre-gate and
 * the {@link com.lc.checker.model.LlmTrace} from the LLM call, so reviewers can see
 * exactly which path the rule took.
 *
 * <p>V1 does not activate any AB rules, but the class is fully wired so adding one in
 * V1.5 is a YAML-only change.
 */
@Component
public class TypeABStrategy implements CheckStrategy {

    private static final Logger log = LoggerFactory.getLogger(TypeABStrategy.class);

    private final SpelBinder binder;
    private final TypeBStrategy typeB;

    public TypeABStrategy(SpelBinder binder, TypeBStrategy typeB) {
        this.binder = binder;
        this.typeB = typeB;
    }

    @Override
    public CheckType type() {
        return CheckType.AB;
    }

    @Override
    public StrategyOutcome execute(Rule rule, LcDocument lc, InvoiceDocument inv) {
        long start = System.currentTimeMillis();

        String preGate = rule.preGateExpression();
        ExpressionTrace preGateTrace = null;
        boolean preGatePassed = true;

        if (preGate != null && !preGate.isBlank()) {
            try {
                EvaluationContext ctx = binder.forExecution(lc, inv);
                Object result = binder.parser().parseExpression(preGate).getValue(ctx);
                preGatePassed = isTruthy(result);
                preGateTrace = new ExpressionTrace(preGate, snapshot(lc, inv), result, null);
            } catch (Exception e) {
                log.warn("AB rule {} pre-gate failed to evaluate: {}", rule.id(), e.getMessage());
                preGateTrace = new ExpressionTrace(preGate, snapshot(lc, inv), null, e.getMessage());
                // Fail safe: can't evaluate pre-gate → UNABLE_TO_VERIFY (do not proceed to LLM).
                return unableToVerify(rule, preGateTrace, "Pre-gate evaluation error: " + e.getMessage(), start);
            }
        }

        if (!preGatePassed) {
            // Hard-fail without spending an LLM token.
            long duration = System.currentTimeMillis() - start;
            CheckResult result = new CheckResult(
                    rule.id(), rule.name(), CheckType.AB, CheckStatus.DISCREPANT,
                    rule.severityOnFail(),
                    firstFieldOrNull(rule.invoiceFields()),
                    null, null,
                    rule.ucpRef(), rule.isbpRef(),
                    "Pre-gate failed for " + rule.id() + "; LLM skipped");
            CheckTrace trace = new CheckTrace(
                    rule.id(), CheckType.AB, CheckStatus.DISCREPANT,
                    preGateTrace == null ? Map.of() : preGateTrace.boundVariables(),
                    preGateTrace, null, duration, null);
            return new StrategyOutcome(result, trace);
        }

        // Delegate to Type B for the semantic step.
        StrategyOutcome semantic = typeB.runTypeB(rule, lc, inv);
        // Re-label as AB and graft the pre-gate trace.
        long duration = System.currentTimeMillis() - start;
        CheckResult sResult = semantic.result();
        CheckResult rebranded = new CheckResult(
                sResult.ruleId(), sResult.ruleName(), CheckType.AB,
                sResult.status(), sResult.severity(),
                sResult.field(), sResult.lcValue(), sResult.presentedValue(),
                sResult.ucpRef(), sResult.isbpRef(), sResult.description());
        CheckTrace sTrace = semantic.trace();
        CheckTrace merged = new CheckTrace(
                sTrace.ruleId(), CheckType.AB, sTrace.status(),
                sTrace.inputSnapshot(),
                preGateTrace,
                sTrace.llmTrace(),
                duration,
                sTrace.error());
        return new StrategyOutcome(rebranded, merged);
    }

    // -----------------------------------------------------------------------

    private static boolean isTruthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return true;
    }

    private static Map<String, Object> snapshot(LcDocument lc, InvoiceDocument inv) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        if (lc != null) {
            m.put("lc.amount", lc.amount());
            m.put("lc.currency", lc.currency());
            m.put("lc.lcNumber", lc.lcNumber());
        }
        if (inv != null) {
            m.put("inv.totalAmount", inv.totalAmount());
            m.put("inv.currency", inv.currency());
            m.put("inv.lcReference", inv.lcReference());
        }
        return m;
    }

    private static StrategyOutcome unableToVerify(Rule rule, ExpressionTrace preGateTrace,
                                                  String description, long start) {
        long duration = System.currentTimeMillis() - start;
        CheckResult result = new CheckResult(
                rule.id(), rule.name(), CheckType.AB, CheckStatus.UNABLE_TO_VERIFY,
                null,
                firstFieldOrNull(rule.invoiceFields()),
                null, null,
                rule.ucpRef(), rule.isbpRef(), description);
        CheckTrace trace = new CheckTrace(
                rule.id(), CheckType.AB, CheckStatus.UNABLE_TO_VERIFY,
                preGateTrace == null ? Map.of() : preGateTrace.boundVariables(),
                preGateTrace, null, duration, description);
        return new StrategyOutcome(result, trace);
    }

    private static String firstFieldOrNull(List<String> fields) {
        return (fields == null || fields.isEmpty()) ? null : fields.get(0);
    }
}
