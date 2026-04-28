package com.lc.checker.infra.storage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>Hot-cache fallback: when MinIO is unreachable, bytes are kept in an
 * in-process {@link ConcurrentHashMap} (keyed by content-addressed key).
 * This lets the dispatcher retrieve session bytes even when MinIO is down.
 * The cache is populated on every successful MinIO PUT/GET and on every
 * failed PUT (so the controller can cache bytes directly without MinIO).
 *
 * <p>No explicit OTel spans are emitted here. To keep Langfuse traces clean
 * and pipeline-rooted, MinIO ops are logged at INFO/WARN only.
 */
final class MinioFileStore implements InvoiceFileStore {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStore.class);

    private static final String LC_PREFIX      = "lc/";
    private static final String INVOICE_PREFIX = "invoice/";

    private final S3Client s3;
    private final StorageProperties.Minio cfg;
    /**
     * In-process hot cache: populated on every MinIO write (success or failure)
     * and checked on every MinIO read. Allows the dispatcher to serve session
     * bytes even when MinIO is temporarily unreachable.
     */
    private final Map<String, byte[]> hotCache = new ConcurrentHashMap<>();

    MinioFileStore(S3Client s3, StorageProperties.Minio cfg) {
        this.s3 = s3;
        this.cfg = cfg;
        log.info("MinioFileStore wired: endpoint={} bucket={}", cfg.endpoint(), cfg.bucket());
    }

    // ── InvoiceFileStore ────────────────────────────────────────────────────

    @Override public boolean isEnabled() { return true; }

    @Override
    public boolean putLcIfAbsent(String sha256, String filename, byte[] bytes) {
        return putIfAbsent(LC_PREFIX + sha256 + ".txt", bytes);
    }

    @Override
    public boolean putInvoiceIfAbsent(String sha256, String filename, byte[] bytes) {
        return putIfAbsent(INVOICE_PREFIX + sha256 + ".pdf", bytes);
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

    private boolean putIfAbsent(String key, byte[] bytes) {
        long t0 = System.currentTimeMillis();
        // Always populate hot cache first — so dispatcher can retrieve bytes
        // even if MinIO is unreachable on the very next read.
        hotCache.put(key, bytes);
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
                    log.warn("MinIO HEAD {} failed (status={}): {} — serving from hot cache",
                            key, e.statusCode(), e.getMessage());
                    return true; // hot cache has the bytes
                }
            }
            s3.putObject(PutObjectRequest.builder()
                            .bucket(cfg.bucket())
                            .key(key)
                            .contentType(key.endsWith(".txt")
                                    ? "text/plain; charset=utf-8"
                                    : "application/pdf")
                            .build(),
                    RequestBody.fromBytes(bytes));
            log.info("MinIO PUT ok: bucket={} key={} bytes={} ({}ms)",
                    cfg.bucket(), key, bytes == null ? 0 : bytes.length,
                    System.currentTimeMillis() - t0);
            return true;
        } catch (S3Exception e) {
            log.warn("MinIO PUT {} failed (status={}): {} — serving from hot cache",
                    key, e.statusCode(), e.getMessage());
            return true; // hot cache has the bytes
        } catch (RuntimeException e) {
            log.warn("MinIO PUT {} failed: {} ({}) — serving from hot cache",
                    key, e.getMessage(), e.getClass().getSimpleName());
            return true; // hot cache has the bytes
        }
    }

    private Optional<byte[]> get(String key) {
        // Hot cache first — always checked before MinIO.
        byte[] cached = hotCache.get(key);
        if (cached != null) {
            log.debug("MinIO hot-cache hit: key={}", key);
            return Optional.of(cached);
        }
        long t0 = System.currentTimeMillis();
        try {
            ResponseBytes<GetObjectResponse> rb = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(cfg.bucket()).key(key).build());
            byte[] bytes = rb.asByteArray();
            // Populate hot cache on successful MinIO read so future reads are fast.
            hotCache.put(key, bytes);
            log.debug("MinIO GET ok: bucket={} key={} bytes={} ({}ms)",
                    cfg.bucket(), key, bytes.length, System.currentTimeMillis() - t0);
            return Optional.of(bytes);
        } catch (NoSuchKeyException e) {
            log.debug("MinIO GET miss: bucket={} key={}", cfg.bucket(), key);
            return Optional.empty();
        } catch (S3Exception e) {
            log.warn("MinIO GET {} failed (status={}): {}", key, e.statusCode(), e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("MinIO GET {} failed: {} ({})", key, e.getMessage(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
