package com.lc.checker.engine;

import com.lc.checker.model.CheckResult;
import com.lc.checker.model.Discrepancy;
import com.lc.checker.model.DiscrepancyReport;
import com.lc.checker.model.InvoiceDocument;
import com.lc.checker.model.LcDocument;
import com.lc.checker.model.Summary;
import com.lc.checker.model.enums.CheckStatus;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Stage 5 of the pipeline. Takes the raw {@link CheckResult}s from {@code CheckExecutor}
 * and produces the final {@link DiscrepancyReport} — the JSON body returned by
 * {@code POST /api/v1/lc-check}.
 *
 * <p>Besides the obvious PASS/DISCREPANT/etc. bucketing, this class shapes the
 * {@link Discrepancy} rows so they match the test-case expected output verbatim:
 * {@code {field, lc_value, presented_value, rule_reference, description}}. For each
 * V1 active rule, the known output format is honoured (e.g. INV-011 prints amounts
 * as "USD 50,000.00"); unknown rules fall through to a generic format.
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

    public DiscrepancyReport assemble(
            String sessionId,
            LcDocument lc,
            InvoiceDocument inv,
            List<CheckResult> checks) {

        List<Discrepancy> discrepancies = new ArrayList<>();
        List<CheckResult> unableToVerify = new ArrayList<>();
        List<CheckResult> passed = new ArrayList<>();
        List<CheckResult> notApplicable = new ArrayList<>();

        for (CheckResult r : checks) {
            switch (r.status()) {
                case DISCREPANT -> discrepancies.add(toDiscrepancy(r, lc, inv));
                case PASS -> passed.add(r);
                case UNABLE_TO_VERIFY -> unableToVerify.add(r);
                case NOT_APPLICABLE -> notApplicable.add(r);
                case HUMAN_REVIEW -> unableToVerify.add(r); // V1 surfaces these as review items too
            }
        }

        Summary summary = new Summary(
                checks.size(),
                passed.size(),
                discrepancies.size(),
                unableToVerify.size(),
                notApplicable.size());

        return new DiscrepancyReport(
                sessionId,
                discrepancies.isEmpty(),
                discrepancies,
                unableToVerify,
                passed,
                notApplicable,
                summary);
    }

    // -----------------------------------------------------------------------
    // per-rule output shaping
    // -----------------------------------------------------------------------

    private Discrepancy toDiscrepancy(CheckResult r, LcDocument lc, InvoiceDocument inv) {
        return switch (r.ruleId()) {
            case "INV-011" -> amountDiscrepancy(r, lc, inv);
            case "INV-015" -> goodsDescriptionDiscrepancy(r, lc, inv);
            case "INV-007" -> lcNumberDiscrepancy(r, lc, inv);
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

    private Discrepancy genericDiscrepancy(CheckResult r) {
        String ruleRef = combineRefs(r.ucpRef(), r.isbpRef());
        return new Discrepancy(
                r.field() == null ? r.ruleId() : r.field(),
                r.lcValue(),
                r.presentedValue(),
                ruleRef,
                r.description());
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
