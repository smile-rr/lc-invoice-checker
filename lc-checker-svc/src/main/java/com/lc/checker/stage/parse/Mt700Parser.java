package com.lc.checker.stage.parse;

import com.lc.checker.domain.common.DocumentRequirement;
import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.ParseWarning;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import com.lc.checker.infra.fields.TagMappingRegistry;
import com.lc.checker.stage.parse.subfield.DocumentListParser;
import com.lc.checker.stage.parse.subfield.IncotermsExtractor;
import com.prowidesoftware.swift.io.parser.SwiftParser;
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
 * <p>Throws {@link LcParseException} when the SWIFT envelope is structurally unreadable,
 * or when a mandatory tag ({@value #MANDATORY_TAGS}) is absent, or when :32B:/:31D:
 * cannot be decoded.
 */
@Component
public class Mt700Parser {

    private static final Logger log = LoggerFactory.getLogger(Mt700Parser.class);

    /**
     * Business-mandatory tags for our compliance pipeline. {@code "50"} and {@code "59"}
     * are satisfied by ANY of their option letters (see {@link #hasMandatory}).
     */
    public static final List<String> MANDATORY_TAGS = List.of("20", "31D", "32B", "45A", "50", "59");

    /** All :50: option letters accepted as the "applicant" party tag. */
    private static final Set<String> APPLICANT_TAGS = Set.of("50", "50B", "50F");

    /** All :59: option letters accepted as the "beneficiary" party tag. */
    private static final Set<String> BENEFICIARY_TAGS = Set.of("59", "59A", "59F");

    /**
     * Tag names we bind to typed {@link LcDocument} fields. Tags not in this set are
     * still captured in {@code rawFields} and logged once at INFO so ops can see them.
     */
    private static final Set<String> TYPED_TAGS = Set.of(
            "20", "31C", "31D", "32B", "39A", "39B", "40E", "43P", "43T",
            "44A", "44B", "44C", "44D", "44E", "44F", "45A", "46A", "47A",
            "48", "50", "50B", "50F", "59", "59A", "59F"
    );

    /** :48: presentation-period leading digits (e.g. "21" or "21/DAYS"). */
    private static final Pattern F48_DIGITS = Pattern.compile("^\\s*(\\d+).*$");

    private final FieldPoolRegistry fieldPool;
    private final TagMappingRegistry tagMappings;
    private final DocumentListParser documentListParser;
    private final IncotermsExtractor incotermsExtractor;

    public Mt700Parser(FieldPoolRegistry fieldPool,
                       TagMappingRegistry tagMappings,
                       DocumentListParser documentListParser,
                       IncotermsExtractor incotermsExtractor) {
        this.fieldPool = fieldPool;
        this.tagMappings = tagMappings;
        this.documentListParser = documentListParser;
        this.incotermsExtractor = incotermsExtractor;
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

        // Mandatory-tag check — fail fast with field-specific diagnostics.
        for (String tag : MANDATORY_TAGS) {
            if (!hasMandatory(raw, tag)) {
                throw new LcParseException(":" + tag + ":",
                        "Mandatory MT700 field :" + tag + ": missing or empty");
            }
        }

        // --- Typed scalar extraction via Prowide Field* --------------------

        Field20 f20 = mt.getField20();
        if (f20 == null || f20.getValue() == null || f20.getValue().isBlank()) {
            throw new LcParseException(":20:", "Tag :20: (LC reference) missing or empty");
        }
        String lcNumber = f20.getValue().trim();

        LocalDate issueDate = toLocalDate(mt.getField31C());
        Field31D f31d = mt.getField31D();
        if (f31d == null) {
            throw new LcParseException(":31D:", "Tag :31D: (expiry) missing");
        }
        LocalDate expiryDate = toLocalDate(f31d);
        if (expiryDate == null) {
            throw new LcParseException(":31D:",
                    "Cannot parse :31D: date from '" + f31d.getValue() + "'");
        }
        String expiryPlace = safe(f31d.getPlace());

        Field32B f32b = mt.getField32B();
        if (f32b == null) {
            throw new LcParseException(":32B:", "Tag :32B: (currency/amount) missing");
        }
        String currency = safe(f32b.getCurrency());
        BigDecimal amount = f32b.getAmountAsBigDecimal();
        if (currency == null || currency.isBlank() || amount == null) {
            throw new LcParseException(":32B:",
                    "Cannot parse :32B: currency/amount from '" + f32b.getValue() + "'");
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

        // Record any tag we saw but didn't bind to a typed field so ops can spot drift.
        Set<String> unmapped = new LinkedHashSet<>(raw.keySet());
        unmapped.removeAll(TYPED_TAGS);
        if (!unmapped.isEmpty()) {
            log.info("Mt700Parser unmapped Block-4 tags (available via lc.rawFields()): {}", unmapped);
        }
        if (!header.isEmpty()) {
            log.debug("Mt700Parser Block-3 header tags: {}", header);
        }

        // ── Structured :46A: documents-required ───────────────────────────────────
        List<DocumentRequirement> documentsRequired = documentListParser.parse(raw.get("46A"));

        // ── Generic envelope build (registry-driven canonical map) ────────────────
        FieldEnvelope envelope = buildEnvelope(
                raw, lcNumber, issueDate, expiryDate, expiryPlace, currency, amount,
                tolerancePlus, toleranceMinus, latestShipmentDate, presentationDays,
                applicant, beneficiary, documentsRequired);

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
                documentsRequired
        );
    }

    /**
     * Build the canonical-key map driven by the field-pool / tag-mapping registries.
     *
     * <p>For each tag that the registries know about, write the already-extracted
     * Prowide-typed value (or sub-field-parser result) under its canonical key(s).
     * Tags the registries don't know about land in {@code extras} verbatim. Defaults
     * (e.g. {@code presentation_days = 21}, {@code tolerance_plus/minus = 0}) come
     * from {@code lc-tag-mapping.yaml}.
     */
    private FieldEnvelope buildEnvelope(Map<String, String> raw,
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
                                        List<DocumentRequirement> documentsRequired) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Object> extras = new LinkedHashMap<>();
        List<ParseWarning> warnings = new ArrayList<>();

        // Pre-populate registry-declared defaults so optional tags (39A, 48) have
        // sensible values even when absent from the message.
        for (var mapping : tagMappings.all()) {
            for (var def : mapping.defaults().entrySet()) {
                fields.putIfAbsent(def.getKey(), def.getValue());
            }
        }

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
            // Write canonical-keyed values from the already-computed typed scalars.
            // Tag-by-tag because each tag has a known shape; this is the single
            // place where tag → canonical value coercion lives.
            switch (tag) {
                case "20" -> put(fields, "lc_number", lcNumber);
                case "31C" -> put(fields, "issue_date", issueDate);
                case "31D" -> {
                    put(fields, "expiry_date", expiryDate);
                    put(fields, "expiry_place", expiryPlace);
                }
                case "32B" -> {
                    put(fields, "credit_currency", currency);
                    put(fields, "credit_amount", amount);
                }
                case "39A" -> {
                    put(fields, "tolerance_plus", tolerancePlus);
                    put(fields, "tolerance_minus", toleranceMinus);
                }
                case "39B" -> put(fields, "max_amount_flag", rawValue);
                case "40E" -> put(fields, "applicable_rules", rawValue);
                case "43P" -> put(fields, "partial_shipment", rawValue);
                case "43T" -> put(fields, "transhipment", rawValue);
                case "44A" -> put(fields, "place_of_receipt", rawValue);
                case "44B" -> put(fields, "place_of_delivery", rawValue);
                case "44C" -> put(fields, "latest_shipment_date", latestShipmentDate);
                case "44D" -> put(fields, "shipment_period", rawValue);
                case "44E" -> put(fields, "port_of_loading", rawValue);
                case "44F" -> put(fields, "port_of_discharge", rawValue);
                case "45A" -> {
                    put(fields, "goods_description", rawValue);
                    String inco = incotermsExtractor.extract(rawValue);
                    if (inco != null) put(fields, "incoterms", inco);
                }
                case "46A" -> put(fields, "documents_required", documentsRequired);
                case "47A" -> put(fields, "additional_conditions", rawValue);
                case "48" -> put(fields, "presentation_days", presentationDays);
                case "50", "50B", "50F" -> {
                    put(fields, "applicant_name", applicant[0]);
                    put(fields, "applicant_address", applicant[1]);
                }
                case "59", "59A", "59F" -> {
                    put(fields, "beneficiary_name", beneficiary[0]);
                    put(fields, "beneficiary_address", beneficiary[1]);
                }
                default -> {
                    // Tag is registered in the mapping but the parser doesn't know how
                    // to project it yet — preserve the raw value under each canonical key.
                    for (String key : mappingOpt.get().fieldKeys()) {
                        put(fields, key, rawValue);
                    }
                }
            }
        }

        return new FieldEnvelope("LC", fields, extras, raw, warnings);
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
