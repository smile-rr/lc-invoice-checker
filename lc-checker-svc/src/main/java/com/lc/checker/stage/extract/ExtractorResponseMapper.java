package com.lc.checker.stage.extract;

import com.lc.checker.domain.invoice.InvoiceDocument;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper retained for HTTP extractor (docling/mineru) call sites. All
 * mapping logic lives in the registry-driven {@link InvoiceFieldMapper}.
 */
@Component
public class ExtractorResponseMapper {

    private final InvoiceFieldMapper delegate;

    public ExtractorResponseMapper(InvoiceFieldMapper delegate) {
        this.delegate = delegate;
    }

    public InvoiceDocument toDocument(ExtractorResponseDto dto) {
        if (dto == null) {
            throw new ExtractionException("unknown", ExtractorErrorCode.EMPTY_RESPONSE,
                    "Extractor returned empty payload");
        }
        Map<String, Object> fields = dto.fields() == null ? Map.of() : dto.fields();
        return delegate.toDocument(
                fields,
                dto.extractor(),
                dto.confidence() == null ? 0.0 : dto.confidence(),
                dto.isImageBased() != null && dto.isImageBased(),
                dto.pages() == null ? 0 : dto.pages(),
                dto.extractionMs() == null ? 0L : dto.extractionMs(),
                dto.rawMarkdown(),
                dto.rawText());
    }
}
