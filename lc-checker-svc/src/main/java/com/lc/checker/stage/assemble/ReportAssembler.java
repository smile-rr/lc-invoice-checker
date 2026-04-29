package com.lc.checker.stage.assemble;

import com.lc.checker.domain.result.CheckResult;
import com.lc.checker.domain.result.Discrepancy;
import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.result.Summary;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.rule.RuleCatalog;
import com.lc.checker.domain.rule.enums.CheckStatus;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Stage 5 of the pipeline. Takes the raw {@link CheckResult}s and produces the
 * final {@link DiscrepancyReport} — the JSON body returned by
 * {@code POST /api/v1/lc-check}.
 *
 * <p><b>Output contract.</b> {@code discrepancies[]} contains only
 * {@link CheckStatus#FAIL} items in the spec-shape
 * {@code {field, lc_value, presented_value, rule_reference, description}}.
 * Compliance is determined by both FAIL and DOUBTS buckets — NOT_REQUIRED
 * never flips {@code compliant} (Art 16 protocol).
 *
 * <p>Three rules use bespoke formatters to preserve the locked sample-output
 * text (UCP-18b-amount, ISBP-C3, ISBP-C1). Every other rule uses the generic
 * path that pulls {@code output_field} and {@code rule_reference_label}
 * directly from {@link Rule}.
 */
@Component
public class ReportAssembler {

    private static final NumberFormat AMOUNT_FORMAT =
            NumberFormat.getNumberInstance(Locale.US);

    static {
        AMOUNT_FORMAT.setMinimumFractionDigits(2);
        AMOUNT_FORMAT.setMaximumFractionDigits(2);
        AMOUNT_FORMAT.setGroupingUsed(true);
    }

    private final RuleCatalog catalog;

    public ReportAssembler(RuleCatalog catalog) {
        this.catalog = catalog;
    }

    public DiscrepancyReport assemble(
            String sessionId,
            LcDocument lc,
            InvoiceDocument inv,
            List<CheckResult> checks) {

        List<Discrepancy> discrepancies = new ArrayList<>();
        List<CheckResult> passed = new ArrayList<>();
        List<CheckResult> failed = new ArrayList<>();
        List<CheckResult> doubts = new ArrayList<>();
        List<CheckResult> notRequired = new ArrayList<>();

        for (CheckResult r : checks) {
            switch (r.status()) {
                case PASS -> passed.add(r);
                case FAIL -> {
                    discrepancies.add(toDiscrepancy(r, lc, inv));
                    failed.add(r);
                }
                case DOUBTS -> doubts.add(r);
                case NOT_REQUIRED -> notRequired.add(r);
            }
        }

        // Compliance flips on FAIL or DOUBTS. NOT_REQUIRED never does.
        boolean compliant = failed.isEmpty() && doubts.isEmpty();

        Summary summary = new Summary(
                checks.size(),
                passed.size(),
                failed.size(),
                doubts.size(),
                notRequired.size());

        return new DiscrepancyReport(
                sessionId,
                compliant,
                discrepancies,
                passed,
                failed,
                doubts,
                notRequired,
                summary);
    }

    // -----------------------------------------------------------------------
    // per-rule output shaping
    // -----------------------------------------------------------------------

    private Discrepancy toDiscrepancy(CheckResult r, LcDocument lc, InvoiceDocument inv) {
        return switch (r.ruleId()) {
            case "UCP-18b-amount" -> amountDiscrepancy(r, lc, inv);
            case "ISBP-C3" -> goodsDescriptionDiscrepancy(r, lc, inv);
            case "ISBP-C1" -> lcNumberDiscrepancy(r, lc, inv);
            default -> genericDiscrepancy(r);
        };
    }

    private Discrepancy amountDiscrepancy(CheckResult r, LcDocument lc, InvoiceDocument inv) {
        String lcValue = formatMoney(lc.currency(), lc.amount());
        String presented = formatMoney(
                inv == null ? null : inv.currency(),
                inv == null ? null : inv.totalAmount());
        String description = (inv == null || inv.totalAmount() == null)
                ? "Invoice total amount is missing or not readable; cannot verify compliance with LC amount."
                : "Invoice amount " + presented + " exceeds the LC amount of " + lcValue
                        + " and tolerance of " + lc.tolerancePlus() + "% is not applicable for unit price goods.";
        return new Discrepancy("invoice_amount", lcValue, presented, r.ucpRef(), description);
    }

    private Discrepancy goodsDescriptionDiscrepancy(CheckResult r, LcDocument lc, InvoiceDocument inv) {
        String lcValue = singleLine(lc == null ? null : lc.field45ARaw());
        String presented = singleLine(inv == null ? null : inv.goodsDescription());
        String description = r.description() != null
                ? "Goods description on invoice does not mirror the LC. " + r.description()
                : "Goods description on invoice does not mirror the LC.";
        String ruleRef = combineRefs(r.ucpRef(), r.isbpRef());
        return new Discrepancy("goods_description", lcValue, presented, ruleRef, description);
    }

    private Discrepancy lcNumberDiscrepancy(CheckResult r, LcDocument lc, InvoiceDocument inv) {
        String lcValue = lc == null ? null : lc.lcNumber();
        String presented = inv == null ? null : inv.lcReference();
        String description = (presented == null)
                ? "Invoice does not indicate the LC number as required by field 46A of the LC."
                : "Invoice LC reference '" + presented + "' does not match LC number '" + lcValue + "'.";
        // Per sample output the reference for this rule is the ISBP paragraph alone.
        String ruleRef = r.isbpRef() != null ? r.isbpRef() : r.ucpRef();
        return new Discrepancy("lc_number_reference", lcValue, presented, ruleRef, description);
    }

    /**
     * Generic mapping path used by every non-legacy rule. Pulls the spec-locked
     * {@code field} and {@code rule_reference} strings from the rule definition
     * itself — no per-rule code path needed when a new rule is added to the catalog.
     */
    private Discrepancy genericDiscrepancy(CheckResult r) {
        Rule rule = catalog.findById(r.ruleId()).orElse(null);
        String field = pickField(rule, r);
        String ruleRef = pickRuleReference(rule, r);
        return new Discrepancy(
                field,
                r.lcValue(),
                r.presentedValue(),
                ruleRef,
                r.description());
    }

    private static String pickField(Rule rule, CheckResult r) {
        if (rule != null && rule.outputField() != null && !rule.outputField().isBlank()) {
            return rule.outputField();
        }
        if (r.field() != null && !r.field().isBlank()) {
            return r.field();
        }
        return r.ruleId();
    }

    private static String pickRuleReference(Rule rule, CheckResult r) {
        if (rule != null && rule.ruleReferenceLabel() != null && !rule.ruleReferenceLabel().isBlank()) {
            return rule.ruleReferenceLabel();
        }
        return combineRefs(r.ucpRef(), r.isbpRef());
    }

    private static String combineRefs(String ucp, String isbp) {
        if (ucp == null && isbp == null) return null;
        if (ucp == null) return isbp;
        if (isbp == null) return ucp;
        return ucp + " / " + isbp;
    }

    /** Formats {@code USD 50,000.00}. Null-tolerant. */
    private static String formatMoney(String currency, BigDecimal amount) {
        if (currency == null && amount == null) return null;
        String amt = amount == null ? null : AMOUNT_FORMAT.format(amount);
        if (currency == null) return amt;
        if (amt == null) return currency;
        return currency + " " + amt;
    }

    /** Collapse multi-line text to a single line for {@code lc_value} / {@code presented_value}. */
    private static String singleLine(String s) {
        if (s == null) return null;
        return s.replaceAll("\\s*\\r?\\n\\s*", ", ").trim();
    }
}
