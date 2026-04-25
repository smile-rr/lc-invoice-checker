package com.lc.checker.stage.extract;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.result.LlmTrace;
import java.util.List;

/**
 * Output of a single vision-LLM extractor invocation. Carries the mapped
 * {@link InvoiceDocument} plus the LLM traces (request/response/latency).
 *
 * <p>The orchestrator persists one {@code pipeline_steps} row per result:
 * {@code result.document = document}, {@code result.llm_calls = llmCalls}.
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
