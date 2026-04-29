package com.lc.checker.domain.lc;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.common.DocumentRequirement;
import com.lc.checker.domain.common.FieldCoercion;
import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.ParsedRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Structured view of an MT700 credit. Built by {@code Mt700Parser} from the
 * canonical {@link FieldEnvelope}; no top-level scalar is stored alongside —
 * the envelope is the single source of truth.
 *
 * <p>Wire JSON shape (snake_case via {@link JsonNaming}):
 * <pre>
 * { raw_fields, header_fields, envelope, documents_required, parsed_rows }
 * </pre>
 *
 * <p>The instance methods below ({@code lcNumber()}, {@code amount()}, …)
 * derive every value from {@link #envelope} on demand. They exist for the
 * benefit of {@code AgentStrategy.renderLc()} (plain-text prompt rendering)
 * and any SpEL expression that prefers {@code lc.lcNumber} over
 * {@code #fields['lc_number']}. Jackson does NOT serialise them — only the
 * record components appear in the wire JSON.
 *
 * <p>Adding a new MT700 field is a YAML edit; this record does not need to
 * change.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LcDocument(
        Map<String, String> rawFields,
        Map<String, String> headerFields,
        FieldEnvelope envelope,
        List<DocumentRequirement> documentsRequired,
        List<ParsedRow> parsedRows
) {

    public LcDocument {
        rawFields = rawFields == null ? Map.of() : Map.copyOf(rawFields);
        headerFields = headerFields == null ? Map.of() : Map.copyOf(headerFields);
        envelope = envelope == null ? FieldEnvelope.empty("LC") : envelope;
        documentsRequired = documentsRequired == null ? List.of() : List.copyOf(documentsRequired);
        parsedRows = parsedRows == null ? List.of() : List.copyOf(parsedRows);
    }

    /** Factory used by {@code Mt700Parser}. Kept for call-site readability. */
    public static LcDocument fromScalars(Map<String, String> rawFields,
                                         Map<String, String> headerFields,
                                         FieldEnvelope envelope,
                                         List<DocumentRequirement> documentsRequired,
                                         List<ParsedRow> parsedRows) {
        return new LcDocument(rawFields, headerFields, envelope, documentsRequired, parsedRows);
    }

    // ── Typed envelope-derived accessors (SpEL + plain-text prompt rendering) ──

    public String     lcNumber()             { return str("lc_number"); }
    public LocalDate  issueDate()            { return date("issue_date"); }
    public LocalDate  expiryDate()           { return date("expiry_date"); }
    public String     expiryPlace()          { return str("expiry_place"); }
    public String     currency()             { return str("credit_currency"); }
    public BigDecimal amount()               { return amt("credit_amount"); }
    public boolean    aboutCreditAmount()    { return bool("about_credit_amount"); }
    public int        tolerancePlus()        { return integer("tolerance_plus"); }
    public int        toleranceMinus()       { return integer("tolerance_minus"); }
    public String     maxAmountFlag()        { return str("max_amount_flag"); }
    public String     partialShipment()      { return str("partial_shipment"); }
    public String     transhipment()         { return str("transhipment"); }
    public String     placeOfReceipt()       { return str("place_of_receipt"); }
    public String     placeOfDelivery()      { return str("place_of_delivery"); }
    public LocalDate  latestShipmentDate()   { return date("latest_shipment_date"); }
    public String     shipmentPeriod()       { return str("shipment_period"); }
    public String     portOfLoading()        { return str("port_of_loading"); }
    public String     portOfDischarge()      { return str("port_of_discharge"); }
    public int        presentationDays()     { return integer("presentation_days"); }
    public String     applicableRules()      { return str("applicable_rules"); }
    public String     applicantName()        { return str("applicant_name"); }
    public String     applicantAddress()     { return str("applicant_address"); }
    public String     beneficiaryName()      { return str("beneficiary_name"); }
    public String     beneficiaryAddress()   { return str("beneficiary_address"); }
    public String     field45ARaw()          { return str("goods_description"); }
    public String     field46ARaw()          { return str("documents_required"); }
    public String     field47ARaw()          { return str("additional_conditions"); }

    /** Canonical-key lookup for rules without a typed accessor. */
    public Object envelopeField(String canonicalKey) {
        return envelope.fields().get(canonicalKey);
    }

    // ── Coercion helpers ──────────────────────────────────────────────────────

    private String str(String key)     { return FieldCoercion.asString(envelope.fields().get(key)); }
    private LocalDate date(String key) { return FieldCoercion.asDate(envelope.fields().get(key)); }
    private BigDecimal amt(String key) { return FieldCoercion.asDecimal(envelope.fields().get(key)); }

    private boolean bool(String key) {
        Boolean b = FieldCoercion.asBoolean(envelope.fields().get(key));
        return Boolean.TRUE.equals(b);
    }

    private int integer(String key) {
        Integer i = FieldCoercion.asInteger(envelope.fields().get(key));
        return i == null ? 0 : i;
    }
}
