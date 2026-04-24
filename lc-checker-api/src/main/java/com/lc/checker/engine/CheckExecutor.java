package com.lc.checker.engine;

import com.lc.checker.checker.RuleImpl;
import com.lc.checker.checker.SpiRuleChecker;
import com.lc.checker.model.CheckResult;
import com.lc.checker.model.CheckTrace;
import com.lc.checker.model.InvoiceDocument;
import com.lc.checker.model.LcDocument;
import com.lc.checker.model.Rule;
import com.lc.checker.model.enums.CheckStatus;
import com.lc.checker.model.enums.CheckType;
import com.lc.checker.model.enums.MissingInvoiceAction;
import com.lc.checker.strategy.CheckStrategy;
import com.lc.checker.strategy.CheckStrategy.StrategyOutcome;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Stage 3 orchestrator. For every activated rule:
 *
 * <ol>
 *   <li>Q1 pre-gate — any LC field declared by the rule must be present; if not, emit
 *       {@link CheckStatus#UNABLE_TO_VERIFY}. V1 treats "present" as "raw tag appeared
 *       in the MT700"; Part B's richer introspection is not yet wired here.</li>
 *   <li>Q2 pre-gate — any invoice field declared by the rule must be present; if not,
 *       apply the rule's {@link MissingInvoiceAction} policy.</li>
 *   <li>SPI dispatch — if a {@link RuleImpl @RuleImpl("ID")} bean is registered for this
 *       rule id, use it; else go to the strategy table.</li>
 *   <li>Strategy dispatch — look up by {@link CheckType}, delegate.</li>
 * </ol>
 *
 * <p>Any unhandled exception inside a strategy is caught and converted to
 * UNABLE_TO_VERIFY — a single broken rule must not crash the pipeline.
 */
@Component
public class CheckExecutor {

    private static final Logger log = LoggerFactory.getLogger(CheckExecutor.class);

    private final Map<CheckType, CheckStrategy> strategies;
    private final Map<String, SpiRuleChecker> spiByRuleId;

    public CheckExecutor(List<CheckStrategy> strategyBeans, ApplicationContext ctx) {
        this.strategies = new EnumMap<>(CheckType.class);
        for (CheckStrategy s : strategyBeans) {
            this.strategies.put(s.type(), s);
        }
        this.spiByRuleId = discoverSpiBeans(ctx);
        log.info("CheckExecutor initialised: strategies={} spiRules={}",
                strategies.keySet(), spiByRuleId.keySet());
    }

    public Result run(List<Rule> activeRules, LcDocument lc, InvoiceDocument inv) {
        List<CheckResult> results = new ArrayList<>(activeRules.size());
        List<CheckTrace> traces = new ArrayList<>(activeRules.size());

        for (Rule rule : activeRules) {
            long start = System.currentTimeMillis();
            StrategyOutcome outcome;
            try {
                outcome = runOne(rule, lc, inv);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                log.error("Rule {} execution threw: {}", rule.id(), e.getMessage(), e);
                CheckResult result = new CheckResult(
                        rule.id(), rule.name(), rule.checkType(), CheckStatus.UNABLE_TO_VERIFY,
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
        }
        return new Result(results, traces);
    }

    // -----------------------------------------------------------------------

    private StrategyOutcome runOne(Rule rule, LcDocument lc, InvoiceDocument inv) {
        // Q1 — LC-side gate
        if (!hasAnyLcField(rule, lc)) {
            return preGateOutcome(rule, CheckStatus.UNABLE_TO_VERIFY,
                    "LC is missing fields required by " + rule.id() + ": " + rule.lcFields());
        }

        // Q2 — invoice-side gate
        if (!hasAnyInvoiceField(rule, inv)) {
            MissingInvoiceAction action = rule.missingInvoiceAction() == null
                    ? MissingInvoiceAction.UNABLE_TO_VERIFY : rule.missingInvoiceAction();
            CheckStatus status = switch (action) {
                case DISCREPANT -> CheckStatus.DISCREPANT;
                case NOT_APPLICABLE -> CheckStatus.NOT_APPLICABLE;
                case UNABLE_TO_VERIFY -> CheckStatus.UNABLE_TO_VERIFY;
            };
            return preGateOutcome(rule, status,
                    "Invoice is missing fields required by " + rule.id() + ": " + rule.invoiceFields());
        }

        // SPI wins over strategy dispatch.
        SpiRuleChecker spi = spiByRuleId.get(rule.id());
        if (spi != null) {
            return spi.check(rule, lc, inv);
        }

        CheckStrategy strategy = strategies.get(rule.checkType());
        if (strategy == null) {
            return preGateOutcome(rule, CheckStatus.UNABLE_TO_VERIFY,
                    "No strategy registered for check_type=" + rule.checkType());
        }
        return strategy.execute(rule, lc, inv);
    }

    /**
     * LC-field presence check. Strict rule: at least one of {@code rule.lcFields()} must
     * appear in {@code lc.rawFields()}. This accommodates multi-field rules (INV-011 reads
     * {32B, 39A, 39B} — :39A: is optional).
     */
    private static boolean hasAnyLcField(Rule rule, LcDocument lc) {
        if (rule.lcFields().isEmpty()) return true;
        if (lc == null || lc.rawFields() == null) return false;
        for (String tag : rule.lcFields()) {
            if (lc.rawFields().containsKey(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Invoice-field presence check. Heuristic: check the field is non-null on the extracted
     * {@link InvoiceDocument}. Field names in the catalog correspond to snake_case keys in
     * the extractor contract; we map them to the Java accessor.
     */
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
                rule.id(), rule.name(), rule.checkType(), status,
                status == CheckStatus.DISCREPANT ? rule.severityOnFail() : null,
                firstFieldOrNull(rule.invoiceFields()),
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

    private static Map<String, SpiRuleChecker> discoverSpiBeans(ApplicationContext ctx) {
        Map<String, SpiRuleChecker> out = new HashMap<>();
        for (Map.Entry<String, Object> e : ctx.getBeansWithAnnotation(RuleImpl.class).entrySet()) {
            Object bean = e.getValue();
            if (!(bean instanceof SpiRuleChecker spi)) {
                log.warn("Bean {} is annotated @RuleImpl but does not implement SpiRuleChecker; ignored", e.getKey());
                continue;
            }
            RuleImpl ann = bean.getClass().getAnnotation(RuleImpl.class);
            if (ann == null) continue;
            out.put(ann.value(), spi);
        }
        return out;
    }

    public record Result(List<CheckResult> results, List<CheckTrace> traces) {
    }
}
