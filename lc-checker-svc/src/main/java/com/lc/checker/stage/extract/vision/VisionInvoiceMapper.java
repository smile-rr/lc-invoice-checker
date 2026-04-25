package com.lc.checker.stage.extract.vision;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.stage.extract.InvoiceFieldMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper retained for the existing call site in {@code VisionLlmExtractor}.
 * All mapping logic lives in the registry-driven {@link InvoiceFieldMapper}.
 */
@Component
public class VisionInvoiceMapper {

    private final InvoiceFieldMapper delegate;

    public VisionInvoiceMapper(InvoiceFieldMapper delegate) {
        this.delegate = delegate;
    }

    public InvoiceDocument toDocument(
            Map<String, Object> fields,
            String extractorName,
            double confidence,
            boolean imageBased,
            int pages,
            long extractionMs,
            String rawMarkdown,
            String rawText) {
        return delegate.toDocument(fields, extractorName, confidence, imageBased, pages,
                extractionMs, rawMarkdown, rawText);
    }
}
