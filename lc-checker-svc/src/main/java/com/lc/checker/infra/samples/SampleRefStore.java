package com.lc.checker.infra.samples;

import com.lc.checker.infra.storage.InvoiceFileStore;
import com.lc.checker.infra.storage.Sha256;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves sample LC / invoice files to their MinIO content-addressed keys
 * (SHA-256). Sample files are read from the filesystem path configured via
 * {@code samples.files-path} (default: {@code file:${user.dir}/test/golden/}).
 *
 * <p>On first access, the corresponding bytes are uploaded to MinIO under the
 * content-addressed key. Subsequent calls hit the dedup HEAD probe and are free.
 *
 * <p>If MinIO is unavailable, {@link #getLcSha256} / {@link #getInvoiceSha256}
 * return {@link Optional#empty} — callers must handle this and fail the request
 * with a 503 if storage is required.
 */
@Component
public class SampleRefStore {

    private static final Logger log = LoggerFactory.getLogger(SampleRefStore.class);

    private final SamplesRegistry registry;
    private final InvoiceFileStore fileStore;

    public SampleRefStore(SamplesRegistry registry, InvoiceFileStore fileStore) {
        this.registry = registry;
        this.fileStore = fileStore;
    }

    /**
     * Resolve the SHA-256 content-addressed key for a sample LC file.
     * Uploads to MinIO on first call (idempotent thereafter).
     *
     * @param sampleId the sample id from samples.yaml
     * @param variant  "pass" or "fail"
     * @return SHA-256 hex string, or empty if MinIO is unavailable
     */
    public Optional<String> getLcSha256(String sampleId, String variant) {
        return registry.byId(sampleId)
                .flatMap(s -> resolveLc(s, variant));
    }

    /**
     * Resolve the SHA-256 content-addressed key for a sample invoice PDF.
     * Uploads to MinIO on first call (idempotent thereafter).
     *
     * @param sampleId the sample id from samples.yaml
     * @return SHA-256 hex string, or empty if MinIO is unavailable or this sample has no invoice
     */
    public Optional<String> getInvoiceSha256(String sampleId) {
        return registry.byId(sampleId)
                .flatMap(this::resolveInvoice);
    }

    /**
     * Returns the filename associated with the LC of a given sample/variant.
     * Used to record the original filename in the session row.
     */
    public Optional<String> getLcFilename(String sampleId, String variant) {
        return registry.byId(sampleId)
                .map(s -> "fail".equalsIgnoreCase(variant) ? s.failLc() : s.passLc())
                .filter(f -> f != null && !f.isBlank());
    }

    /**
     * Returns the filename associated with the invoice of a given sample.
     */
    public Optional<String> getInvoiceFilename(String sampleId) {
        return registry.byId(sampleId).map(SampleManifest::invoice);
    }

    /**
     * Returns raw LC bytes for a sample/variant. Does NOT upload to MinIO — caller
     * is responsible for caching or storing the bytes. Used when MinIO is unavailable
     * and the controller needs to cache sample LC bytes in memory for the session.
     */
    public Optional<byte[]> getLcBytes(String sampleId, String variant) {
        return registry.byId(sampleId)
                .flatMap(s -> {
                    String filename = "fail".equalsIgnoreCase(variant) ? s.failLc() : s.passLc();
                    if (filename == null || filename.isBlank()) {
                        return Optional.<byte[]>empty();
                    }
                    try {
                        return Optional.of(registry.readBytes(filename));
                    } catch (IOException e) {
                        log.error("SampleRefStore: failed to read LC from classpath: {}", filename, e);
                        return Optional.<byte[]>empty();
                    }
                });
    }

    /**
     * Returns raw invoice bytes for a sample. Does NOT upload to MinIO — caller
     * is responsible for caching or storing the bytes.
     */
    public Optional<byte[]> getInvoiceBytes(String sampleId) {
        return registry.byId(sampleId)
                .flatMap(s -> {
                    String filename = s.invoice();
                    if (filename == null || filename.isBlank()) {
                        return Optional.<byte[]>empty();
                    }
                    try {
                        return Optional.of(registry.readBytes(filename));
                    } catch (IOException e) {
                        log.error("SampleRefStore: failed to read invoice from classpath: {}", filename, e);
                        return Optional.<byte[]>empty();
                    }
                });
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private Optional<String> resolveLc(SampleManifest s, String variant) {
        String filename = "fail".equalsIgnoreCase(variant) ? s.failLc() : s.passLc();
        if (filename == null || filename.isBlank()) {
            log.warn("SampleRefStore: no LC file for sample={} variant={}", s.id(), variant);
            return Optional.empty();
        }
        return uploadLc(filename);
    }

    private Optional<String> resolveInvoice(SampleManifest s) {
        if (s.invoice() == null || s.invoice().isBlank()) {
            return Optional.empty();
        }
        return uploadInvoice(s.invoice());
    }

    private Optional<String> uploadLc(String filename) {
        try {
            byte[] bytes = registry.readBytes(filename);
            String sha = Sha256.hex(bytes);
            String lcName = filename;
            if (!fileStore.putLcIfAbsent(sha, lcName, bytes)) {
                log.warn("SampleRefStore: MinIO upload failed for LC sample={}", filename);
                return Optional.empty();
            }
            log.debug("SampleRefStore: resolved LC {} → sha={}", filename, sha.substring(0, 12));
            return Optional.of(sha);
        } catch (IOException e) {
            log.error("SampleRefStore: failed to read LC from classpath: {}", filename, e);
            return Optional.empty();
        }
    }

    private Optional<String> uploadInvoice(String filename) {
        try {
            byte[] bytes = registry.readBytes(filename);
            String sha = Sha256.hex(bytes);
            String invName = filename;
            if (!fileStore.putInvoiceIfAbsent(sha, invName, bytes)) {
                log.warn("SampleRefStore: MinIO upload failed for invoice sample={}", filename);
                return Optional.empty();
            }
            log.debug("SampleRefStore: resolved invoice {} → sha={}", filename, sha.substring(0, 12));
            return Optional.of(sha);
        } catch (IOException e) {
            log.error("SampleRefStore: failed to read invoice from classpath: {}", filename, e);
            return Optional.empty();
        }
    }
}
