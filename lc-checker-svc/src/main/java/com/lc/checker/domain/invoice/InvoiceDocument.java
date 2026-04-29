package com.lc.checker.domain.invoice;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.common.FieldCoercion;
import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.ParsedRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Parsed commercial invoice. The {@link FieldEnvelope} is the single source of
 * truth; the typed accessors below ({@code totalAmount()}, {@code currency()},
 * …) are derived on demand.
 *
 * <p>Wire JSON shape (snake_case via {@link JsonNaming}):
 * <pre>
 * { envelope, extractor_used, extractor_confidence, image_based, pages,
 *   extraction_ms, raw_markdown, raw_text, parsed_rows }
 * </pre>
 *
 * <p>Jackson serialises only the record components — the typed accessors below
 * are NOT emitted as top-level keys (they exist for SpEL +
 * {@code AgentStrategy.renderInvoice()}). All scalar canonical fields live
 * inside {@code envelope.fields}; rules should prefer
 * {@code #invoiceFields['credit_amount']} over {@code inv.totalAmount()}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InvoiceDocument(
        FieldEnvelope envelope,
        String extractorUsed,
        double extractorConfidence,
        boolean imageBased,
        int pages,
        long extractionMs,
        String rawMarkdown,
        String rawText,
        List<ParsedRow> parsedRows
) {

    public InvoiceDocument {
        if (envelope == null) {
            envelope = FieldEnvelope.empty("INVOICE");
        }
        parsedRows = parsedRows == null ? List.of() : List.copyOf(parsedRows);
    }

    // ── Typed envelope-derived accessors (SpEL + plain-text prompt rendering) ──

    public String     invoiceNumber()    { return str("invoice_number"); }
    public LocalDate  invoiceDate()      { return date("invoice_date"); }
    public String     sellerName()       { return str("beneficiary_name"); }
    public String     sellerAddress()    { return str("beneficiary_address"); }
    public String     buyerName()        { return str("applicant_name"); }
    public String     buyerAddress()     { return str("applicant_address"); }
    public String     goodsDescription() { return str("goods_description"); }
    public BigDecimal quantity()         { return amt("quantity"); }
    public String     unit()             { return str("unit"); }
    public BigDecimal unitPrice()        { return amt("unit_price"); }
    public BigDecimal totalAmount()      { return amt("credit_amount"); }
    public String     currency()         { return str("credit_currency"); }
    public String     lcReference()      { return str("lc_number"); }
    public String     tradeTerms()       { return str("incoterms"); }
    public String     portOfLoading()    { return str("port_of_loading"); }
    public String     portOfDischarge()  { return str("port_of_discharge"); }
    public String     countryOfOrigin()  { return str("country_of_origin"); }
    public Boolean    signed()           { return FieldCoercion.asBoolean(envelope.fields().get("signed")); }

    // ── Coercion helpers ──────────────────────────────────────────────────────

    private String     str(String key)  { return FieldCoercion.asString(envelope.fields().get(key)); }
    private LocalDate  date(String key) { return FieldCoercion.asDate(envelope.fields().get(key)); }
    private BigDecimal amt(String key)  { return FieldCoercion.asDecimal(envelope.fields().get(key)); }
}
