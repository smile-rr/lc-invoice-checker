package com.lc.checker.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.extractor.vision.VisionLlmExtractor;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Routes invoice extraction through an ordered chain of {@link InvoiceExtractor}
 * implementations, falling back to the next in the chain when an extractor throws a
 * {@link ExtractionException} with {@link ExtractorErrorCode#isFallbackCandidate()}=true.
 *
 * <p>Default chain (V1.5):
 * <pre>
 * vision → docling → mineru
 * </pre>
 *
 * <p>Each extractor can be individually enabled / disabled via config:
 * <ul>
 *   <li>{@code extractor.vision-enabled: true}  — Vision LLM primary (default)</li>
 *   <li>{@code extractor.docling-enabled: true} — Docling fallback #1 (default)</li>
 *   <li>{@code extractor.mineru-enabled: false} — Mineru fallback #2 (V1.5, off by default)</li>
 * </ul>
 *
 * <p>Vision LLM is provider-agnostic — set {@code vision.provider}, {@code vision.base-url},
 * and {@code vision.model} to switch between Ollama (local), Qwen Cloud, or Gemini.
 * See {@code vision/VisionLlmExtractor.java} for details.
 */
@Component
public class ExtractorRouter implements InvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExtractorRouter.class);

    private final List<InvoiceExtractor> chain = new ArrayList<>();
    private volatile String lastUsed = "vision";

    public ExtractorRouter(
            VisionLlmExtractor visionExtractor,
            @Qualifier(ExtractorClientConfig.DOCLING_REST_CLIENT) RestClient doclingClient,
            @Qualifier(ExtractorClientConfig.MINERU_REST_CLIENT)  RestClient mineruClient,
            ExtractorResponseMapper doclingMapper,
            ObjectMapper json,
            @Value("${extractor.retries:1}") int retries,
            @Value("${extractor.retry-backoff-ms:500}") long retryBackoffMs,
            @Value("${extractor.vision-enabled:true}")  boolean visionEnabled,
            @Value("${extractor.docling-enabled:true}") boolean doclingEnabled,
            @Value("${extractor.mineru-enabled:false}") boolean mineruEnabled) {

        if (visionEnabled) {
            chain.add(visionExtractor);
        }
        if (doclingEnabled) {
            chain.add(new HttpInvoiceExtractor(doclingClient, "docling", doclingMapper, json, retries, retryBackoffMs));
        }
        if (mineruEnabled) {
            chain.add(new HttpInvoiceExtractor(mineruClient,  "mineru",  doclingMapper, json, retries, retryBackoffMs));
        }

        if (chain.isEmpty()) {
            throw new IllegalStateException(
                    "At least one extractor must be enabled. Set extractor.vision-enabled=true, "
                            + "extractor.docling-enabled=true, or extractor.mineru-enabled=true.");
        }

        log.info("ExtractorRouter initialised: chain={}", chain.stream()
                .map(InvoiceExtractor::extractorName).toList());
    }

    @Override
    public InvoiceDocument extract(byte[] pdfBytes, String filename) {
        List<String> tried = new ArrayList<>();
        ExtractionException lastException = null;

        for (InvoiceExtractor extractor : chain) {
            tried.add(extractor.extractorName());
            try {
                InvoiceDocument result = extractor.extract(pdfBytes, filename);
                lastUsed = extractor.extractorName();
                return result;
            } catch (ExtractionException e) {
                lastException = e;
                if (!e.isFallbackCandidate()) {
                    // Client-input or client-bug error — no point retrying
                    throw e;
                }
                log.warn("Extractor '{}' failed with {}; falling back to next in chain",
                        extractor.extractorName(), e.getCode());
            }
        }

        // All extractors failed
        String primary = tried.isEmpty() ? "none" : tried.get(0);
        String fallback = tried.size() > 1 ? tried.get(tried.size() - 1) : "none";
        throw new ExtractionException(
                primary + "+" + fallback,
                lastException != null ? lastException.getCode() : ExtractorErrorCode.UNKNOWN,
                "All extractors failed. Tried: " + tried + ". Last error: "
                        + (lastException != null ? lastException.getMessage() : "unknown"),
                lastException);
    }

    @Override
    public String extractorName() {
        return lastUsed;
    }
}
