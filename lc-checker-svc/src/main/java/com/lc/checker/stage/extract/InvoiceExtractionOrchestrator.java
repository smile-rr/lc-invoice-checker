package com.lc.checker.stage.extract;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.result.LlmTrace;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.stream.CheckEventPublisher;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stage 1b orchestrator. Fires every enabled extractor in parallel against the
 * same PDF, persists one {@code pipeline_steps} row per source, then marks one
 * row as {@code is_selected=true} to drive downstream rule checks.
 *
 * <p><b>Selection chain</b> (priority order): {@code [local_llm_vl, cloud_llm_vl]}.
 * Selection rule: <b>first SUCCESS in chain order</b>. Confidence threshold is a
 * {@code WARN}-only signal, not a gate.
 *
 * <p><b>Supplementary sources</b>: {@code [docling, mineru]} (when enabled). They
 * run in parallel and their results are persisted for display in the UI, but they
 * are never candidates for the selected result — rule checks always use a vision
 * LLM result.
 *
 * <p>Per-source persistence is preserved across crashes — {@link #runAndPersistOne}
 * catches every exception, persists a {@code FAILED} row, and emits an SSE event,
 * so a single source's blow-up never loses the other sources' results.
 */
@Component
public class InvoiceExtractionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(InvoiceExtractionOrchestrator.class);

    /** Priority-ordered vision sources — winner is selected from here only. */
    private final List<InvoiceExtractor> selectionChain = new ArrayList<>();

    /** Text-layout sources — always run, persist, but never selected for rule checks. */
    private final List<InvoiceExtractor> supplementary = new ArrayList<>();

    private final CheckSessionStore store;
    private final Executor executor;
    private final double confidenceThreshold;
    private volatile String lastUsed = "";

    public InvoiceExtractionOrchestrator(
            @Qualifier("cloudLlmVlExtractor") InvoiceExtractor cloudLlmVl,
            @Qualifier("localLlmVlExtractor") ObjectProvider<InvoiceExtractor> localLlmVlProvider,
            @Qualifier("doclingExtractorClient") ObjectProvider<InvoiceExtractor> doclingProvider,
            @Qualifier("mineruExtractorClient")  ObjectProvider<InvoiceExtractor> mineruProvider,
            CheckSessionStore store,
            @Qualifier("lcCheckExecutor") Executor executor,
            @Value("${extractor.confidence-threshold:0.80}") double confidenceThreshold,
            @Value("${extractor.cloud-llm-vl-enabled:true}")  boolean cloudLlmVlEnabled,
            @Value("${extractor.local-llm-vl-enabled:false}") boolean localLlmVlEnabled,
            @Value("${extractor.docling-enabled:false}") boolean doclingEnabled,
            @Value("${extractor.mineru-enabled:false}")  boolean mineruEnabled) {

        this.store = store;
        this.executor = executor;
        this.confidenceThreshold = confidenceThreshold;

        // Selection chain: local first (default winner), cloud second (fallback).
        if (localLlmVlEnabled) {
            InvoiceExtractor local = localLlmVlProvider.getIfAvailable();
            if (local == null) {
                throw new IllegalStateException(
                        "extractor.local-llm-vl-enabled=true but no localLlmVlExtractor bean exists. "
                        + "Check the @ConditionalOnProperty wiring and the local-llm-vl config block.");
            }
            selectionChain.add(local);
        }
        if (cloudLlmVlEnabled) {
            selectionChain.add(cloudLlmVl);
        }
        if (selectionChain.isEmpty()) {
            throw new IllegalStateException(
                    "At least one vision extractor must be enabled (local_llm_vl / cloud_llm_vl).");
        }

        // Supplementary sources: run always, persist results, never selected.
        if (doclingEnabled) {
            InvoiceExtractor d = doclingProvider.getIfAvailable();
            if (d != null) supplementary.add(d);
            else log.warn("extractor.docling-enabled=true but doclingExtractorClient bean is absent");
        }
        if (mineruEnabled) {
            InvoiceExtractor m = mineruProvider.getIfAvailable();
            if (m != null) supplementary.add(m);
            else log.warn("extractor.mineru-enabled=true but mineruExtractorClient bean is absent");
        }

        log.info("InvoiceExtractionOrchestrator initialised: selection={} supplementary={} threshold={} (warn-only)",
                selectionChain.stream().map(InvoiceExtractor::extractorName).toList(),
                supplementary.stream().map(InvoiceExtractor::extractorName).toList(),
                confidenceThreshold);
    }

    /** Per-source extraction outcome — produced by {@link #runAndPersistOne}. */
    private record Attempt(String source, boolean success, boolean isSelectionSource,
                           InvoiceDocument doc, List<LlmTrace> traces, String error, long durationMs) {}

    public InvoiceDocument runAndPersist(String sessionId, byte[] pdfBytes, String filename) {
        return runAndPersist(sessionId, pdfBytes, filename, CheckEventPublisher.NOOP);
    }

    /**
     * Stage 1b main entry. Fires every enabled source (selection chain + supplementary)
     * as its own future on {@code lcCheckExecutor}; total wall-clock is roughly
     * {@code max(perSource)}.
     */
    public InvoiceDocument runAndPersist(String sessionId, byte[] pdfBytes, String filename,
                                          CheckEventPublisher publisher) {
        // Fire all sources in parallel — selection chain and supplementary alike.
        List<InvoiceExtractor> allSources = Stream
                .concat(selectionChain.stream(), supplementary.stream())
                .toList();

        List<String> sourceNames = allSources.stream()
                .map(InvoiceExtractor::extractorName).toList();
        int totalSources = allSources.size();
        AtomicInteger doneCount = new AtomicInteger(0);

        publisher.progress("invoice_extract",
                "Running " + totalSources + " extractor"
                        + (totalSources == 1 ? "" : "s") + ": "
                        + String.join(", ", sourceNames),
                Map.of("sources", sourceNames, "done", 0, "total", totalSources));

        // Capture OTel context (the active stage span) and the MDC sessionId
        // BEFORE handing off to the executor, then restore both inside each
        // worker. Without this, supplyAsync runs on a thread with empty
        // context — extractor spans become trace roots and lose
        // langfuse.trace.id (read from MDC), and the trace tree fragments.
        Context capturedOtelCtx = Context.current();
        String mdcSessionId = org.slf4j.MDC.get(MdcKeys.SESSION_ID);

        List<CompletableFuture<Attempt>> futures = allSources.stream()
                .map(e -> {
                    boolean isSelection = selectionChain.contains(e);
                    return CompletableFuture.supplyAsync(
                            () -> {
                                String prev = org.slf4j.MDC.get(MdcKeys.SESSION_ID);
                                if (mdcSessionId != null) org.slf4j.MDC.put(MdcKeys.SESSION_ID, mdcSessionId);
                                try (Scope scope = capturedOtelCtx.makeCurrent()) {
                                    return runAndPersistOne(e, isSelection, sessionId, pdfBytes, filename, publisher);
                                } finally {
                                    if (prev == null) org.slf4j.MDC.remove(MdcKeys.SESSION_ID);
                                    else org.slf4j.MDC.put(MdcKeys.SESSION_ID, prev);
                                }
                            },
                            executor)
                        .whenComplete((attempt, ex) -> {
                            int done = doneCount.incrementAndGet();
                            String label = attempt != null ? attempt.source() : e.extractorName();
                            String outcome = attempt != null && attempt.success() ? "ok"
                                    : (attempt != null ? "failed" : "errored");
                            long dur = attempt != null ? attempt.durationMs() : 0L;
                            publisher.progress("invoice_extract",
                                    label + " " + outcome + " (" + dur + "ms) · "
                                            + done + "/" + totalSources + " sources complete",
                                    Map.of(
                                            "source", label,
                                            "status", outcome,
                                            "durationMs", dur,
                                            "done", done,
                                            "total", totalSources));
                        });
                })
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Unexpected lane failure in invoice extraction: {}", e.getMessage(), e);
        }

        List<Attempt> attempts = futures.stream()
                .map(f -> f.getNow(null))
                .filter(Objects::nonNull)
                .toList();

        // Selection: first SUCCESS among selection-chain sources only.
        Attempt selected = attempts.stream()
                .filter(a -> a.isSelectionSource() && a.success())
                .findFirst()
                .orElse(null);
        if (selected == null) {
            throw new ExtractionException("none", ExtractorErrorCode.UNKNOWN,
                    "All vision extractors failed; see pipeline_steps rows for details");
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
     * Never throws.
     */
    private Attempt runAndPersistOne(InvoiceExtractor e, boolean isSelectionSource,
                                     String sessionId, byte[] pdfBytes, String filename,
                                     CheckEventPublisher publisher) {
        String source = e.extractorName();
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
            return new Attempt(source, true, isSelectionSource, r.document(), r.llmCalls(), null, dur);
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
            return new Attempt(source, false, isSelectionSource, null, trc, ex.getMessage(), dur);
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
            return new Attempt(source, false, isSelectionSource, null, List.of(), msg, dur);
        }
    }

    /**
     * Names of all active sources in declaration order (selection chain first,
     * then supplementary). UI uses this for pre-populated PENDING cards.
     */
    public List<String> configuredSources() {
        return Stream.concat(selectionChain.stream(), supplementary.stream())
                .map(InvoiceExtractor::extractorName)
                .toList();
    }

    /** Source of the most recently selected attempt — debug / observability only. */
    public String lastUsedSource() {
        return lastUsed;
    }

    /**
     * Debug helper — runs every enabled source in order and returns a plain-text
     * dump. Used by {@code /api/v1/debug/invoice/compare}. Does NOT persist to DB.
     */
    public String compareAllAsText(byte[] pdfBytes, String filename) {
        StringBuilder sb = new StringBuilder();
        Stream.concat(selectionChain.stream(), supplementary.stream()).forEach(e -> {
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
        });
        return sb.toString();
    }
}
