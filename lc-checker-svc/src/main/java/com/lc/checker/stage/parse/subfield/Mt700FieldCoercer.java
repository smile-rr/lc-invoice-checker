package com.lc.checker.stage.parse.subfield;

import com.lc.checker.domain.common.DocumentRequirement;
import com.lc.checker.domain.common.FieldCoercion;
import com.lc.checker.domain.common.ParserType;
import com.lc.checker.infra.fields.TagMapping;
import com.lc.checker.stage.parse.LcParseException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Splits and types a single MT700 tag value per its {@code lc-tag-mapping.yaml}
 * row. Replaces the inline {@code put(fields, key, rawValue)} loop in
 * {@code Mt700Parser} that wrote the same raw string under every declared
 * canonical key — see {@link ParserType} for the full vocabulary.
 *
 * <p>Per-parser behaviour:
 * <ul>
 *   <li>{@code AMOUNT_WITH_CURRENCY} — splits {@code USD60000,00} into
 *       {@code credit_currency=USD} (String) + {@code credit_amount=60000.00}
 *       (BigDecimal). The {@code ABOUT}/{@code APPROXIMATELY} prefix triggers
 *       a side-write of {@code about_credit_amount=true} (Boolean).</li>
 *   <li>{@code DATE_PLUS_TEXT} — splits {@code 260315SINGAPORE} into a typed
 *       {@code expiry_date=2026-03-15} + {@code expiry_place=SINGAPORE}.</li>
 *   <li>{@code SLASH_SEPARATED_INT} — splits {@code 5/5} into
 *       {@code tolerance_plus=5} + {@code tolerance_minus=5}.</li>
 *   <li>{@code MULTILINE_FIRST_LINE} — first non-blank line → name key,
 *       remaining lines joined → address key (handles {@code :50:} / {@code :59:}).</li>
 *   <li>{@code INCOTERMS_EXTRACT} — keeps {@code :45A:} verbatim under
 *       {@code goods_description} and writes the extracted Incoterm under
 *       {@code incoterms}.</li>
 * </ul>
 *
 * <p>SWIFT MT amount format note: the decimal separator is {@code ,} and there
 * is NO thousands separator. {@code USD60000,00} = sixty thousand. Don't try
 * to reuse {@link FieldCoercion#asDecimal} for this case — that helper treats
 * {@code ,} as a thousands separator (correct for en-US invoices, wrong here).
 */
@Component
public class Mt700FieldCoercer {

    private static final Pattern INT_BEFORE_SLASH = Pattern.compile("^\\s*(\\d+).*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final IncotermsExtractor incotermsExtractor;
    private final DocumentListParser documentListParser;

    public Mt700FieldCoercer(IncotermsExtractor incotermsExtractor,
                             DocumentListParser documentListParser) {
        this.incotermsExtractor = incotermsExtractor;
        this.documentListParser = documentListParser;
    }

    /**
     * Apply the YAML-declared parser to {@code rawValue} and return a small
     * map of canonical-key → typed value. Empty map when the value is
     * blank / produced no output.
     */
    public Map<String, Object> split(TagMapping mapping, String rawValue) {
        if (rawValue == null) return Map.of();
        ParserType parser = mapping.parser();
        List<String> keys = mapping.fieldKeys();
        Map<String, Object> out = new LinkedHashMap<>();

        switch (parser) {
            case SIMPLE_STRING -> {
                String s = rawValue.trim();
                if (!s.isEmpty()) putAll(out, keys, s);
            }
            case AMOUNT_WITH_CURRENCY -> coerceAmountWithCurrency(mapping, rawValue, keys, out);
            case DATE_YYMMDD -> {
                String s = rawValue.trim();
                if (s.length() < 6) {
                    throw new LcParseException(":" + mapping.tag() + ":",
                            "expected YYMMDD, got '" + rawValue + "'");
                }
                LocalDate d = parseSwiftDate(mapping.tag(), s.substring(0, 6));
                putAll(out, keys, d);
            }
            case DATE_PLUS_TEXT -> coerceDatePlusText(mapping, rawValue, keys, out);
            case SLASH_SEPARATED_INT -> coerceSlashSeparatedInt(mapping, rawValue, keys, out);
            case INT_BEFORE_SLASH -> {
                Matcher m = INT_BEFORE_SLASH.matcher(rawValue);
                if (m.matches()) {
                    Integer n = FieldCoercion.asInteger(m.group(1));
                    if (n != null) putAll(out, keys, n);
                }
            }
            case MULTILINE_FIRST_LINE -> coerceMultilineFirstLine(rawValue, keys, out);
            case MULTILINE_FULL -> {
                String s = rawValue.stripTrailing();
                if (!s.isBlank()) putAll(out, keys, s);
            }
            case BIC -> {
                String s = rawValue.trim().toUpperCase();
                if (!s.isEmpty()) putAll(out, keys, s);
            }
            case ENUM_NORMALIZED -> {
                String s = WHITESPACE.matcher(rawValue.trim().toUpperCase()).replaceAll(" ");
                if (!s.isEmpty()) putAll(out, keys, s);
            }
            case DOCUMENT_LIST -> {
                List<DocumentRequirement> docs = documentListParser.parse(rawValue);
                putAll(out, keys, docs);
            }
            case INCOTERMS_EXTRACT -> {
                // :45A: keeps the raw verbatim text (line breaks preserved) so
                // ISBP 821 C3 "corresponds with" comparisons see what the LC
                // actually said.
                if (!rawValue.isBlank()) {
                    String goodsKey = keys.get(0);
                    out.put(goodsKey, rawValue);
                    if (keys.size() >= 2) {
                        String inco = incotermsExtractor.extract(rawValue);
                        if (inco != null) out.put(keys.get(1), inco);
                    }
                }
            }
        }

        return out;
    }

    // ── Per-parser helpers ────────────────────────────────────────────────

    private static void coerceAmountWithCurrency(TagMapping mapping, String rawValue,
                                                 List<String> keys, Map<String, Object> out) {
        String value = rawValue.trim();
        boolean about = false;
        for (String prefix : List.of("ABOUT ", "APPROXIMATELY ")) {
            if (value.toUpperCase().startsWith(prefix)) {
                value = value.substring(prefix.length()).trim();
                about = true;
                break;
            }
        }
        if (value.length() < 4) {
            throw new LcParseException(":" + mapping.tag() + ":",
                    "amount-with-currency too short: '" + rawValue + "'");
        }
        String currency = value.substring(0, 3).toUpperCase();
        // SWIFT MT amount: comma is the decimal separator; no thousands separator.
        String amountText = value.substring(3).trim().replace(",", ".");
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountText);
        } catch (NumberFormatException e) {
            throw new LcParseException(":" + mapping.tag() + ":",
                    "could not parse SWIFT amount from '" + rawValue + "'");
        }
        if (keys.size() < 2) {
            throw new LcParseException(":" + mapping.tag() + ":",
                    "AMOUNT_WITH_CURRENCY needs two field_keys, got " + keys);
        }
        out.put(keys.get(0), currency);
        out.put(keys.get(1), amount);
        // Side-write derived flag (not in the YAML field_keys — see field-pool).
        out.put("about_credit_amount", about ? Boolean.TRUE : Boolean.FALSE);
    }

    private static void coerceDatePlusText(TagMapping mapping, String rawValue,
                                           List<String> keys, Map<String, Object> out) {
        String s = rawValue.trim();
        if (s.length() < 6) {
            throw new LcParseException(":" + mapping.tag() + ":",
                    "DATE_PLUS_TEXT too short: '" + rawValue + "'");
        }
        if (keys.size() < 2) {
            throw new LcParseException(":" + mapping.tag() + ":",
                    "DATE_PLUS_TEXT needs two field_keys, got " + keys);
        }
        LocalDate d = parseSwiftDate(mapping.tag(), s.substring(0, 6));
        out.put(keys.get(0), d);
        String tail = s.substring(6).trim();
        if (!tail.isEmpty()) out.put(keys.get(1), tail);
    }

    private static void coerceSlashSeparatedInt(TagMapping mapping, String rawValue,
                                                List<String> keys, Map<String, Object> out) {
        String[] parts = rawValue.trim().split("/", 2);
        if (parts.length < 2) {
            throw new LcParseException(":" + mapping.tag() + ":",
                    "SLASH_SEPARATED_INT needs A/B form, got '" + rawValue + "'");
        }
        if (keys.size() < 2) {
            throw new LcParseException(":" + mapping.tag() + ":",
                    "SLASH_SEPARATED_INT needs two field_keys, got " + keys);
        }
        Integer plus = FieldCoercion.asInteger(parts[0].trim());
        Integer minus = FieldCoercion.asInteger(parts[1].trim());
        if (plus != null) out.put(keys.get(0), plus);
        if (minus != null) out.put(keys.get(1), minus);
    }

    private static void coerceMultilineFirstLine(String rawValue, List<String> keys,
                                                 Map<String, Object> out) {
        String[] lines = rawValue.split("\\r?\\n");
        String first = null;
        int idx = 0;
        for (; idx < lines.length; idx++) {
            String t = lines[idx].trim();
            if (!t.isEmpty()) {
                first = t;
                break;
            }
        }
        if (first == null) return;
        out.put(keys.get(0), first);
        if (keys.size() >= 2) {
            StringBuilder addr = new StringBuilder();
            for (int j = idx + 1; j < lines.length; j++) {
                String t = lines[j].trim();
                if (t.isEmpty()) continue;
                if (addr.length() > 0) addr.append('\n');
                addr.append(t);
            }
            if (addr.length() > 0) out.put(keys.get(1), addr.toString());
        }
    }

    private static LocalDate parseSwiftDate(String tag, String yymmdd) {
        if (!yymmdd.matches("\\d{6}")) {
            throw new LcParseException(":" + tag + ":",
                    "expected YYMMDD digits, got '" + yymmdd + "'");
        }
        try {
            // SWIFT 2-digit years are interpreted as 20xx. The MT format is
            // ambiguous about the century but the live network has been on
            // 20xx since 2000; legacy 19xx LCs are not in scope.
            int yy = Integer.parseInt(yymmdd.substring(0, 2));
            int mm = Integer.parseInt(yymmdd.substring(2, 4));
            int dd = Integer.parseInt(yymmdd.substring(4, 6));
            return LocalDate.of(2000 + yy, mm, dd);
        } catch (DateTimeException e) {
            throw new LcParseException(":" + tag + ":",
                    "invalid SWIFT date '" + yymmdd + "': " + e.getMessage());
        }
    }

    private static void putAll(Map<String, Object> out, List<String> keys, Object value) {
        for (String key : keys) out.put(key, value);
    }
}
