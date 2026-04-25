package com.lc.checker.stage.extract;

import com.lc.checker.stage.extract.vision.VisionLlmExtractor;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.result.LlmTrace;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.stream.CheckEvent;
import com.lc.checker.infra.stream.CheckEventPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stage 1b orchestrator. Fires every enabled vision-LLM extractor in parallel
 * against the same PDF, persists one {@code pipeline_steps} row per source,
 * then marks one row as {@code is_selected=true} to drive downstream rule
 * checks.
 *
 * <p>Chain priority order: {@code [local_vision, remote_vision]}. Selection
 * rule: <b>first SUCCESS in chain order</b> — confidence threshold becomes a
 * {@code WARN}-only signal, not a gate. So {@code local_vision} wins whenever
 * it succeeded, regardless of confidence; if it failed, {@code remote_vision}
 * takes over.
 *
 * <p>Per-source persistence is preserved across crashes — {@link #runAndPersistOne}
 * catches every exception, persists a {@code FAILED} row, and emits an SSE
 * event, so a single source's blow-up never loses the other source's result.
 */
@Component
public class InvoiceExtractionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(InvoiceExtractionOrchestrator.class);

    /** Configured chain in priority order — drives selection AND parallel fan-out. */
    private final List<VisionLlmExtractor> chain = new ArrayList<>();

    private final CheckSessionStore store;
    private final Executor executor;
    private final double confidenceThreshold;
    private volatile String lastUsed = "remote_vision";

    public InvoiceExtractionOrchestrator(
            @Qualifier("remoteVisionExtractor") VisionLlmExtractor remoteVision,
            @Qualifier("localVisionExtractor") ObjectProvider<VisionLlmExtractor> localVisionProvider,
            CheckSessionStore store,
            @Qualifier("lcCheckExecutor") Executor executor,
            @Value("${extractor.confidence-threshold:0.80}") double confidenceThreshold,
            @Value("${extractor.vision-enabled:true}")        boolean visionEnabled,
            @Value("${extractor.local-vision-enabled:false}") boolean localVisionEnabled) {

        this.store = store;
        this.executor = executor;
        this.confidenceThreshold = confidenceThreshold;

        // Priority order: local_vision first (default selected when available),
        // then remote_vision as the comparison / fallback source.
        if (localVisionEnabled) {
            VisionLlmExtractor local = localVisionProvider.getIfAvailable();
            if (local == null) {
                throw new IllegalStateException(
                        "extractor.local-vision-enabled=true but no localVisionExtractor bean exists. "
                        + "Check the @ConditionalOnProperty wiring and the local-vision config block.");
            }
            chain.add(local);
        }
        if (visionEnabled) {
            chain.add(remoteVision);
        }
        if (chain.isEmpty()) {
            throw new IllegalStateException(
                    "At least one extractor must be enabled (local_vision / remote_vision).");
        }
        log.info("InvoiceExtractionOrchestrator initialised: sources={} threshold={} (warn-only)",
                chain.stream().map(VisionLlmExtractor::extractorName).toList(),
                confidenceThreshold);
    }

    /** Per-source extraction outcome — produced by {@link #runAndPersistOne}. */
    private record Attempt(String source, boolean success, InvoiceDocument doc,
                           List<LlmTrace> traces, String error, long durationMs) {}

    public InvoiceDocument runAndPersist(String sessionId, byte[] pdfBytes, String filename) {
        return runAndPersist(sessionId, pdfBytes, filename, CheckEventPublisher.NOOP);
    }

    /**
     * Stage 1b main entry. Fires each enabled extractor as its own future on
     * {@code lcCheckExecutor}; total wall-clock is roughly {@code max(perSource)}.
     */
    public InvoiceDocument runAndPersist(String sessionId, byte[] pdfBytes, String filename,
                                          CheckEventPublisher publisher) {
        // One future per enabled source, all run in parallel.
        List<CompletableFuture<Attempt>> futures = chain.stream()
                .map(e -> CompletableFuture.supplyAsync(
                        () -> runAndPersistOne(e, sessionId, pdfBytes, filename, publisher),
                        executor))
                .toList();

        // Each future internally swallows ExtractionException into a FAILED Attempt,
        // so allOf().join() should never throw under normal operation. Defensive log.
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Unexpected lane failure in invoice extraction: {}", e.getMessage(), e);
        }

        // Collect attempts — futures may not all have completed normally; getNow(null)
        // handles that. Filter nulls. Order is chain order (futures stream is ordered).
        List<Attempt> attempts = futures.stream()
                .map(f -> f.getNow(null))
                .filter(Objects::nonNull)
                .toList();

        // Selection: first SUCCESS in chain priority order. Threshold is warn-only.
        Attempt selected = attempts.stream()
                .filter(Attempt::success)
                .findFirst()
                .orElse(null);
        if (selected == null) {
            throw new ExtractionException("none", ExtractorErrorCode.UNKNOWN,
                    "All extractors failed; see pipeline_steps rows for details");
        }
        if (selected.doc().extractorConfidence() < confidenceThreshold) {
            log.warn("Stage 1b selected source={} below threshold (confidence={}, threshold={})",
                    selected.source(), selected.doc().extractorConfidence(), confidenceThreshold);
        }

        store.markExtractSelected(sessionId, selected.source());
        lastUsed = selected.source();
        log.info("Stage 1b selected source={} (confidence={})",
                selected.source(), selected.doc().extractorConfidence());
        return selected.doc();
    }

    /**
     * Run one extractor: capture timing, persist a row to {@code pipeline_steps}
     * (SUCCESS or FAILED), emit per-source SSE events, return the {@link Attempt}.
     * Never throws — extractor exceptions become FAILED attempts so a single
     * source's failure doesn't poison the parallel join.
     */
    private Attempt runAndPersistOne(VisionLlmExtractor e, String sessionId,
                                     byte[] pdfBytes, String filename,
                                     CheckEventPublisher publisher) {
        String source = e.extractorName();
        publisher.emit(CheckEvent.of(CheckEvent.Type.EXTRACT_SOURCE_STARTED,
                Map.of("source", source)));
        Instant stepStart = Instant.now();
        long t0 = System.currentTimeMillis();
        try {
            ExtractionResult r = e.extract(pdfBytes, filename);
            long dur = System.currentTimeMillis() - t0;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("document", r.document());
            result.put("llm_calls", r.llmCalls());
            result.put("is_selected", false);  // markExtractSelected flips one later
            store.putStep(sessionId, "invoice_extract", source, "SUCCESS",
                    stepStart, Instant.now(), result, null);
            log.info("Stage 1b source={} SUCCESS confidence={} duration={}ms",
                    source, r.document().extractorConfidence(), dur);
            publisher.emit(CheckEvent.of(CheckEvent.Type.EXTRACT_SOURCE_COMPLETED,
                    Map.of(
                            "source", source,
                            "success", true,
                            "confidence", r.document().extractorConfidence(),
                            "durationMs", dur,
                            "imageBased", r.document().imageBased(),
                            "pages", r.document().pages())));
            return new Attempt(source, true, r.document(), r.llmCalls(), null, dur);
        } catch (ExtractionException ex) {
            long dur = System.currentTimeMillis() - t0;
            List<LlmTrace> trc = ex.getLlmTrace() == null ? List.of() : List.of(ex.getLlmTrace());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("document", null);
            result.put("llm_calls", trc);
            result.put("is_selected", false);
            store.putStep(sessionId, "invoice_extract", source, "FAILED",
                    stepStart, Instant.now(), result, ex.getMessage());
            log.warn("Stage 1b source={} FAILED duration={}ms error={}",
                    source, dur, ex.getMessage());
            publisher.emit(CheckEvent.of(CheckEvent.Type.EXTRACT_SOURCE_COMPLETED,
                    Map.of(
                            "source", source,
                            "success", false,
                            "durationMs", dur,
                            "error", ex.getMessage() == null ? "" : ex.getMessage())));
            return new Attempt(source, false, null, trc, ex.getMessage(), dur);
        } catch (RuntimeException ex) {
            long dur = System.currentTimeMillis() - t0;
            String msg = "unexpected: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("document", null);
            result.put("llm_calls", List.of());
            result.put("is_selected", false);
            store.putStep(sessionId, "invoice_extract", source, "FAILED",
                    stepStart, Instant.now(), result, msg);
            log.error("Stage 1b source={} crashed duration={}ms", source, dur, ex);
            publisher.emit(CheckEvent.of(CheckEvent.Type.EXTRACT_SOURCE_COMPLETED,
                    Map.of(
                            "source", source,
                            "success", false,
                            "durationMs", dur,
                            "error", msg)));
            return new Attempt(source, false, null, List.of(), msg, dur);
        }
    }

    /**
     * Names of extractor sources currently configured to run, in chain priority
     * order. UI uses this for pre-populated PENDING cards before any SSE event.
     */
    public List<String> configuredSources() {
        return chain.stream().map(VisionLlmExtractor::extractorName).toList();
    }

    /** Source of the most recently selected attempt — debug / observability only. */
    public String lastUsedSource() {
        return lastUsed;
    }

    /**
     * Debug helper — runs every enabled source in chain order and returns a
     * plain-text dump. Used by {@code /api/v1/debug/invoice/compare}. Does NOT
     * persist to DB.
     */
    public String compareAllAsText(byte[] pdfBytes, String filename) {
        StringBuilder sb = new StringBuilder();
        for (VisionLlmExtractor e : chain) {
            long start = System.currentTimeMillis();
            sb.append("=== ").append(e.extractorName()).append(" ===\n");
            try {
                ExtractionResult r = e.extract(pdfBytes, filename);
                InvoiceDocument doc = r.document();
                long ms = System.currentTimeMillis() - start;
                sb.append("status: OK\n");
                sb.append("confidence: ").append(doc.extractorConfidence()).append("\n");
                sb.append("pages: ").append(doc.pages()).append("\n");
                sb.append("extraction_ms: ").append(ms).append("\n");
                sb.append("invoice_number: ").append(doc.invoiceNumber()).append("\n");
                sb.append("invoice_date: ").append(doc.invoiceDate()).append("\n");
                sb.append("seller: ").append(doc.sellerName()).append("\n");
                sb.append("buyer: ").append(doc.buyerName()).append("\n");
                sb.append("goods: ").append(doc.goodsDescription()).append("\n");
                sb.append("total_amount: ").append(doc.totalAmount())
                  .append(" ").append(doc.currency()).append("\n");
                sb.append("lc_reference: ").append(doc.lcReference()).append("\n");
                sb.append("trade_terms: ").append(doc.tradeTerms()).append("\n");
                sb.append("country_of_origin: ").append(doc.countryOfOrigin()).append("\n");
                sb.append("signed: ").append(doc.signed()).append("\n");
                sb.append("llm_calls: ").append(r.llmCalls().size()).append("\n");
                if (doc.rawText() != null && !doc.rawText().isBlank()) {
                    sb.append("\nraw_text:\n").append(doc.rawText()).append("\n");
                }
            } catch (Exception ex) {
                long ms = System.currentTimeMillis() - start;
                sb.append("status: FAILED\n");
                sb.append("extraction_ms: ").append(ms).append("\n");
                sb.append("error: ").append(ex.getMessage()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
