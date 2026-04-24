package com.lc.checker.parser;

import com.lc.checker.model.LcDocument;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stage 1 Part A — deterministic regex parser for SWIFT MT700 text.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Extract Block 4 from the SWIFT envelope ({@code {1:..}{2:..}{3:..}{4: ... -}}).
 *       Callers may also pass raw Block-4 body; we handle both transparently.</li>
 *   <li>Split Block 4 into tag-value pairs on the {@code \n:\d{2}[A-Z]?:} pattern,
 *       preserving continuation lines and stripping the leading {@code +} markers that
 *       MT700 uses to delimit sub-entries inside :46A:/:47A:.</li>
 *   <li>Normalise :32B: European-decimal amounts ("USD50000," → USD + 50000).</li>
 *   <li>Populate the scalar side of {@link LcDocument}; Part B (LLM) fills :45A:/:46A:/:47A:
 *       structured views. The raw bodies of those tags are preserved on the record
 *       so Part B has exact source text.</li>
 * </ol>
 *
 * <p>Throws {@link LcParseException} when a mandatory tag ({@value #MANDATORY_TAGS})
 * is absent or when :32B:/:31D: cannot be decoded.
 */
@Component
public class Mt700Parser {

    private static final Logger log = LoggerFactory.getLogger(Mt700Parser.class);

    /** Mandatory per test case + logic-flow.md intro. Order preserved for diagnostics. */
    public static final List<String> MANDATORY_TAGS = List.of("20", "31D", "32B", "45A", "50", "59");

    /** Extract Block 4. {@code (?s)} so dot matches newlines. Trailing "-" is optional. */
    private static final Pattern BLOCK_4 = Pattern.compile("(?s)\\{4:\\s*(.*?)(-)?}\\s*$");

    /** A tag token as it appears at line start: colon, 2 digits, optional letter, colon. */
    private static final Pattern TAG_LINE = Pattern.compile("^:(\\d{2}[A-Z]?):(.*)$");

    /** :32B: form: 3-letter ISO currency then amount (European decimal allowed). */
    private static final Pattern F32B = Pattern.compile("^([A-Z]{3})\\s*(.+)$");

    /** :39A: tolerance form: plus/minus integers (percent), e.g. "10/10". */
    private static final Pattern F39A = Pattern.compile("^\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*$");

    /** :31D: form: YYMMDD + place. */
    private static final Pattern F31D = Pattern.compile("^(\\d{6})(.*)$");

    /** :44C: latest shipment date YYMMDD. */
    private static final Pattern F44C = Pattern.compile("^\\s*(\\d{6}).*$");

    /** :31C: issue date YYMMDD. */
    private static final Pattern F31C = Pattern.compile("^\\s*(\\d{6}).*$");

    /** :48: presentation period — just digits (days). */
    private static final Pattern F48 = Pattern.compile("^\\s*(\\d+).*$");

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    public LcDocument parse(String mt700Text) {
        if (mt700Text == null || mt700Text.isBlank()) {
            throw new LcParseException("MT700 input is null or blank");
        }

        String block4 = extractBlock4(mt700Text);
        Map<String, String> raw = splitTags(block4);

        // Mandatory tags up-front so we fail fast and with the most specific field name.
        for (String tag : MANDATORY_TAGS) {
            if (!raw.containsKey(tag) || raw.get(tag).isBlank()) {
                throw new LcParseException(":" + tag + ":", "Mandatory MT700 field :" + tag + ": missing or empty");
            }
        }

        // --- Scalar field mapping -------------------------------------------
        String lcNumber = raw.get("20").trim();
        LocalDate issueDate = parseYyMmDd(raw.get("31C"), F31C, "31C");

        String f31d = raw.get("31D");
        Matcher m31 = F31D.matcher(f31d.trim());
        if (!m31.matches()) {
            throw new LcParseException(":31D:", "Cannot parse :31D: date/place from '" + f31d + "'");
        }
        LocalDate expiryDate = LocalDate.parse(m31.group(1), YYMMDD);
        String expiryPlace = m31.group(2).trim();

        String f32b = raw.get("32B");
        Matcher m32 = F32B.matcher(f32b.trim());
        if (!m32.matches()) {
            throw new LcParseException(":32B:", "Cannot parse :32B: currency/amount from '" + f32b + "'");
        }
        String currency = m32.group(1);
        BigDecimal amount = parseEuropeanDecimal(m32.group(2).trim(), "32B");

        int tolerancePlus = 0;
        int toleranceMinus = 0;
        if (raw.containsKey("39A")) {
            Matcher m39 = F39A.matcher(raw.get("39A"));
            if (m39.matches()) {
                tolerancePlus = Integer.parseInt(m39.group(1));
                toleranceMinus = Integer.parseInt(m39.group(2));
            } else {
                log.warn("Non-standard :39A: value '{}' — defaulting tolerance to 0/0", raw.get("39A"));
            }
        }

        // UCP 600 Art. 14(c) default: 21 days if :48: absent.
        int presentationDays = 21;
        if (raw.containsKey("48")) {
            Matcher m48 = F48.matcher(raw.get("48"));
            if (m48.matches()) {
                presentationDays = Integer.parseInt(m48.group(1));
            }
        }

        LocalDate latestShipmentDate = parseYyMmDd(raw.get("44C"), F44C, "44C");

        String[] applicant = splitNameAddress(raw.get("50"));
        String[] beneficiary = splitNameAddress(raw.get("59"));

        return new LcDocument(
                lcNumber,
                issueDate,
                expiryDate,
                expiryPlace,
                currency,
                amount,
                tolerancePlus,
                toleranceMinus,
                raw.get("39B"),
                raw.get("43P"),
                raw.get("43T"),
                raw.get("44A"),
                raw.get("44B"),
                latestShipmentDate,
                raw.get("44D"),
                raw.get("44E"),
                raw.get("44F"),
                presentationDays,
                raw.get("40E"),
                applicant[0],
                applicant[1],
                beneficiary[0],
                beneficiary[1],
                // Part-B raw preservation
                raw.get("45A"),
                raw.get("46A"),
                raw.get("47A"),
                // Part-B structured fields left null for Mt700LlmParser
                null,
                null,
                null,
                raw
        );
    }

    // -----------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------

    /**
     * Return the body of Block 4, or {@code raw} itself if no SWIFT envelope is present
     * (so the parser works with both full SWIFT messages and bare Block-4 bodies).
     */
    private String extractBlock4(String raw) {
        // Fast path: no envelope at all.
        if (!raw.contains("{4:")) {
            return raw;
        }
        Matcher m = BLOCK_4.matcher(raw);
        if (m.find()) {
            return m.group(1);
        }
        // Envelope present but un-matcheable — let the tag split surface the real error.
        log.warn("MT700 contains {{4:}} but no closing brace; parsing tail as-is");
        return raw;
    }

    /**
     * Split Block 4 into tag → value entries. Continuation lines (lines that don't start
     * with a new {@code :tag:}) are appended to the current value with a newline.
     * Leading {@code +} markers on sub-entry lines are preserved — we leave them in the
     * raw value because Part B (LLM) consumes the raw form and the marker itself is a
     * meaningful split hint for :46A:/:47A:.
     */
    private Map<String, String> splitTags(String block4) {
        Map<String, String> out = new LinkedHashMap<>();
        String current = null;
        StringBuilder buf = new StringBuilder();

        for (String rawLine : block4.split("\\r?\\n")) {
            String line = rawLine;
            // Strip trailing whitespace but preserve leading "+" or spaces (they matter for :46A:/:47A: grouping).
            while (line.endsWith(" ") || line.endsWith("\t")) {
                line = line.substring(0, line.length() - 1);
            }
            // Ignore the final "-" terminator line that SWIFT Block 4 closes with.
            if (line.equals("-") || line.isEmpty()) {
                continue;
            }

            Matcher m = TAG_LINE.matcher(line);
            if (m.matches()) {
                // Flush the previous tag.
                if (current != null) {
                    out.put(current, buf.toString());
                }
                current = m.group(1);
                buf = new StringBuilder(m.group(2));
            } else if (current != null) {
                if (buf.length() > 0) {
                    buf.append('\n');
                }
                buf.append(line);
            }
            // Lines appearing before any tag (unlikely) are silently dropped.
        }
        if (current != null) {
            out.put(current, buf.toString());
        }
        return out;
    }

    /**
     * Parse a SWIFT numeric with European decimal: trailing "," is the decimal separator
     * (e.g. "50000," → 50000.00, "123,45" → 123.45). Thousand separators are not used in
     * standard SWIFT numerics per ISO 15022.
     */
    private BigDecimal parseEuropeanDecimal(String s, String tag) {
        String cleaned = s.replace(" ", "");
        if (cleaned.endsWith(",")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        } else if (cleaned.contains(",")) {
            cleaned = cleaned.replace(',', '.');
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new LcParseException(":" + tag + ":", "Cannot parse amount '" + s + "' in tag :" + tag + ":");
        }
    }

    private LocalDate parseYyMmDd(String value, Pattern p, String tag) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher m = p.matcher(value);
        if (!m.matches()) {
            log.warn("Tag :{}: value '{}' did not match YYMMDD pattern; leaving null", tag, value);
            return null;
        }
        try {
            return LocalDate.parse(m.group(1), YYMMDD);
        } catch (Exception e) {
            log.warn("Tag :{}: value '{}' — YYMMDD parse failed: {}", tag, value, e.getMessage());
            return null;
        }
    }

    /**
     * Split a :50: / :59: value into (name, address). SWIFT convention: first line = name,
     * remaining lines = multi-line address.
     */
    private String[] splitNameAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[]{null, null};
        }
        String[] lines = raw.split("\\r?\\n", 2);
        String name = lines[0].trim();
        String address = lines.length > 1 ? lines[1].trim() : null;
        return new String[]{name, address};
    }

    /** Exposed for unit tests — keeps the mandatory set single-sourced. */
    static Set<String> mandatoryTags() {
        return Set.copyOf(MANDATORY_TAGS);
    }
}
