package com.lc.checker.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Numeric tools for Tier-3 rules. Task-shaped: each method covers one rule's
 * deterministic core in a single call so the LLM does not have to chain
 * primitives ({@code multiply} → {@code subtract} → {@code abs} → {@code compare}).
 *
 * <p>Bound to rules via {@code rules/catalog.yml}:
 * <ul>
 *   <li>{@code verify_arithmetic} — UCP 18(b) invoice math (qty × price ≈ total)</li>
 *   <li>{@code check_within_tolerance} — UCP 18(b) amount-vs-tolerance, UCP 30(a)/(b)</li>
 * </ul>
 */
@Component
public class ArithmeticTools {

    /**
     * Verify that {@code quantity × unit_price ≈ total_amount} within {@code epsilon}.
     * Returns the computed product and the absolute difference so the agent can
     * record the equation it used in {@code equation_used}.
     *
     * <p>Use this for UCP 600 Art. 18(b) invoice arithmetic consistency. If the
     * invoice has multiple line items or separately-stated freight/insurance
     * (CIF), pass the relevant header values you read; do NOT invent values.
     */
    @Tool(name = "verify_arithmetic",
          description = """
              Verify that quantity × unit_price equals total_amount within a small
              rounding tolerance. Returns {match, computed, diff}. Use for invoice
              math reconciliation under UCP 600 Art. 18(b). Pass the header values
              you actually read from the invoice; if the invoice has multiple line
              items or separate charges, return DOUBTS rather than guess inputs.""")
    public ArithmeticResult verifyArithmetic(
            @ToolParam(description = "Quantity from the invoice (decimal)") BigDecimal quantity,
            @ToolParam(description = "Unit price from the invoice (decimal, in invoice currency)") BigDecimal unitPrice,
            @ToolParam(description = "Total amount from the invoice (decimal, in invoice currency)") BigDecimal totalAmount,
            @ToolParam(description = "Rounding tolerance (e.g. 0.01 for two-decimal currency)", required = false)
            BigDecimal epsilon) {
        if (quantity == null || unitPrice == null || totalAmount == null) {
            return new ArithmeticResult(false,
                    null, null,
                    "missing input: one of quantity / unit_price / total_amount is null");
        }
        BigDecimal eps = epsilon == null ? new BigDecimal("0.01") : epsilon.abs();
        BigDecimal computed = quantity.multiply(unitPrice).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
        BigDecimal diff = computed.subtract(totalAmount).abs();
        boolean match = diff.compareTo(eps) <= 0;
        return new ArithmeticResult(match, computed, diff, null);
    }

    /**
     * Check whether {@code value} falls within the tolerance band derived from
     * {@code base × (1 ± plus_pct/100)} / {@code base × (1 − minus_pct/100)}.
     *
     * <p>Use for UCP 600 Art. 18(b) amount limit, Art. 30(a) "about/approximately"
     * (10%), Art. 30(b) bulk-goods (5%). The agent decides which percentages
     * apply per LC field 39A / 39B / silent-default; this tool only does the
     * band math and verdict.
     */
    @Tool(name = "check_within_tolerance",
          description = """
              Compute the tolerance band base × (1 + plus_pct/100) ... base × (1 − minus_pct/100)
              and return whether `value` is WITHIN, ABOVE, or BELOW it, plus the min and max bounds.
              Use for UCP 600 Art. 18(b) amount limit and Art. 30(a)/(b) tolerance rules.
              The agent must decide which tolerance applies (39A explicit / 39B "NOT EXCEEDING" /
              30(b) default 5%) before calling this tool.""")
    public ToleranceResult checkWithinTolerance(
            @ToolParam(description = "Value to check (e.g. invoice total amount)") BigDecimal value,
            @ToolParam(description = "Base value (e.g. LC amount from :32B:)") BigDecimal base,
            @ToolParam(description = "Plus tolerance percentage (e.g. 5 for +5%, 0 if NOT EXCEEDING)") BigDecimal plusPct,
            @ToolParam(description = "Minus tolerance percentage (e.g. 5 for −5%, 0 if no underdraw allowance)") BigDecimal minusPct) {
        if (value == null || base == null) {
            return new ToleranceResult("DOUBTS", null, null,
                    "missing input: value or base is null");
        }
        BigDecimal plus = plusPct == null ? BigDecimal.ZERO : plusPct;
        BigDecimal minus = minusPct == null ? BigDecimal.ZERO : minusPct;
        BigDecimal hundred = new BigDecimal("100");
        BigDecimal max = base.multiply(BigDecimal.ONE.add(plus.divide(hundred, 10, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal min = base.multiply(BigDecimal.ONE.subtract(minus.divide(hundred, 10, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal v = value.setScale(2, RoundingMode.HALF_UP);
        String status;
        if (v.compareTo(max) > 0) status = "ABOVE";
        else if (v.compareTo(min) < 0) status = "BELOW";
        else status = "WITHIN";
        return new ToleranceResult(status, min, max, null);
    }

    /** Tool result for {@link #verifyArithmetic}. */
    public record ArithmeticResult(boolean match, BigDecimal computed, BigDecimal diff, String error) {}

    /** Tool result for {@link #checkWithinTolerance}. */
    public record ToleranceResult(String status, BigDecimal min, BigDecimal max, String error) {}
}
