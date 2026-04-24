package com.lc.checker.extractor;

import com.lc.checker.model.InvoiceDocument;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Converts the transport DTO {@link ExtractorResponseDto} into the domain record
 * {@link InvoiceDocument}. Numeric-looking strings become {@link BigDecimal}s, ISO dates
 * become {@link LocalDate}s, and non-parseable values are tolerated as {@code null} —
 * the downstream checkers treat null per their {@code missing_invoice_action} policy.
 */
@Component
public class ExtractorResponseMapper {

    private static final Logger log = LoggerFactory.getLogger(ExtractorResponseMapper.class);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    public InvoiceDocument toDocument(ExtractorResponseDto dto) {
        if (dto == null) {
            throw new ExtractionException("unknown", ExtractorErrorCode.EMPTY_RESPONSE, "Extractor returned empty payload");
        }
        Map<String, Object> fields = dto.fields() == null ? Map.of() : dto.fields();

        return new InvoiceDocument(
                asString(fields.get("invoice_number")),
                asDate(fields.get("invoice_date")),
                asString(fields.get("seller_name")),
                asString(fields.get("seller_address")),
                asString(fields.get("buyer_name")),
                asString(fields.get("buyer_address")),
                asString(fields.get("goods_description")),
                asDecimal(fields.get("quantity")),
                asString(fields.get("unit")),
                asDecimal(fields.get("unit_price")),
                asDecimal(fields.get("total_amount")),
                asString(fields.get("currency")),
                asString(fields.get("lc_reference")),
                asString(fields.get("trade_terms")),
                asString(fields.get("port_of_loading")),
                asString(fields.get("port_of_discharge")),
                asString(fields.get("country_of_origin")),
                asBoolean(fields.get("signed")),
                dto.extractor(),
                dto.confidence() == null ? 0.0 : dto.confidence(),
                dto.isImageBased() != null && dto.isImageBased(),
                dto.pages() == null ? 0 : dto.pages(),
                dto.extractionMs() == null ? 0L : dto.extractionMs(),
                dto.rawMarkdown(),
                dto.rawText()
        );
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal asDecimal(Object v) {
        String s = asString(v);
        if (s == null) return null;
        // Accept numbers with commas as thousand separators defensively.
        String cleaned = s.replace(",", "").replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            log.debug("Could not parse numeric '{}' from extractor; leaving null", s);
            return null;
        }
    }

    private static LocalDate asDate(Object v) {
        String s = asString(v);
        if (s == null) return null;
        try {
            return LocalDate.parse(s, ISO_DATE);
        } catch (Exception e) {
            log.debug("Extractor returned non-ISO date '{}'; leaving null (downstream will mark UNABLE_TO_VERIFY)", s);
            return null;
        }
    }

    private static Boolean asBoolean(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase();
        return switch (s) {
            case "true", "yes", "y", "1" -> Boolean.TRUE;
            case "false", "no", "n", "0" -> Boolean.FALSE;
            default -> null;
        };
    }
}
