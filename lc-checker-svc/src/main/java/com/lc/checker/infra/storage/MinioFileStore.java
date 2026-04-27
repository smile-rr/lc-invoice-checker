package com.lc.checker.infra.storage;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * MinIO/S3 implementation. Content-addressed: object keys derive from the
 * SHA-256 of the bytes, so re-uploading identical files is a no-op after the
 * first put. Filename is preserved as object metadata for round-tripping
 * {@code Content-Disposition} on download.
 *
 * <p>No explicit OTel spans are emitted here. Earlier we wrapped each
 * put/get in a {@code minio.put} / {@code minio.get} span, but the put runs
 * inside the upload HTTP request scope while the get runs inside the
 * dispatcher's virtual-thread scope — those two contexts can't share a
 * single trace tree without intrusive refactoring. To keep Langfuse traces
 * clean and pipeline-rooted, MinIO ops are now logged at INFO/WARN only and
 * left out of the trace tree.
 */
final class MinioFileStore implements InvoiceFileStore {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStore.class);

    private static final String LC_PREFIX      = "lc/";
    private static final String INVOICE_PREFIX = "invoice/";

    private final S3Client s3;
    private final StorageProperties.Minio cfg;

    MinioFileStore(S3Client s3, StorageProperties.Minio cfg) {
        this.s3 = s3;
        this.cfg = cfg;
        log.info("MinioFileStore wired: endpoint={} bucket={}", cfg.endpoint(), cfg.bucket());
    }

    @Override public boolean isEnabled() { return true; }

    @Override
    public boolean putLcIfAbsent(String sha256, String filename, byte[] bytes) {
        return putIfAbsent(LC_PREFIX + sha256 + ".txt", "text/plain; charset=utf-8", filename, bytes);
    }

    @Override
    public boolean putInvoiceIfAbsent(String sha256, String filename, byte[] bytes) {
        return putIfAbsent(INVOICE_PREFIX + sha256 + ".pdf", "application/pdf", filename, bytes);
    }

    @Override
    public Optional<byte[]> getLc(String sha256) {
        return get(LC_PREFIX + sha256 + ".txt");
    }

    @Override
    public Optional<byte[]> getInvoice(String sha256) {
        return get(INVOICE_PREFIX + sha256 + ".pdf");
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private boolean putIfAbsent(String key, String contentType, String filename, byte[] bytes) {
        long t0 = System.currentTimeMillis();
        try {
            // headObject is the cheapest dedup probe — same bytes hash to the
            // same key, so HEAD success means we already have it.
            try {
                s3.headObject(HeadObjectRequest.builder().bucket(cfg.bucket()).key(key).build());
                log.debug("MinIO put dedup-hit: bucket={} key={} ({}ms)",
                        cfg.bucket(), key, System.currentTimeMillis() - t0);
                return true;
            } catch (NoSuchKeyException ignored) {
                // fall through to PUT
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    log.warn("MinIO HEAD {} failed (status={}): {}", key, e.statusCode(), e.getMessage());
                    return false;
                }
            }
            s3.putObject(PutObjectRequest.builder()
                            .bucket(cfg.bucket())
                            .key(key)
                            .contentType(contentType)
                            .metadata(filename == null ? null
                                    : java.util.Map.of("original-filename", safeFilename(filename)))
                            .build(),
                    RequestBody.fromBytes(bytes));
            log.info("MinIO PUT ok: bucket={} key={} bytes={} ({}ms)",
                    cfg.bucket(), key, bytes == null ? 0 : bytes.length,
                    System.currentTimeMillis() - t0);
            return true;
        } catch (RuntimeException e) {
            log.warn("MinIO PUT {} failed: {} ({})",
                    key, e.getMessage(), e.getClass().getSimpleName());
            return false;
        }
    }

    private Optional<byte[]> get(String key) {
        long t0 = System.currentTimeMillis();
        try {
            ResponseBytes<GetObjectResponse> rb = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(cfg.bucket()).key(key).build());
            byte[] bytes = rb.asByteArray();
            log.debug("MinIO GET ok: bucket={} key={} bytes={} ({}ms)",
                    cfg.bucket(), key, bytes.length, System.currentTimeMillis() - t0);
            return Optional.of(bytes);
        } catch (NoSuchKeyException e) {
            log.debug("MinIO GET miss: bucket={} key={}", cfg.bucket(), key);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("MinIO GET {} failed: {} ({})", key, e.getMessage(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** S3 object metadata is restricted to ASCII; filenames may contain CJK / spaces. */
    private static String safeFilename(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c < 0x20 || c > 0x7E ? '_' : c);
        }
        return out.toString();
    }
}
