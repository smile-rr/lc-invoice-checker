package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.infra.samples.SampleManifest;
import com.lc.checker.infra.samples.SamplesRegistry;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pre-defined LC + invoice sample pairs for the upload page picker.
 *
 * <p>Three endpoints:
 * <ul>
 *   <li>{@code GET /api/v1/samples}                — list manifest (id, title, note, kind, lc/invoice filenames)</li>
 *   <li>{@code GET /api/v1/samples/{id}/lc?variant=pass|fail} — MT700 text bytes</li>
 *   <li>{@code GET /api/v1/samples/{id}/invoice}   — invoice PDF bytes</li>
 * </ul>
 *
 * <p>Backed by {@link SamplesRegistry} which reads from
 * {@code classpath:/samples/}. We intentionally do NOT use MinIO for these
 * curated demo assets — see SamplesRegistry javadoc for rationale.
 */
@RestController
@RequestMapping("/api/v1/samples")
public class SamplesController {

    private static final Logger log = LoggerFactory.getLogger(SamplesController.class);

    private final SamplesRegistry registry;

    public SamplesController(SamplesRegistry registry) {
        this.registry = registry;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SampleSummary(
            String id,
            String title,
            String note,
            String kind,
            String invoiceFilename,
            String lcPassFilename,
            String lcFailFilename) {}

    @GetMapping
    public List<SampleSummary> list() {
        return registry.all().stream()
                .map(s -> new SampleSummary(
                        s.id(), s.title(), s.note(), s.kind(),
                        s.invoice(), s.passLc(), s.failLc()))
                .toList();
    }

    /**
     * MT700 text for a sample. {@code variant} is "pass" (default) or "fail".
     * Returns 404 when the sample id is unknown or the requested variant is
     * absent for that sample (e.g. fail variant not authored).
     */
    @GetMapping(path = "/{id}/lc", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> lc(
            @PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(value = "variant", defaultValue = "pass") String variant) {
        SampleManifest s = registry.byId(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        String filename = "fail".equalsIgnoreCase(variant) ? s.failLc() : s.passLc();
        if (filename == null || filename.isBlank()) return ResponseEntity.notFound().build();
        return readResponse(filename, MediaType.TEXT_PLAIN);
    }

    @GetMapping(path = "/{id}/invoice")
    public ResponseEntity<byte[]> invoice(@PathVariable String id) {
        return registry.byId(id)
                .map(s -> readResponse(s.invoice(), MediaType.APPLICATION_PDF))
                .orElseGet(() -> ResponseEntity.<byte[]>notFound().build());
    }

    private ResponseEntity<byte[]> readResponse(String filename, MediaType contentType) {
        try {
            byte[] bytes = registry.readBytes(filename);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(contentType);
            headers.setContentLength(bytes.length);
            // inline so the browser/<embed> can render in-place; download buttons
            // in the UI use download-attribute on the anchor to force-save instead.
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"");
            return ResponseEntity.ok().headers(headers).body(bytes);
        } catch (IOException e) {
            log.warn("samples: failed to read {}: {}", filename, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
