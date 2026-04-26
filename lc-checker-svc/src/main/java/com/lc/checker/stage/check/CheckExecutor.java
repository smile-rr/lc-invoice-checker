package com.lc.checker.stage.check;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.result.CheckResult;
import com.lc.checker.domain.result.CheckTrace;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.rule.enums.CheckStatus;
import com.lc.checker.domain.rule.enums.CheckType;
import com.lc.checker.domain.rule.enums.MissingInvoiceAction;
import com.lc.checker.infra.stream.CheckEventPublisher;
import com.lc.checker.stage.check.strategy.CheckStrategy;
import com.lc.checker.stage.check.strategy.CheckStrategy.StrategyOutcome;
import java.util.ArrayList;
import java.util.EnumMap;
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
 * <p>An exception inside a strategy converts to UNABLE_TO_VERIFY — a single
 * broken rule must not crash the pipeline.
 */
@Component
public class CheckExecutor {

    private static final Logger log = LoggerFactory.getLogger(CheckExecutor.class);

    private final Map<CheckType, CheckStrategy> strategies;

    public CheckExecutor(List<CheckStrategy> strategyBeans) {
        this.strategies = new EnumMap<>(CheckType.class);
        for (CheckStrategy s : strategyBeans) {
            this.strategies.put(s.type(), s);
        }
        log.info("CheckExecutor initialised: strategies={}", strategies.keySet());
    }

    public Result run(String stageName, List<Rule> rules, LcDocument lc, InvoiceDocument inv,
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

            long start = System.currentTimeMillis();
            StrategyOutcome outcome;
            try {
                outcome = runOne(rule, lc, inv);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                log.error("Rule {} execution threw: {}", rule.id(), e.getMessage(), e);
                CheckResult result = new CheckResult(
                        rule.id(), rule.name(), rule.checkType(), rule.businessPhase(),
                        CheckStatus.UNABLE_TO_VERIFY,
                        null, firstFieldOrNull(rule.invoiceFields()), null, null,
                        rule.ucpRef(), rule.isbpRef(),
                        "Executor error: " + e.getMessage());
                CheckTrace trace = new CheckTrace(
                        rule.id(), rule.checkType(), CheckStatus.UNABLE_TO_VERIFY,
                        Map.of(), null, null, duration, e.getMessage());
                outcome = new StrategyOutcome(result, trace);
            }
            results.add(outcome.result());
            traces.add(outcome.trace());
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
                    ? MissingInvoiceAction.UNABLE_TO_VERIFY : rule.missingInvoiceAction();
            CheckStatus status = switch (action) {
                case DISCREPANT -> CheckStatus.DISCREPANT;
                case NOT_APPLICABLE -> CheckStatus.NOT_APPLICABLE;
                case UNABLE_TO_VERIFY -> CheckStatus.UNABLE_TO_VERIFY;
            };
            return preGateOutcome(rule, status, missingFieldDescription(rule));
        }

        CheckStrategy strategy = strategies.get(rule.checkType());
        if (strategy == null) {
            return preGateOutcome(rule, CheckStatus.UNABLE_TO_VERIFY,
                    "No strategy registered for check_type=" + rule.checkType());
        }
        return strategy.execute(rule, lc, inv);
    }

    /**
     * Pattern-aware missing-field message — driven off rule shape so the operator
     * sees a concrete reason rather than "missing fields: [seller_name]".
     */
    private static String missingFieldDescription(Rule rule) {
        String outputField = rule.outputField() == null ? rule.id() : rule.outputField();
        if (rule.lcFields().isEmpty()) {
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

    private static boolean hasAnyInvoiceField(Rule rule, InvoiceDocument inv) {
        if (rule.invoiceFields().isEmpty()) return true;
        if (inv == null) return false;
        for (String f : rule.invoiceFields()) {
            if (getInvoiceField(inv, f) != null) {
                return true;
            }
        }
        return false;
    }

    /** Snake-case field name → value lookup. Unknown keys return null (treated as missing). */
    private static Object getInvoiceField(InvoiceDocument inv, String key) {
        return switch (key) {
            case "invoice_number" -> inv.invoiceNumber();
            case "invoice_date" -> inv.invoiceDate();
            case "seller_name" -> inv.sellerName();
            case "seller_address" -> inv.sellerAddress();
            case "buyer_name" -> inv.buyerName();
            case "buyer_address" -> inv.buyerAddress();
            case "goods_description" -> inv.goodsDescription();
            case "quantity" -> inv.quantity();
            case "unit" -> inv.unit();
            case "unit_price" -> inv.unitPrice();
            case "total_amount" -> inv.totalAmount();
            case "currency" -> inv.currency();
            case "lc_reference" -> inv.lcReference();
            case "trade_terms" -> inv.tradeTerms();
            case "port_of_loading" -> inv.portOfLoading();
            case "port_of_discharge" -> inv.portOfDischarge();
            case "country_of_origin" -> inv.countryOfOrigin();
            case "signed" -> inv.signed();
            default -> null;
        };
    }

    private static StrategyOutcome preGateOutcome(Rule rule, CheckStatus status, String description) {
        CheckResult result = new CheckResult(
                rule.id(), rule.name(), rule.checkType(), rule.businessPhase(), status,
                status == CheckStatus.DISCREPANT ? rule.severityOnFail() : null,
                rule.outputField() != null ? rule.outputField() : firstFieldOrNull(rule.invoiceFields()),
                null, null,
                rule.ucpRef(), rule.isbpRef(),
                description);
        CheckTrace trace = new CheckTrace(
                rule.id(), rule.checkType(), status,
                Map.of("lcFields", rule.lcFields(), "invoiceFields", rule.invoiceFields()),
                null, null, 0L, null);
        return new StrategyOutcome(result, trace);
    }

    private static String firstFieldOrNull(List<String> fields) {
        return (fields == null || fields.isEmpty()) ? null : fields.get(0);
    }

    public record Result(List<CheckResult> results, List<CheckTrace> traces) {
    }
}
