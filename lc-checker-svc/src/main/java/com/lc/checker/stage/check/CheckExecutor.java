package com.lc.checker.stage.check;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.result.CheckResult;
import com.lc.checker.domain.result.CheckTrace;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.rule.enums.CheckStatus;
import com.lc.checker.domain.rule.enums.CheckType;
import com.lc.checker.domain.rule.enums.MissingInvoiceAction;
import com.lc.checker.infra.fields.FieldDefinition;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.stream.CheckEventPublisher;
import com.lc.checker.stage.check.strategy.CheckStrategy;
import com.lc.checker.stage.check.strategy.CheckStrategy.StrategyOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single per-rule executor used by both the {@code ProgrammaticChecksStage}
 * and {@code AgentChecksStage}. The caller filters the catalog to one
 * {@link CheckType} subset and passes it in.
 *
 * <p>Per rule:
 * <ol>
 *   <li>{@link CheckType#PROGRAMMATIC} — invoice-field presence gate honours
 *       {@link MissingInvoiceAction}; pass-through otherwise.</li>
 *   <li>{@link CheckType#AGENT} — no gate; the agent decides applicability and
 *       missing-data handling itself.</li>
 *   <li>Dispatch to the matching {@link CheckStrategy}; emit {@code rule}
 *       envelope event with the resulting {@link CheckResult}.</li>
 * </ol>
 *
 * <p>An exception inside a strategy converts to DOUBTS — a single broken
 * rule must not crash the pipeline.
 */
@Component
public class CheckExecutor {

    private static final Logger log = LoggerFactory.getLogger(CheckExecutor.class);

    /** Stage name used for per-rule rows in {@code pipeline_steps}. Matches the
     *  reader contract in {@code JdbcCheckSessionStore} (STAGE_LC_CHECK = "lc_check"). */
    private static final String STEP_STAGE_LC_CHECK = "lc_check";

    private final Map<CheckType, CheckStrategy> strategies;
    private final FieldPoolRegistry fieldPool;
    private final CheckSessionStore store;

    public CheckExecutor(List<CheckStrategy> strategyBeans, FieldPoolRegistry fieldPool,
                         CheckSessionStore store) {
        this.strategies = new EnumMap<>(CheckType.class);
        for (CheckStrategy s : strategyBeans) {
            this.strategies.put(s.type(), s);
        }
        this.fieldPool = fieldPool;
        this.store = store;
        log.info("CheckExecutor initialised: strategies={}", strategies.keySet());
    }

    public Result run(String stageName, String sessionId, List<Rule> rules,
                      LcDocument lc, InvoiceDocument inv,
                      CheckEventPublisher publisher) {
        List<CheckResult> results = new ArrayList<>(rules.size());
        List<CheckTrace> traces = new ArrayList<>(rules.size());
        int total = rules.size();

        for (int i = 0; i < total; i++) {
            Rule rule = rules.get(i);
            int idx = i + 1;
            // Emit a progress event BEFORE the dispatch so the UI shows which
            // rule is currently being evaluated. AGENT rules can take seconds
            // each (LLM call) — without this the user sees nothing between the
            // stage.started event and the first rule.completed.
            publisher.progress(stageName,
                    "Checking " + rule.id() + " (" + idx + "/" + total + "): " + rule.name(),
                    Map.of(
                            "ruleId", rule.id(),
                            "ruleName", rule.name() == null ? "" : rule.name(),
                            "index", idx,
                            "total", total));

            Instant ruleStart = Instant.now();
            long start = System.currentTimeMillis();
            StrategyOutcome outcome;
            try {
                outcome = runOne(rule, lc, inv);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                log.error("Rule {} execution threw: {}", rule.id(), e.getMessage(), e);
                CheckResult result = new CheckResult(
                        rule.id(), rule.name(), rule.checkType(), rule.businessPhase(),
                        CheckStatus.DOUBTS,
                        null, firstFieldOrNull(rule.fieldKeys()), null, null,
                        rule.ucpRef(), rule.isbpRef(),
                        "Executor error: " + e.getMessage(),
                        null);
                CheckTrace trace = new CheckTrace(
                        rule.id(), rule.checkType(), CheckStatus.DOUBTS,
                        Map.of(), null, null, duration, e.getMessage());
                outcome = new StrategyOutcome(result, trace);
            }
            Instant ruleEnd = Instant.now();
            results.add(outcome.result());
            traces.add(outcome.trace());

            // Persist this rule outcome to pipeline_steps under stage="lc_check".
            // Matches the reader contract in JdbcCheckSessionStore.loadRuleChecks
            // and the rules_run / discrepancies counts in findRecent. Failure
            // here MUST NOT break the pipeline — log and move on.
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("result", outcome.result());
                payload.put("trace",  outcome.trace());
                payload.put("check_type",
                        rule.checkType() == null ? null : rule.checkType().name());
                String errorMsg = outcome.trace() == null ? null : outcome.trace().error();
                store.putStep(sessionId, STEP_STAGE_LC_CHECK, rule.id(),
                        outcome.result().status().name(),
                        ruleStart, ruleEnd, payload, errorMsg);
            } catch (Exception persistFail) {
                log.warn("Persisting rule step {} failed (non-fatal): {}",
                        rule.id(), persistFail.getMessage());
            }

            publisher.rule(outcome.result());
        }
        return new Result(results, traces);
    }

    private StrategyOutcome runOne(Rule rule, LcDocument lc, InvoiceDocument inv) {
        // PROGRAMMATIC rules apply the missing-invoice-field gate.
        // AGENT rules skip it — the agent decides applicability with the rule definition
        // plus the LC and invoice in front of it.
        if (rule.checkType() == CheckType.PROGRAMMATIC && !hasAnyInvoiceField(rule, inv)) {
            MissingInvoiceAction action = rule.missingInvoiceAction() == null
                    ? MissingInvoiceAction.DOUBTS : rule.missingInvoiceAction();
            CheckStatus status = switch (action) {
                case FAIL -> CheckStatus.FAIL;
                case DOUBTS -> CheckStatus.DOUBTS;
            };
            return preGateOutcome(rule, status, missingFieldDescription(rule));
        }

        CheckStrategy strategy = strategies.get(rule.checkType());
        if (strategy == null) {
            return preGateOutcome(rule, CheckStatus.DOUBTS,
                    "No strategy registered for check_type=" + rule.checkType());
        }
        return strategy.execute(rule, lc, inv);
    }

    /**
     * Pattern-aware missing-field message — driven off rule shape so the operator
     * sees a concrete reason rather than "missing fields: [seller_name]".
     */
    private String missingFieldDescription(Rule rule) {
        String outputField = rule.outputField() == null ? rule.id() : rule.outputField();
        boolean rulesAgainstLc = rule.fieldKeys().stream()
                .map(fieldPool::byKey)
                .anyMatch(opt -> opt.isPresent() && opt.get().appliesToLc());
        if (!rulesAgainstLc) {
            // Pure mandatory presence — no LC comparison.
            return outputField + " is mandatory on every commercial invoice per "
                    + safeRef(rule) + " but was not extracted";
        }
        return outputField + " not found on invoice; required for " + rule.id() + " compliance check";
    }

    private static String safeRef(Rule rule) {
        if (rule.ucpRef() != null) return rule.ucpRef();
        if (rule.isbpRef() != null) return rule.isbpRef();
        return rule.id();
    }

    /**
     * Returns true if the rule has at least one invoice-applicable field_key
     * present on the invoice envelope. Rules with no invoice-applicable keys
     * (e.g. LC-only checks) trivially pass the gate.
     */
    private boolean hasAnyInvoiceField(Rule rule, InvoiceDocument inv) {
        if (rule.fieldKeys().isEmpty()) return true;
        List<String> invoiceKeys = rule.fieldKeys().stream()
                .map(fieldPool::byKey)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .filter(FieldDefinition::appliesToInvoice)
                .map(FieldDefinition::key)
                .toList();
        if (invoiceKeys.isEmpty()) return true;
        if (inv == null || inv.envelope() == null) return false;
        Map<String, Object> fields = inv.envelope().fields();
        for (String k : invoiceKeys) {
            Object v = fields.get(k);
            if (v != null && !(v instanceof String s && s.isBlank())) {
                return true;
            }
        }
        return false;
    }

    private static StrategyOutcome preGateOutcome(Rule rule, CheckStatus status, String description) {
        CheckResult result = new CheckResult(
                rule.id(), rule.name(), rule.checkType(), rule.businessPhase(), status,
                status == CheckStatus.FAIL ? rule.severityOnFail() : null,
                rule.outputField() != null ? rule.outputField() : firstFieldOrNull(rule.fieldKeys()),
                null, null,
                rule.ucpRef(), rule.isbpRef(),
                description,
                null);
        CheckTrace trace = new CheckTrace(
                rule.id(), rule.checkType(), status,
                Map.of("fieldKeys", rule.fieldKeys()),
                null, null, 0L, null);
        return new StrategyOutcome(result, trace);
    }

    private static String firstFieldOrNull(List<String> fields) {
        return (fields == null || fields.isEmpty()) ? null : fields.get(0);
    }

    public record Result(List<CheckResult> results, List<CheckTrace> traces) {
    }
}
