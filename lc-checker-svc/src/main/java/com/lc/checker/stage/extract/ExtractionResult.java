package com.lc.checker.stage.extract;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.result.LlmTrace;
import java.util.List;

/**
 * Output of a single {@link InvoiceExtractor} invocation. Carries the mapped
 * {@link InvoiceDocument} plus any LLM traces the extractor captured (empty for
 * non-LLM extractors such as Docling / MinerU).
 *
 * <p>The orchestrator persists one {@code stage_invoice_extract} row per result:
 * {@code inv_output = document}, {@code llm_calls = llmCalls}.
 */
public record ExtractionResult(
        InvoiceDocument document,
        List<LlmTrace> llmCalls
) {
    public ExtractionResult {
        llmCalls = llmCalls == null ? List.of() : List.copyOf(llmCalls);
    }

    public static ExtractionResult of(InvoiceDocument document) {
        return new ExtractionResult(document, List.of());
    }
}
