package com.lc.checker.stage.check;

import com.lc.checker.stage.check.spi.RuleImpl;
import com.lc.checker.stage.check.spi.SpiRuleChecker;
import com.lc.checker.domain.result.CheckResult;
import com.lc.checker.domain.result.CheckTrace;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.rule.enums.CheckStatus;
import com.lc.checker.domain.rule.enums.CheckType;
import com.lc.checker.domain.rule.enums.MissingInvoiceAction;
import com.lc.checker.stage.check.strategy.CheckStrategy;
import com.lc.checker.stage.check.strategy.CheckStrategy.StrategyOutcome;
import com.lc.checker.infra.stream.CheckEvent;
import com.lc.checker.infra.stream.CheckEventPublisher;
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
        return run(activeRules, lc, inv, CheckEventPublisher.NOOP);
    }

    /**
     * Publisher-aware overload. Emits {@link CheckEvent.Type#CHECK_STARTED} before
     * each rule's {@code runOne} and {@link CheckEvent.Type#CHECK_COMPLETED} with
     * the resulting {@link CheckResult} after. The existing signature delegates
     * with {@link CheckEventPublisher#NOOP}.
     */
    public Result run(List<Rule> activeRules, LcDocument lc, InvoiceDocument inv,
                      CheckEventPublisher publisher) {
        List<CheckResult> results = new ArrayList<>(activeRules.size());
        List<CheckTrace> traces = new ArrayList<>(activeRules.size());

        for (Rule rule : activeRules) {
            long start = System.currentTimeMillis();
            publisher.emit(CheckEvent.of(CheckEvent.Type.CHECK_STARTED,
                    Map.of("ruleId", rule.id())));
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
            publisher.emit(CheckEvent.of(CheckEvent.Type.CHECK_COMPLETED, outcome.result()));
        }
        return new Result(results, traces);
    }

    /**
     * Stage 3 tiered execution — run all Tier 1 rules first, then all Tier 2, then all
     * Tier 3. No fail-fast: every activated rule executes regardless of earlier outcomes.
     * The tier is derived from {@link CheckType}:
     * <ul>
     *   <li>Tier 1 — {@code CheckType.A}  (deterministic SpEL, zero LLM)</li>
     *   <li>Tier 2 — {@code CheckType.B}  (LLM semantic)</li>
     *   <li>Tier 3 — {@code CheckType.AB} (SpEL pre-gate + LLM confirm)</li>
     * </ul>
     * {@code CheckType.SPI} rules run inside the tier of their delegated strategy
     * (they dispatch by rule id to a registered bean; if the bean exposes no strategy
     * type the rule defaults to Tier 1 so the SPI implementation owns its behavior).
     */
    public List<TierResult> runAllTiers(List<Rule> activeRules, LcDocument lc, InvoiceDocument inv) {
        return runAllTiers(activeRules, lc, inv, CheckEventPublisher.NOOP);
    }

    /** Publisher-aware overload used by the streaming pipeline. */
    public List<TierResult> runAllTiers(List<Rule> activeRules, LcDocument lc, InvoiceDocument inv,
                                        CheckEventPublisher publisher) {
        List<Rule> tier1 = filterByType(activeRules, CheckType.A);
        List<Rule> tier2 = filterByType(activeRules, CheckType.B);
        List<Rule> tier3 = filterByType(activeRules, CheckType.AB);
        List<Rule> spi   = filterByType(activeRules, CheckType.SPI);
        // SPI rules land in Tier 1 by default — their bean owns whatever it wants to do.
        if (!spi.isEmpty()) {
            tier1 = new ArrayList<>(tier1);
            tier1.addAll(spi);
        }

        List<TierResult> tiers = new ArrayList<>(3);
        tiers.add(runTier(1, tier1, lc, inv, publisher));
        tiers.add(runTier(2, tier2, lc, inv, publisher));
        tiers.add(runTier(3, tier3, lc, inv, publisher));
        return tiers;
    }

    /** Executes one tier's rules as a single pass. Returns both the results and traces. */
    public TierResult runTier(int tier, List<Rule> rules, LcDocument lc, InvoiceDocument inv) {
        return runTier(tier, rules, lc, inv, CheckEventPublisher.NOOP);
    }

    /** Publisher-aware overload. */
    public TierResult runTier(int tier, List<Rule> rules, LcDocument lc, InvoiceDocument inv,
                              CheckEventPublisher publisher) {
        if (rules.isEmpty()) {
            return new TierResult(tier, List.of(), List.of());
        }
        log.debug("Stage 3 Tier {} — running {} rule(s)", tier, rules.size());
        Result r = run(rules, lc, inv, publisher);
        return new TierResult(tier, r.results(), r.traces());
    }

    private static List<Rule> filterByType(List<Rule> rules, CheckType type) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            if (r.checkType() == type) out.add(r);
        }
        return out;
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

    /** One tier's output — used by {@code CheckExecutionStage} to persist per-tier rows. */
    public record TierResult(int tier, List<CheckResult> results, List<CheckTrace> traces) {
    }
}
