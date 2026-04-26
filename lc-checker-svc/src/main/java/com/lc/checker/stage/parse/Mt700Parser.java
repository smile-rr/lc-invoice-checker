package com.lc.checker.stage.parse;

import com.lc.checker.domain.common.DocumentRequirement;
import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.ParseWarning;
import com.lc.checker.domain.common.ParsedRow;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.infra.fields.TagMappingRegistry;
import com.lc.checker.stage.parse.subfield.DocumentListParser;
import com.lc.checker.stage.parse.subfield.IncotermsExtractor;
import com.prowidesoftware.swift.io.parser.SwiftParser;
import com.prowidesoftware.swift.model.SwiftBlock1;
import com.prowidesoftware.swift.model.SwiftBlock2;
import com.prowidesoftware.swift.model.SwiftBlock3;
import com.prowidesoftware.swift.model.SwiftBlock4;
import com.prowidesoftware.swift.model.SwiftMessage;
import com.prowidesoftware.swift.model.Tag;
import com.prowidesoftware.swift.model.field.Field20;
import com.prowidesoftware.swift.model.field.Field31C;
import com.prowidesoftware.swift.model.field.Field31D;
import com.prowidesoftware.swift.model.field.Field32B;
import com.prowidesoftware.swift.model.field.Field39A;
import com.prowidesoftware.swift.model.field.Field44C;
import com.prowidesoftware.swift.model.field.Field48;
import com.prowidesoftware.swift.model.mt.mt7xx.MT700;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stage 1 Part A — SWIFT MT700 parser backed by Prowide Core
 * ({@code com.prowidesoftware:pw-swift-core}).
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Accept either a full SWIFT envelope ({@code {1:}{2:}{3:}{4:...-}{5:}}) or a bare
 *       Block-4 body. Prowide's {@link SwiftParser} handles both.</li>
 *   <li>Capture every Block-4 tag verbatim in {@code rawFields} (keys include the option
 *       letter, e.g. {@code "50F"}, {@code "71B"}). No whitelist — any new or SRU-added
 *       tag is reachable from downstream rules via {@code lc.rawFields().get("…")}.</li>
 *   <li>Populate the typed scalar side of {@link LcDocument} using Prowide's {@code Field*}
 *       classes. Free-text bodies for :45A:/:46A:/:47A: are preserved verbatim so Part B
 *       (LLM) has exact source text.</li>
 *   <li>Log (at INFO) any Block-4 tag we did not bind to a typed {@code LcDocument}
 *       field so SRU drift or unusual LC shapes surface in ops logs.</li>
 * </ol>
 *
 * <p>Throws {@link LcParseException} only when the SWIFT envelope itself is
 * structurally unreadable — missing mandatory tags are NOT a parser concern;
 * {@code InputValidator} runs that check upstream and rejects with a clean
 * 400 before any session is created. Missing optional fields parse as
 * {@code null}; rules that need them produce {@link CheckStatus#DOUBTS}.
 */
@Component
public class Mt700Parser {

    private static final Logger log = LoggerFactory.getLogger(Mt700Parser.class);

    /**
     * Business-mandatory tags consumed by {@code InputValidator} as the
     * pre-pipeline gate. Kept here so the validator and parser share a
     * single source of truth for which tags must exist on the wire.
     */
    public static final List<String> MANDATORY_TAGS = List.of("20", "31D", "32B", "45A", "50", "59");

    /** All :50: option letters accepted as the "applicant" party tag. */
    private static final Set<String> APPLICANT_TAGS = Set.of("50", "50B", "50F");

    /** All :59: option letters accepted as the "beneficiary" party tag. */
    private static final Set<String> BENEFICIARY_TAGS = Set.of("59", "59A", "59F");

    /** :48: presentation-period leading digits (e.g. "21" or "21/DAYS"). */
    private static final Pattern F48_DIGITS = Pattern.compile("^\\s*(\\d+).*$");

    private final TagMappingRegistry tagMappings;
    private final DocumentListParser documentListParser;
    private final IncotermsExtractor incotermsExtractor;
    private final ParsedRowProjector rowProjector;

    public Mt700Parser(TagMappingRegistry tagMappings,
                       DocumentListParser documentListParser,
                       IncotermsExtractor incotermsExtractor,
                       ParsedRowProjector rowProjector) {
        this.tagMappings = tagMappings;
        this.documentListParser = documentListParser;
        this.incotermsExtractor = incotermsExtractor;
        this.rowProjector = rowProjector;
    }

    public LcDocument parse(String mt700Text) {
        if (mt700Text == null || mt700Text.isBlank()) {
            throw new LcParseException("MT700 input is null or blank");
        }

        // Prowide accepts either a full SWIFT envelope or bare Block-4; be explicit
        // in how we convert because MT700.parse(String) requires the envelope form.
        SwiftMessage swift = parseSwiftMessage(mt700Text);
        MT700 mt = new MT700(swift);

        SwiftBlock4 b4 = swift.getBlock4();
        if (b4 == null) {
            throw new LcParseException(":4:", "MT700 has no Block 4 (tag section)");
        }

        Map<String, String> raw = new LinkedHashMap<>();
        for (Tag t : b4.getTags()) {
            raw.put(t.getName(), t.getValue());
        }

        Map<String, String> header = new LinkedHashMap<>();
        SwiftBlock3 b3 = swift.getBlock3();
        if (b3 != null) {
            for (Tag t : b3.getTags()) {
                header.put(t.getName(), t.getValue());
            }
        }

        // No mandatory-tag check here — InputValidator handles that pre-pipeline.
        // The parser's job is to extract whatever's present; missing fields
        // become null and surface downstream as DOUBTS verdicts on rules that
        // need them.

        // --- Typed scalar extraction via Prowide Field* --------------------

        Field20 f20 = mt.getField20();
        String lcNumber = (f20 == null || f20.getValue() == null) ? null : f20.getValue().trim();
        if (lcNumber != null && lcNumber.isBlank()) lcNumber = null;

        LocalDate issueDate = toLocalDate(mt.getField31C());
        Field31D f31d = mt.getField31D();
        LocalDate expiryDate = f31d == null ? null : toLocalDate(f31d);
        if (f31d != null && expiryDate == null) {
            log.warn("Cannot parse :31D: date from '{}' — leaving expiryDate=null", f31d.getValue());
        }
        String expiryPlace = f31d == null ? null : safe(f31d.getPlace());

        Field32B f32b = mt.getField32B();
        String currency = f32b == null ? null : safe(f32b.getCurrency());
        if (currency != null && currency.isBlank()) currency = null;
        BigDecimal amount = f32b == null ? null : f32b.getAmountAsBigDecimal();
        if (f32b != null && (currency == null || amount == null)) {
            log.warn("Partial :32B: parse — currency={}, amount={}, raw='{}'",
                    currency, amount, f32b.getValue());
        }

        int tolerancePlus = 0;
        int toleranceMinus = 0;
        Field39A f39a = mt.getField39A();
        if (f39a != null) {
            tolerancePlus = toInt(f39a.getComponent1AsLong(), 0);
            toleranceMinus = toInt(f39a.getComponent2AsLong(), 0);
        }

        // UCP 600 Art. 14(c) default: 21 days if :48: absent.
        int presentationDays = 21;
        Field48 f48 = mt.getField48();
        if (f48 != null) {
            Long days = f48.getComponent1AsLong();
            if (days != null) {
                presentationDays = days.intValue();
            } else if (f48.getValue() != null) {
                Matcher m = F48_DIGITS.matcher(f48.getValue());
                if (m.matches()) presentationDays = Integer.parseInt(m.group(1));
            }
        }

        LocalDate latestShipmentDate = toLocalDate(mt.getField44C());

        String[] applicant = extractParty(raw, APPLICANT_TAGS);
        String[] beneficiary = extractParty(raw, BENEFICIARY_TAGS);

        // ── Structured :46A: documents-required ───────────────────────────────────
        List<DocumentRequirement> documentsRequired = documentListParser.parse(raw.get("46A"));

        // Pre-compute the two raw strings that projectSubFields() needs, so the method
        // receives all its inputs from ParsedScalars and carries no loop-variable dependency.
        String goodsDescriptionRaw = raw.get("45A");
        String availableRaw = raw.containsKey("41A") ? raw.get("41A") : raw.get("41D");

        // Bundle all Prowide-typed scalars into a single object for buildEnvelope().
        ParsedScalars scalars = new ParsedScalars(
                lcNumber, issueDate, expiryDate, expiryPlace, currency, amount,
                tolerancePlus, toleranceMinus, latestShipmentDate, presentationDays,
                applicant, beneficiary, documentsRequired,
                goodsDescriptionRaw, splitAvailableWithBy(availableRaw));

        // Log tags not registered in lc-tag-mapping.yaml — genuinely unknown to the system.
        Set<String> notInRegistry = new LinkedHashSet<>();
        for (String t : raw.keySet()) {
            if (tagMappings.byTag(t).isEmpty()) notInRegistry.add(t);
        }
        if (!notInRegistry.isEmpty()) {
            log.info("Mt700Parser: unregistered Block-4 tags (not in lc-tag-mapping.yaml; available via lc.rawFields()): {}", notInRegistry);
        }
        if (!header.isEmpty()) {
            log.debug("Mt700Parser Block-3 header tags: {}", header);
        }

        // ── Envelope build: lc-tag-mapping.yaml is the primary driver ────────────
        FieldEnvelope envelope = buildEnvelope(raw, header, swift.getBlock1(), swift.getBlock2(), scalars);

        // ── Display-ready row list for the parsed pane (UI renders 1:1) ───────────
        List<ParsedRow> parsedRows = rowProjector.project(envelope);

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
                raw.get("45A"),
                raw.get("46A"),
                raw.get("47A"),
                raw,
                header,
                envelope,
                documentsRequired,
                parsedRows
        );
    }

    /**
     * Prowide-typed scalars extracted in {@link #parse(String)} and bundled here so
     * {@link #buildEnvelope} receives a clean single-object contract instead of 13+
     * individual parameters.
     */
    private record ParsedScalars(
            String lcNumber,
            LocalDate issueDate,
            LocalDate expiryDate,
            String expiryPlace,
            String currency,
            BigDecimal amount,
            int tolerancePlus,
            int toleranceMinus,
            LocalDate latestShipmentDate,
            int presentationDays,
            String[] applicant,
            String[] beneficiary,
            List<DocumentRequirement> documentsRequired,
            String goodsDescriptionRaw,    // :45A: verbatim text
            String[] availableWithBy       // :41A:/:41D: pre-split [available_with, available_by]
    ) {}

    /**
     * Build the canonical-key map. {@code lc-tag-mapping.yaml} is the primary driver.
     *
     * <p>For every Block-4 tag:
     * <ol>
     *   <li>Confirm it is registered in {@code lc-tag-mapping.yaml} (gate via
     *       {@link TagMappingRegistry}). Unknown tags land in {@code extras}.</li>
     *   <li>If the tag encodes multiple canonical fields or requires Prowide's typed API
     *       to decode, delegate to {@link #projectSubFields} which writes pre-parsed
     *       scalars. That method is the <em>exception</em>.</li>
     *   <li>Otherwise the registry default applies: write the raw SWIFT value under
     *       each canonical key declared in the YAML — no Java change needed when adding
     *       simple single-field tags.</li>
     * </ol>
     */
    private FieldEnvelope buildEnvelope(Map<String, String> raw,
                                        Map<String, String> header,
                                        SwiftBlock1 block1,
                                        SwiftBlock2 block2,
                                        ParsedScalars scalars) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Object> extras = new LinkedHashMap<>();
        List<ParseWarning> warnings = new ArrayList<>();

        // Seed registry-declared defaults first (e.g. presentation_days=21, tolerance=0).
        for (var mapping : tagMappings.all()) {
            for (var def : mapping.defaults().entrySet()) {
                fields.putIfAbsent(def.getKey(), def.getValue());
            }
        }

        // ── Main loop: lc-tag-mapping.yaml drives everything ──────────────────────
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

            // Registry default: write raw value under each canonical key from the YAML.
            // Tags needing multi-field or Prowide-typed projection are handled by
            // projectSubFields() — that is the exception path, not the rule.
            if (!projectSubFields(tag, scalars, fields)) {
                for (String key : mappingOpt.get().fieldKeys()) {
                    put(fields, key, rawValue);
                }
            }
        }

        // ── SWIFT envelope (Blocks 1/2/3) ─────────────────────────────────────────
        // Block 1: F01HSBCHKHHHXXX0000000000 → sender LT "HSBCHKHHHXXX" → BIC11 "HSBCHKHHXXX".
        if (block1 != null) {
            put(fields, "sender_bic", toBic11(block1.getLogicalTerminal()));
        }
        // Block 2: I700CITIUS33XXXXN → message_type=700, receiver LT "CITIUS33XXXX" → BIC11 "CITIUS33XXX".
        if (block2 != null) {
            String b2 = block2.getValue();
            if (b2 != null && b2.length() >= 4) {
                put(fields, "message_type", b2.substring(1, 4)); // skip leading "I"/"O"
                if (b2.length() >= 16) put(fields, "receiver_bic", toBic11(b2.substring(4, 16)));
            }
        }
        // Block 3 :108: user reference
        String userRef = header.get("108");
        if (userRef != null) put(fields, "user_reference", userRef.trim());

        return new FieldEnvelope("LC", fields, extras, raw, warnings);
    }

    /**
     * Projects Prowide-typed scalars for the subset of Block-4 tags where the raw
     * SWIFT value cannot be passed through directly — because a single tag encodes
     * <em>two</em> canonical fields (e.g. {@code :32B:} → {@code credit_currency} +
     * {@code credit_amount}), or because the value requires Prowide's typed API to
     * decode correctly (e.g. {@code :31D:} date component → {@link LocalDate}), or
     * because custom splitting logic applies (e.g. {@code :41A:} → available-with +
     * available-by).
     *
     * <p>All tags <em>not</em> listed here are handled by the registry default in
     * {@link #buildEnvelope}: raw SWIFT value written under the canonical key(s)
     * declared in {@code lc-tag-mapping.yaml}. Add a new simple tag there and it
     * works with zero Java changes.
     *
     * @return {@code true} if projection was handled here (caller skips registry default);
     *         {@code false} to fall through to the registry default.
     */
    private boolean projectSubFields(String tag, ParsedScalars s, Map<String, Object> out) {
        switch (tag) {
            // ── Tags that decode into a single typed value ──────────────────────
            case "20"  -> put(out, "lc_number",            s.lcNumber());
            case "31C" -> put(out, "issue_date",            s.issueDate());
            case "44C" -> put(out, "latest_shipment_date",  s.latestShipmentDate());
            case "46A" -> put(out, "documents_required",    s.documentsRequired());
            case "48"  -> put(out, "presentation_days",     s.presentationDays());

            // ── Tags that split into two canonical fields ────────────────────────
            case "31D" -> {
                put(out, "expiry_date",  s.expiryDate());
                put(out, "expiry_place", s.expiryPlace());
            }
            case "32B" -> {
                put(out, "credit_currency", s.currency());
                put(out, "credit_amount",   s.amount());
            }
            case "39A" -> {
                put(out, "tolerance_plus",  s.tolerancePlus());
                put(out, "tolerance_minus", s.toleranceMinus());
            }
            case "50", "50B", "50F" -> {
                put(out, "applicant_name",    s.applicant()[0]);
                put(out, "applicant_address", s.applicant()[1]);
            }
            case "59", "59A", "59F" -> {
                put(out, "beneficiary_name",    s.beneficiary()[0]);
                put(out, "beneficiary_address", s.beneficiary()[1]);
            }
            case "41A", "41D" -> {
                put(out, "available_with", s.availableWithBy()[0]);
                put(out, "available_by",   s.availableWithBy()[1]);
            }

            // ── Tags that also derive a secondary field via sub-parser ───────────
            // :45A: raw text preserved verbatim AND incoterms extracted alongside.
            case "45A" -> {
                put(out, "goods_description", s.goodsDescriptionRaw());
                String inco = incotermsExtractor.extract(s.goodsDescriptionRaw());
                if (inco != null) put(out, "incoterms", inco);
            }

            // ── All other registered tags → registry default handles them ────────
            default -> { return false; }
        }
        return true;
    }

    /** Split :41A/D: raw value into [available_with, available_by]. */
    private static String[] splitAvailableWithBy(String raw) {
        if (raw == null) return new String[] { null, null };
        String t = raw.trim();
        int byIdx = t.toUpperCase().indexOf(" BY ");
        if (byIdx > 0) {
            return new String[] {
                    t.substring(0, byIdx).trim(),
                    "BY " + t.substring(byIdx + 4).trim()
            };
        }
        int nl = t.indexOf('\n');
        if (nl > 0) {
            return new String[] { t.substring(0, nl).trim(), t.substring(nl + 1).trim() };
        }
        return new String[] { t, null };
    }

    private static void put(Map<String, Object> fields, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        fields.put(key, value);
    }

    // -----------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------

    private SwiftMessage parseSwiftMessage(String fin) {
        try {
            String envelope = ensureEnvelope(fin);
            SwiftMessage swift = new SwiftParser(envelope).message();
            if (swift == null) {
                throw new LcParseException("SWIFT parser returned null for input");
            }
            return swift;
        } catch (LcParseException e) {
            throw e;
        } catch (Exception e) {
            throw new LcParseException("Failed to parse SWIFT message: " + e.getMessage(), e);
        }
    }

    /**
     * Prowide's {@link SwiftParser} requires the full {@code {1:}{2:}{4:…-}} envelope.
     * If the caller passed a bare Block-4 body (starts with {@code :nnL:} or contains
     * no {@code {1:} / {4:}} blocks), wrap it in a minimal envelope for parsing.
     */
    private String ensureEnvelope(String fin) {
        String trimmed = fin.trim();
        if (trimmed.contains("{1:") || trimmed.contains("{4:")) {
            return trimmed;
        }
        // Treat input as raw Block-4 body.
        String body = trimmed;
        if (!body.endsWith("-")) body = body + "\n-";
        return "{1:F01BANKBICAAXXX0000000000}{2:I700BANKBICBXXXXN}{4:\n" + body + "}";
    }

    /**
     * Party-tag (50/50B/50F or 59/59A/59F) name + address extraction.
     * Returns {@code [name, address]}; either element may be null.
     */
    private String[] extractParty(Map<String, String> raw, Set<String> tags) {
        for (String tag : tags) {
            String value = raw.get(tag);
            if (value != null && !value.isBlank()) {
                return splitPartyValue(value, tag);
            }
        }
        return new String[]{null, null};
    }

    /**
     * Split a raw party-tag value into (name, address). Handles:
     * <ul>
     *   <li>:50: / :59: — pure narrative, first line = name, rest = address.</li>
     *   <li>:50B: / :59A: — first line is often {@code /ACC…} or a BIC; if it starts
     *       with '/' we skip it and use the next line as name.</li>
     *   <li>:50F: — structured subfields with {@code /CODE/} prefixes; we look for
     *       {@code /NAME/} if present, else fall back to the first non-slash line.</li>
     * </ul>
     */
    private String[] splitPartyValue(String raw, String tag) {
        String[] lines = raw.split("\\r?\\n");
        if ("50F".equals(tag) || "59F".equals(tag)) {
            // Look for explicit /NAME/ prefix first.
            StringBuilder name = new StringBuilder();
            StringBuilder addr = new StringBuilder();
            String current = null;
            for (String line : lines) {
                String l = line.trim();
                if (l.isEmpty()) continue;
                if (l.startsWith("/")) {
                    int endCode = l.indexOf('/', 1);
                    if (endCode > 0) {
                        current = l.substring(1, endCode);
                        String rest = l.substring(endCode + 1);
                        appendTo(current, name, addr, rest);
                        continue;
                    }
                    // Leading /account-identifier/ line — skip.
                    current = null;
                    continue;
                }
                if (current != null) {
                    appendTo(current, name, addr, l);
                }
            }
            return new String[]{
                    name.length() == 0 ? null : name.toString().trim(),
                    addr.length() == 0 ? null : addr.toString().trim()
            };
        }
        // Default (:50:, :50B:, :59:, :59A:) — skip leading identifier lines that start with '/'.
        int nameIdx = 0;
        while (nameIdx < lines.length && lines[nameIdx].trim().startsWith("/")) {
            nameIdx++;
        }
        if (nameIdx >= lines.length) {
            return new String[]{null, null};
        }
        String name = lines[nameIdx].trim();
        StringBuilder addr = new StringBuilder();
        for (int i = nameIdx + 1; i < lines.length; i++) {
            if (addr.length() > 0) addr.append('\n');
            addr.append(lines[i].trim());
        }
        return new String[]{
                name.isEmpty() ? null : name,
                addr.length() == 0 ? null : addr.toString()
        };
    }

    private void appendTo(String code, StringBuilder name, StringBuilder addr, String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        if ("NAME".equalsIgnoreCase(code)) {
            if (name.length() > 0) name.append(' ');
            name.append(t);
        } else if ("ADDR".equalsIgnoreCase(code) || "ADDRESS".equalsIgnoreCase(code)) {
            if (addr.length() > 0) addr.append('\n');
            addr.append(t);
        } else if (name.length() == 0) {
            // Unknown code before NAME — treat as name line 1.
            name.append(t);
        } else {
            if (addr.length() > 0) addr.append('\n');
            addr.append(t);
        }
    }

    private static boolean hasMandatory(Map<String, String> raw, String tag) {
        if ("50".equals(tag)) {
            return presentAny(raw, APPLICANT_TAGS);
        }
        if ("59".equals(tag)) {
            return presentAny(raw, BENEFICIARY_TAGS);
        }
        String v = raw.get(tag);
        return v != null && !v.isBlank();
    }

    private static boolean presentAny(Map<String, String> raw, Set<String> tags) {
        for (String t : tags) {
            String v = raw.get(t);
            if (v != null && !v.isBlank()) return true;
        }
        return false;
    }

    private static LocalDate toLocalDate(Field31C f) {
        return f == null ? null : toLocalDate(f.getComponent1AsCalendar());
    }

    private static LocalDate toLocalDate(Field31D f) {
        return f == null ? null : toLocalDate(f.getComponent1AsCalendar());
    }

    private static LocalDate toLocalDate(Field44C f) {
        return f == null ? null : toLocalDate(f.getComponent1AsCalendar());
    }

    private static LocalDate toLocalDate(Calendar cal) {
        if (cal == null) return null;
        Date d = cal.getTime();
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static int toInt(Long value, int fallback) {
        return value == null ? fallback : value.intValue();
    }

    private static String safe(String s) {
        return s == null ? null : s.trim();
    }

    /**
     * Convert a SWIFT 12-char Logical Terminal Address to its commercial BIC11 form
     * by dropping the LT-code character at position 8 (0-indexed). The LT code
     * identifies which physical terminal at the bank sent the message — operational
     * detail, not bank identity. BIC11 = 8-char base BIC + 3-char branch code, and is
     * what trade-finance operators expect to see in UI / docs.
     *
     * <p>Example: {@code HSBCHKHHHXXX} (LT) → {@code HSBCHKHHXXX} (BIC11). Inputs that
     * are not exactly 12 characters (null, truncated, or already-stripped BIC11/BIC8)
     * are returned unchanged so we never silently mangle an unexpected shape.
     */
    private static String toBic11(String lt) {
        if (lt == null || lt.length() != 12) return lt;
        return lt.substring(0, 8) + lt.substring(9, 12);
    }

    /** Exposed for unit tests — keeps the mandatory set single-sourced. */
    static Set<String> mandatoryTags() {
        return Set.copyOf(MANDATORY_TAGS);
    }

    /**
     * Debug helper — returns a plain-text dump of all parsed LC fields plus every
     * Block-4 tag Prowide saw and Block-3 (user header) tags. Used by
     * {@code /api/v1/debug/mt700/parse}.
     */
    public String parseAsText(String mt700Text) {
        LcDocument lc = parse(mt700Text);
        StringBuilder sb = new StringBuilder();
        sb.append("=== LC FIELDS (TYPED) ===\n");
        sb.append("lc_number: ").append(lc.lcNumber()).append("\n");
        sb.append("issue_date: ").append(lc.issueDate()).append("\n");
        sb.append("expiry_date: ").append(lc.expiryDate()).append("\n");
        sb.append("expiry_place: ").append(lc.expiryPlace()).append("\n");
        sb.append("currency: ").append(lc.currency()).append("\n");
        sb.append("amount: ").append(lc.amount()).append("\n");
        sb.append("tolerance: +").append(lc.tolerancePlus()).append("% / -").append(lc.toleranceMinus()).append("%\n");
        sb.append("max_amount_flag: ").append(lc.maxAmountFlag()).append("\n");
        sb.append("partial_shipment: ").append(lc.partialShipment()).append("\n");
        sb.append("transhipment: ").append(lc.transhipment()).append("\n");
        sb.append("place_of_receipt: ").append(lc.placeOfReceipt()).append("\n");
        sb.append("latest_shipment_date: ").append(lc.latestShipmentDate()).append("\n");
        sb.append("shipment_period: ").append(lc.shipmentPeriod()).append("\n");
        sb.append("port_of_loading: ").append(lc.portOfLoading()).append("\n");
        sb.append("port_of_discharge: ").append(lc.portOfDischarge()).append("\n");
        sb.append("place_of_delivery: ").append(lc.placeOfDelivery()).append("\n");
        sb.append("presentation_days: ").append(lc.presentationDays()).append("\n");
        sb.append("applicable_rules: ").append(lc.applicableRules()).append("\n");
        sb.append("applicant: ").append(lc.applicantName()).append("\n");
        sb.append("applicant_address: ").append(lc.applicantAddress()).append("\n");
        sb.append("beneficiary: ").append(lc.beneficiaryName()).append("\n");
        sb.append("beneficiary_address: ").append(lc.beneficiaryAddress()).append("\n");

        sb.append("\n=== RAW FREE-TEXT FIELDS ===\n");
        sb.append(":45A:\n").append(lc.field45ARaw()).append("\n\n");
        sb.append(":46A:\n").append(lc.field46ARaw()).append("\n\n");
        sb.append(":47A:\n").append(lc.field47ARaw()).append("\n");

        sb.append("\n=== ALL BLOCK 4 TAGS (as captured by Prowide) ===\n");
        for (Map.Entry<String, String> e : lc.rawFields().entrySet()) {
            sb.append(':').append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }

        if (!lc.headerFields().isEmpty()) {
            sb.append("\n=== HEADER (BLOCK 3) TAGS ===\n");
            for (Map.Entry<String, String> e : lc.headerFields().entrySet()) {
                sb.append(':').append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }
}
