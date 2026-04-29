package com.lc.checker.domain.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Stateless coercion helpers shared by the LC parse stage
 * ({@code Mt700FieldCoercer}) and the invoice extract stage
 * ({@code InvoiceFieldMapper}).
 *
 * <p>Coercion is the boundary between "raw text from a tag value or LLM output"
 * and "typed Java value stored in {@link FieldEnvelope}". Centralising it here
 * means both sides agree on what counts as a blank, how to treat the literal
 * string {@code "null"}, and how to parse dates.
 *
 * <p>{@link #asDecimal} treats {@code ,} as a thousands separator — correct
 * for invoice values in en-US format, but NOT for SWIFT MT amounts, where
 * {@code ,} is the decimal separator. Callers parsing SWIFT format should
 * normalise the string themselves before calling {@link #asDecimal}, or use
 * a {@link BigDecimal} directly — see {@code Mt700FieldCoercer.AMOUNT_WITH_CURRENCY}.
 */
public final class FieldCoercion {

    private FieldCoercion() {}

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH));

    public static String asString(Object v) {
        if (v == null) return null;
        String s = v instanceof String str ? str.trim() : v.toString().trim();
        if (s.isEmpty()) return null;
        if (s.equalsIgnoreCase("null")) return null;
        if (s.equalsIgnoreCase("n/a") || s.equalsIgnoreCase("n.a.") || s.equals("—") || s.equals("–")) return null;
        return s;
    }

    public static BigDecimal asDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = asString(v);
        if (s == null) return null;
        String cleaned = s.replace(",", "").replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Integer asInteger(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        String s = asString(v);
        if (s == null) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static LocalDate asDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        String s = asString(v);
        if (s == null) return null;
        for (DateTimeFormatter fmt : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(s, fmt);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return null;
    }

    public static Boolean asBoolean(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase();
        return switch (s) {
            case "true", "yes", "y", "1", "signed" -> Boolean.TRUE;
            case "false", "no", "n", "0" -> Boolean.FALSE;
            default -> null;
        };
    }
}
