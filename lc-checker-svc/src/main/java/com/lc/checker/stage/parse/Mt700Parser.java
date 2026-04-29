package com.lc.checker.stage.parse;

import com.lc.checker.domain.common.DocumentRequirement;
import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.ParseWarning;
import com.lc.checker.domain.common.ParsedRow;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.infra.fields.TagMappingRegistry;
import com.lc.checker.stage.parse.subfield.DocumentListParser;
import com.lc.checker.stage.parse.subfield.Mt700FieldCoercer;
import com.prowidesoftware.swift.io.parser.SwiftParser;
import com.prowidesoftware.swift.model.SwiftBlock1;
import com.prowidesoftware.swift.model.SwiftBlock2;
import com.prowidesoftware.swift.model.SwiftBlock3;
import com.prowidesoftware.swift.model.SwiftBlock4;
import com.prowidesoftware.swift.model.SwiftMessage;
import com.prowidesoftware.swift.model.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stage 1 Part A — SWIFT MT700 parser backed by Prowide Core.
 *
 * <p>Two outputs:
 * <ul>
 *   <li>{@link LcDocument} — typed facade; getters derive from the {@link FieldEnvelope}.</li>
 *   <li>{@link FieldEnvelope} — YAML-driven canonical map (primary contract for rules/UI).</li>
 * </ul>
 *
 * <p>Extension model: adding a new MT700 tag requires only a new row in
 * {@code lc-tag-mapping.yaml}. Zero Java changes for simple scalar tags.
 * Splitting + typing per the row's {@code parser:} {@link com.lc.checker.domain.common.ParserType}
 * is delegated to {@link Mt700FieldCoercer}; the parser itself is just a
 * dispatch loop.
 *
 * <p>Throws {@link LcParseException} only when the SWIFT envelope is structurally
 * unreadable or a value fails coercion. Missing mandatory tags are validated
 * upstream by {@code InputValidator}.
 */
@Component
public class Mt700Parser {

    private static final Logger log = LoggerFactory.getLogger(Mt700Parser.class);

    private final TagMappingRegistry tagMappings;
    private final DocumentListParser documentListParser;
    private final Mt700FieldCoercer fieldCoercer;
    private final ParsedRowProjector rowProjector;

    public Mt700Parser(TagMappingRegistry tagMappings,
                       DocumentListParser documentListParser,
                       Mt700FieldCoercer fieldCoercer,
                       ParsedRowProjector rowProjector) {
        this.tagMappings = tagMappings;
        this.documentListParser = documentListParser;
        this.fieldCoercer = fieldCoercer;
        this.rowProjector = rowProjector;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public LcDocument parse(String mt700Text) {
        if (mt700Text == null || mt700Text.isBlank()) {
            throw new LcParseException("MT700 input is null or blank");
        }

        SwiftMessage swift = parseSwiftMessage(mt700Text);
        SwiftBlock4 b4 = swift.getBlock4();
        if (b4 == null) {
            throw new LcParseException(":4:", "MT700 has no Block 4 (tag section)");
        }

        // ── Raw tag capture — feeds YAML registry loop ────────────────────────────
        Map<String, String> raw = new LinkedHashMap<>();
        for (Tag t : b4.getTags()) {
            raw.put(t.getName(), t.getValue());
        }

        Map<String, String> header = new LinkedHashMap<>();
        SwiftBlock3 b3 = swift.getBlock3();
        if (b3 != null) {
            for (Tag t : b3.getTags()) header.put(t.getName(), t.getValue());
        }

        applyTagValidation(raw);

        // :46A: structured document list — kept as a typed sibling of the envelope
        // value so LcDocument can expose it without re-parsing.
        List<DocumentRequirement> documentsRequired = documentListParser.parse(raw.get("46A"));

        // ── Log unregistered tags ────────────────────────────────────────────────
        LinkedHashSet<String> unregistered = new LinkedHashSet<>();
        for (String tag : raw.keySet()) {
            if (tagMappings.byTag(tag).isEmpty()) unregistered.add(tag);
        }
        if (!unregistered.isEmpty()) {
            log.info("Mt700Parser: tags not in lc-tag-mapping.yaml: {}", unregistered);
        }

        // ── Build canonical envelope ────────────────────────────────────────────
        // YAML registry + Mt700FieldCoercer drive everything: each tag's parser:
        // ParserType decides how the raw value is split + coerced into the
        // canonical keys declared in field_keys.
        FieldEnvelope envelope = buildEnvelope(
                raw, header,
                swift.getBlock1(), swift.getBlock2());

        // ── Display rows (computed from envelope) ───────────────────────────────
        List<ParsedRow> parsedRows = rowProjector.project(envelope);

        return LcDocument.fromScalars(raw, header, envelope, documentsRequired, parsedRows);
    }

    // ── Envelope builder ────────────────────────────────────────────────────────

    /**
     * Build the canonical-key {@link FieldEnvelope} by dispatching every raw
     * tag value through {@link Mt700FieldCoercer#split}. The YAML row's
     * {@code parser:} decides how the value is split and typed:
     * {@code :32B: USD60000,00} becomes {@code credit_currency=USD} (String)
     * + {@code credit_amount=60000.00} (BigDecimal) + a derived
     * {@code about_credit_amount} flag. Adding a new tag requires only a
     * YAML row.
     */
    private FieldEnvelope buildEnvelope(Map<String, String> raw,
                                        Map<String, String> header,
                                        SwiftBlock1 block1,
                                        SwiftBlock2 block2) {
        Map<String, Object> fields  = new LinkedHashMap<>();
        Map<String, Object> extras = new LinkedHashMap<>();
        List<ParseWarning> warnings = new ArrayList<>();

        // Seed YAML-declared defaults first (e.g. presentation_days=21, tolerance=0).
        // Defaults are typed values from the YAML literal — Integer 0, not "0".
        for (var mapping : tagMappings.all()) {
            for (var def : mapping.defaults().entrySet()) {
                fields.putIfAbsent(def.getKey(), def.getValue());
            }
        }

        // ── Main loop — coercer turns each raw tag into typed envelope entries ──
        for (var entry : raw.entrySet()) {
            String tag = entry.getKey();
            String rawValue = entry.getValue();

            var mappingOpt = tagMappings.byTag(tag);
            if (mappingOpt.isEmpty()) {
                extras.put(tag, rawValue);
                warnings.add(new ParseWarning("UNKNOWN_TAG", tag,
                        "Tag :" + tag + ": is not registered in lc-tag-mapping.yaml"));
                continue;
            }

            Map<String, Object> coerced = fieldCoercer.split(mappingOpt.get(), rawValue);
            for (var ce : coerced.entrySet()) {
                put(fields, ce.getKey(), ce.getValue());
            }
        }

        // ── SWIFT envelope (Blocks 1/2/3) ─────────────────────────────────────
        if (block1 != null) {
            put(fields, "sender_bic", toBic11(block1.getLogicalTerminal()));
        }
        if (block2 != null) {
            String b2 = block2.getValue();
            if (b2 != null && b2.length() >= 4) {
                put(fields, "message_type", b2.substring(1, 4));
                if (b2.length() >= 16) put(fields, "receiver_bic", toBic11(b2.substring(4, 16)));
            }
        }
        String userRef = header.get("108");
        if (userRef != null) put(fields, "user_reference", userRef.trim());

        return new FieldEnvelope("LC", fields, extras, raw, warnings);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Tag validation from {@code lc-tag-mapping.yaml} {@code validation:} blocks.
     * Runs on the raw tag value before any sub-parsing.
     */
    private void applyTagValidation(Map<String, String> raw) {
        for (var entry : raw.entrySet()) {
            String tag = entry.getKey();
            String value = entry.getValue();
            var mapping = tagMappings.byTag(tag).orElse(null);
            if (mapping == null || mapping.validation() == null) continue;
            var v = mapping.validation();
            String trimmed = value == null ? "" : value.trim();
            if (v.minLength() != null && trimmed.length() < v.minLength()) {
                throw new LcParseException(":" + tag + ":",
                        "Tag :" + tag + ": length " + trimmed.length() + " < min " + v.minLength());
            }
            if (v.maxLength() != null && trimmed.length() > v.maxLength()) {
                throw new LcParseException(":" + tag + ":",
                        "Tag :" + tag + ": length " + trimmed.length() + " > max " + v.maxLength());
            }
            if (v.pattern() != null && !v.pattern().isBlank()
                    && !Pattern.compile(v.pattern()).matcher(trimmed).matches()) {
                throw new LcParseException(":" + tag + ":",
                        "Tag :" + tag + ": value does not match pattern " + v.pattern());
            }
        }
    }

    private SwiftMessage parseSwiftMessage(String fin) {
        try {
            SwiftMessage swift = new SwiftParser(ensureEnvelope(fin)).message();
            if (swift == null) throw new LcParseException("SWIFT parser returned null");
            return swift;
        } catch (LcParseException e) {
            throw e;
        } catch (Exception e) {
            throw new LcParseException("Failed to parse SWIFT message: " + e.getMessage(), e);
        }
    }

    private String ensureEnvelope(String fin) {
        String t = fin.trim();
        if (t.contains("{1:") || t.contains("{4:")) return t;
        if (!t.endsWith("-")) t = t + "\n-";
        return "{1:F01BANKBICAAXXX0000000000}{2:I700BANKBICBXXXXN}{4:\n" + t + "}";
    }

    private static void put(Map<String, Object> fields, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        fields.put(key, value);
    }

    private static String toBic11(String lt) {
        if (lt == null || lt.length() != 12) return lt;
        return lt.substring(0, 8) + lt.substring(9, 12);
    }

    Set<String> mandatoryTags() {
        return Set.copyOf(tagMappings.mandatoryTags());
    }
}
