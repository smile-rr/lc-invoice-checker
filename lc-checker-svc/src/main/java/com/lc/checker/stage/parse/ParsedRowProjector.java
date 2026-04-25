package com.lc.checker.stage.parse;

import com.lc.checker.domain.common.DocumentRequirement;
import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.FieldType;
import com.lc.checker.domain.common.ParsedRow;
import com.lc.checker.infra.fields.FieldDefinition;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link FieldEnvelope} into the display-ready row list the UI consumes.
 *
 * <p>This is the single point where presentation decisions live: which canonical
 * field is "primary" for a given source tag, how each value is formatted for
 * humans, what becomes a subline. The frontend reads {@code parsedRows} and
 * renders 1:1 — no grouping, no formatting, no derived-field heuristics there.
 */
@Component
public class ParsedRowProjector {

    /** Type priority — bigger = stronger candidate for the primary slot of a row. */
    private static final Map<FieldType, Integer> TYPE_RANK = Map.of(
            FieldType.MULTILINE_TEXT, 100,
            FieldType.DOCUMENT_LIST, 95,
            FieldType.TABLE, 92,
            FieldType.AMOUNT, 90,
            FieldType.DATE, 80,
            FieldType.ENUM, 70,
            FieldType.STRING, 60,
            FieldType.INTEGER, 50,
            FieldType.CURRENCY_CODE, 40);

    private final FieldPoolRegistry registry;

    public ParsedRowProjector(FieldPoolRegistry registry) {
        this.registry = registry;
    }

    public List<ParsedRow> project(FieldEnvelope envelope) {
        if (envelope == null) return List.of();
        Map<String, Object> values = envelope.fields();
        Map<String, String> rawSource = envelope.rawSource();

        // Group field definitions by their FIRST source_tag (the one a user
        // would point to). A field with multiple source_tags (e.g. 50/50B/50F)
        // collapses under whichever the message actually used; if all are
        // present, pick the first declared.
        Map<String, List<FieldDefinition>> byTag = new LinkedHashMap<>();
        for (FieldDefinition def : registry.appliesToLc()) {
            if (def.sourceTags().isEmpty()) continue;
            String anchor = pickAnchorTag(def, rawSource);
            byTag.computeIfAbsent(anchor, k -> new ArrayList<>()).add(def);
        }

        List<ParsedRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<FieldDefinition>> entry : byTag.entrySet()) {
            String tag = entry.getKey();
            List<FieldDefinition> defs = entry.getValue();
            ParsedRow row = projectRow(tag, defs, values, rawSource);
            if (row != null) rows.add(row);
        }

        // Stable order: envelope blocks first, then body tags alphanumerically by tag string.
        rows.sort(Comparator.comparing(ParsedRow::sortKey));
        return List.copyOf(rows);
    }

    private String pickAnchorTag(FieldDefinition def, Map<String, String> rawSource) {
        for (String t : def.sourceTags()) {
            if (rawSource.containsKey(t)) return t;
        }
        // None of the tag variants present — anchor on the first declared tag
        // (used for envelope-block synthetics that have no rawSource entry).
        return def.sourceTags().get(0);
    }

    private ParsedRow projectRow(String tag,
                                 List<FieldDefinition> defs,
                                 Map<String, Object> values,
                                 Map<String, String> rawSource) {
        // Pick primary by type rank; ties broken by declaration order (registry list).
        FieldDefinition primary = defs.stream()
                .filter(d -> values.containsKey(d.key()) || isDocumentList(d, rawSource, tag))
                .max(Comparator.comparingInt(d -> TYPE_RANK.getOrDefault(d.type(), 0)))
                .orElse(null);
        if (primary == null) return null;

        Object rawValue = values.get(primary.key());
        String displayValue = formatValue(primary, rawValue, rawSource.get(tag));

        List<ParsedRow.Subline> sublines = new ArrayList<>();
        for (FieldDefinition d : defs) {
            if (d == primary) continue;
            Object v = values.get(d.key());
            if (v == null) continue;
            String formatted = formatValue(d, v, null);
            if (formatted == null || formatted.isBlank()) continue;
            sublines.add(new ParsedRow.Subline(d.nameEn() != null ? d.nameEn() : d.key(), formatted));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        if (primary.type() == FieldType.DOCUMENT_LIST && rawValue instanceof List<?> list) {
            meta.put("parsed_count", list.size());
            meta.put("doc_types", summariseDocList(list));
        }
        if (rawValue instanceof BigDecimal || rawValue instanceof Number) {
            meta.put("raw_value", String.valueOf(rawValue));
        }

        return new ParsedRow(
                tag,
                primary.group(),
                primary.nameEn() != null ? primary.nameEn() : primary.key(),
                displayValue,
                sublines,
                meta,
                buildSortKey(tag)
        );
    }

    private boolean isDocumentList(FieldDefinition def, Map<String, String> rawSource, String tag) {
        // DOCUMENT_LIST is "present" if either the parsed list landed in fields OR
        // the raw text is in rawSource — the projector falls back to raw text either way.
        return def.type() == FieldType.DOCUMENT_LIST && rawSource.containsKey(tag);
    }

    private String formatValue(FieldDefinition def, Object value, String rawFallback) {
        if (def.type() == FieldType.DOCUMENT_LIST) {
            // Always prefer the raw source text — it's what the human reads in the source pane.
            if (rawFallback != null && !rawFallback.isBlank()) return rawFallback;
            if (value instanceof List<?> list) {
                StringBuilder sb = new StringBuilder();
                for (Object item : list) {
                    if (item instanceof DocumentRequirement dr) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append("• ").append(dr.rawText());
                    }
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            return null;
        }
        if (value == null) return null;
        return switch (def.type()) {
            case AMOUNT -> formatAmount(value);
            case INTEGER -> String.valueOf(value);
            case DATE -> value.toString();   // LocalDate#toString → ISO yyyy-MM-dd
            case MULTILINE_TEXT, STRING, ENUM, CURRENCY_CODE -> {
                String s = value.toString().trim();
                yield s.isEmpty() ? null : s;
            }
            default -> value.toString();
        };
    }

    private String formatAmount(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).toPlainString();
        }
        return value.toString();
    }

    private List<String> summariseDocList(List<?> list) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Object item : list) {
            if (item instanceof DocumentRequirement dr && dr.type() != null) {
                String key = dr.type().name().toLowerCase(Locale.ROOT);
                counts.merge(key, 1, Integer::sum);
            }
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            out.add(e.getValue() + " " + e.getKey().replace('_', ' '));
        }
        return out;
    }

    /**
     * Sort key for the UI's "sort by tag" mode. Envelope blocks sort before body
     * tags (so {@code block1} → {@code block2} → {@code block3:108} → {@code 20}).
     */
    private String buildSortKey(String tag) {
        if (tag == null) return "z_unknown";
        if (tag.startsWith("block")) return "0_" + tag;
        return "1_" + tag;
    }
}
