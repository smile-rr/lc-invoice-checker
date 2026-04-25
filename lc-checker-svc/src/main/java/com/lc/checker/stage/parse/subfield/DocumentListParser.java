package com.lc.checker.stage.parse.subfield;

import com.lc.checker.domain.common.DocType;
import com.lc.checker.domain.common.DocumentRequirement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Splits :46A: into one structured {@link DocumentRequirement} per "+"-prefixed
 * bullet, then mines each block for the well-known UCP/ISBP cues.
 *
 * <p>The parser is deliberately defensive: anything it cannot categorise lands
 * as {@link DocType#OTHER} with the original text on {@code rawText}, so rule
 * writers always have an escape hatch.
 */
@Component
public class DocumentListParser {

    private static final Pattern WORD_NUMBER = Pattern.compile(
            "(?i)\\b(ONE|TWO|THREE|FOUR|FIVE|SIX|SEVEN|EIGHT|NINE|TEN|TRIPLICATE|DUPLICATE|QUADRUPLICATE)\\b");
    private static final Pattern DIGIT_NUMBER = Pattern.compile("(\\d+)\\s*(?:/\\d+\\s+)?(?:ORIGINAL|COPY|COPIES|ORIGINALS)");
    private static final Pattern CONSIGNEE = Pattern.compile(
            "(?i)MADE\\s+OUT\\s+TO\\s+ORDER\\s+OF\\s+([A-Z0-9 ,.&'-]+?)(?=\\s+(?:MARKED|NOTIFY|FREIGHT)|$)");
    private static final Pattern NOTIFY = Pattern.compile(
            "(?i)NOTIFY\\s+([A-Z0-9 ,.&'-]+?)(?=\\s+(?:MARKED|FREIGHT)|$)");
    private static final Pattern FREIGHT = Pattern.compile(
            "(?i)FREIGHT\\s+(PREPAID|COLLECT)");
    private static final Pattern ISSUED_BY = Pattern.compile(
            "(?i)ISSUED\\s+BY\\s+([A-Z0-9 ,.&'-]+?)(?=\\s+(?:IN\\s+|AND|$))");

    private static final Map<String, Integer> WORD_TO_INT;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("ONE", 1);
        m.put("TWO", 2);
        m.put("THREE", 3);
        m.put("FOUR", 4);
        m.put("FIVE", 5);
        m.put("SIX", 6);
        m.put("SEVEN", 7);
        m.put("EIGHT", 8);
        m.put("NINE", 9);
        m.put("TEN", 10);
        m.put("TRIPLICATE", 3);
        m.put("DUPLICATE", 2);
        m.put("QUADRUPLICATE", 4);
        WORD_TO_INT = Map.copyOf(m);
    }

    /** Parse :46A: raw text into a list of structured requirements. */
    public List<DocumentRequirement> parse(String raw46A) {
        if (raw46A == null || raw46A.isBlank()) return List.of();

        // Each requirement is delimited by a leading '+' (continuation lines belong to it).
        // Some senders omit the '+' and just newline-separate; we tolerate both.
        List<String> blocks = splitBlocks(raw46A);
        List<DocumentRequirement> out = new ArrayList<>(blocks.size());
        for (String block : blocks) {
            out.add(parseBlock(block));
        }
        return List.copyOf(out);
    }

    private List<String> splitBlocks(String raw) {
        String normalised = raw.replace("\r\n", "\n").trim();
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : normalised.split("\n")) {
            String l = line.trim();
            if (l.startsWith("+")) {
                if (current.length() > 0) {
                    blocks.add(current.toString().trim());
                    current.setLength(0);
                }
                current.append(l.substring(1).trim());
            } else {
                if (current.length() > 0) current.append(' ');
                current.append(l);
            }
        }
        if (current.length() > 0) blocks.add(current.toString().trim());
        // If no '+' delimiter was used, fall back to one-block-per-line (sender quirk).
        if (blocks.size() == 1 && raw.contains("\n") && !raw.contains("+")) {
            blocks.clear();
            for (String l : normalised.split("\n")) {
                String t = l.trim();
                if (!t.isEmpty()) blocks.add(t);
            }
        }
        return blocks;
    }

    private DocumentRequirement parseBlock(String block) {
        String upper = block.toUpperCase();

        DocType type = classify(upper);

        Integer originals = matchCount(upper, "ORIGINAL");
        Integer copies = matchCount(upper, "COP");
        if (originals == null && copies == null) {
            // Patterns like "IN TRIPLICATE" / "IN DUPLICATE" — unqualified count = originals.
            Integer maybe = wordNumber(upper);
            if (maybe != null) {
                originals = maybe;
            }
        }

        boolean signed = upper.contains("SIGNED");
        boolean fullSet = upper.contains("FULL SET");
        boolean onBoard = upper.contains("ON BOARD") || upper.contains("ONBOARD");

        String consignee = firstGroup(CONSIGNEE.matcher(upper));
        String notify = firstGroup(NOTIFY.matcher(upper));
        String issuedBy = firstGroup(ISSUED_BY.matcher(upper));
        String freight = null;
        Matcher fm = FREIGHT.matcher(upper);
        if (fm.find()) freight = fm.group(1).toUpperCase();

        return new DocumentRequirement(
                type,
                originals,
                copies,
                signed,
                fullSet,
                onBoard,
                consignee,
                freight,
                notify,
                issuedBy,
                block
        );
    }

    private DocType classify(String upper) {
        if (upper.contains("BILL OF LADING") || upper.contains("BILLS OF LADING") || upper.contains("B/L"))
            return DocType.BILL_OF_LADING;
        if (upper.contains("AIRWAY BILL") || upper.contains("AIR WAYBILL") || upper.contains("AWB"))
            return DocType.AIRWAY_BILL;
        if (upper.contains("CERTIFICATE OF ORIGIN") || upper.contains("CERT OF ORIGIN") || upper.contains("ORIGIN CERT"))
            return DocType.CERT_OF_ORIGIN;
        if (upper.contains("PACKING LIST")) return DocType.PACKING_LIST;
        if (upper.contains("INSURANCE")) return DocType.INSURANCE_CERT;
        if (upper.contains("INSPECTION")) return DocType.INSPECTION_CERT;
        if (upper.contains("WEIGHT LIST") || upper.contains("WEIGHT CERT")) return DocType.WEIGHT_LIST;
        if (upper.contains("PHYTOSANITARY")) return DocType.PHYTOSANITARY_CERT;
        if (upper.contains("COMMERCIAL INVOICE") || upper.contains("INVOICE")) return DocType.COMMERCIAL_INVOICE;
        return DocType.OTHER;
    }

    private Integer matchCount(String upper, String unitToken) {
        // Look for digit forms first ("3 ORIGINALS", "2 COPIES").
        Matcher m = DIGIT_NUMBER.matcher(upper);
        while (m.find()) {
            String unit = m.group(0).toUpperCase();
            if (unit.contains(unitToken)) {
                try {
                    return Integer.valueOf(m.group(1));
                } catch (NumberFormatException ignored) { /* skip */ }
            }
        }
        // Word forms: "TWO COPIES", "ONE ORIGINAL".
        Matcher w = WORD_NUMBER.matcher(upper);
        while (w.find()) {
            int idx = w.end();
            String tail = idx + 30 <= upper.length() ? upper.substring(idx, idx + 30) : upper.substring(idx);
            if (tail.contains(unitToken)) {
                Integer n = WORD_TO_INT.get(w.group(1).toUpperCase());
                if (n != null) return n;
            }
        }
        return null;
    }

    private Integer wordNumber(String upper) {
        Matcher w = WORD_NUMBER.matcher(upper);
        if (w.find()) return WORD_TO_INT.get(w.group(1).toUpperCase());
        return null;
    }

    private String firstGroup(Matcher m) {
        if (m.find()) {
            String g = m.group(1).trim();
            return g.isEmpty() ? null : g;
        }
        return null;
    }
}
