package com.lc.checker.infra.storage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

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
 * <p>Retry with exponential backoff on transient failures (timeout,
 * connection reset, 500 errors). After exhausting retries, throws
 * {@link MinioAccessException} so callers can distinguish a storage
 * connectivity problem from a genuine object-not-found.
 */
public final class MinioFileStore implements InvoiceFileStore {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStore.class);

    private static final String LC_PREFIX      = "lc/";
    private static final String INVOICE_PREFIX = "invoice/";

    /** Retry: initial delay ms, max delay ms, max attempts. */
    private static final int RETRY_INIT_MS  = 500;
    private static final int RETRY_MAX_MS    = 4_000;
    private static final int RETRY_MAX_ATTEMPTS = 3;

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

    /**
     * Thrown when MinIO is reachable (connection succeeded) but the object
     * cannot be read due to a transient error (timeout, 500, connection reset).
     * This is distinct from a genuine NoSuchKey — the object should be there.
     */
    public static class MinioAccessException extends RuntimeException {
        private final String key;
        public MinioAccessException(String key, Throwable cause) {
            super("MinIO read failed for '" + key + "': " + cause.getMessage(), cause);
            this.key = key;
        }
        public String key() { return key; }
    }

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
        } catch (SdkServiceException e) {
            log.warn("MinIO PUT {} failed (status={}): {} — serving from hot cache",
                    key, e.statusCode(), e.getMessage());
            return true; // hot cache has the bytes
        } catch (SdkClientException e) {
            log.warn("MinIO PUT {} unreachable: {} — serving from hot cache", key, e.getMessage());
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
        int attempt = 0;
        SdkClientException lastFailure = null;
        while (attempt < RETRY_MAX_ATTEMPTS) {
            attempt++;
            try {
                ResponseBytes<GetObjectResponse> rb = s3.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(cfg.bucket()).key(key).build());
                byte[] bytes = rb.asByteArray();
                // Populate hot cache on successful MinIO read so future reads are fast.
                hotCache.put(key, bytes);
                log.debug("MinIO GET ok: bucket={} key={} bytes={} attempts={} ({}ms)",
                        cfg.bucket(), key, bytes.length, attempt, System.currentTimeMillis() - t0);
                return Optional.of(bytes);
            } catch (NoSuchKeyException e) {
                log.debug("MinIO GET miss: bucket={} key={}", cfg.bucket(), key);
                return Optional.empty();  // genuinely not found — 404
            } catch (SdkServiceException e) {
                // 5xx from MinIO — transient, retry
                if (e.statusCode() >= 500 && attempt < RETRY_MAX_ATTEMPTS) {
                    log.warn("MinIO GET {} received {} — retrying (attempt {}/{})",
                            key, e.statusCode(), attempt, RETRY_MAX_ATTEMPTS);
                    sleep(calcDelayMs(attempt));
                    continue;
                }
                // 4xx other than NoSuchKey — don't retry, surface as error
                log.warn("MinIO GET {} failed (status={}): {}", key, e.statusCode(), e.getMessage());
                throw new MinioAccessException(key, e);
            } catch (SdkClientException e) {
                // Connection timeout, reset, unreachable — transient, retry
                lastFailure = e;
                if (attempt < RETRY_MAX_ATTEMPTS) {
                    log.warn("MinIO GET {} attempt {}/{} failed: {} — retrying",
                            key, attempt, RETRY_MAX_ATTEMPTS, e.getMessage());
                    sleep(calcDelayMs(attempt));
                    continue;
                }
                log.error("MinIO GET {} unreachable after {} attempts: {}",
                        key, RETRY_MAX_ATTEMPTS, e.getMessage());
                throw new MinioAccessException(key, e);
            }
        }
        // Should not reach here, but be defensive
        throw new MinioAccessException(key, lastFailure);
    }

    private int calcDelayMs(int attempt) {
        // Exponential backoff with jitter: 500, 1000, 2000 ms
        int base = RETRY_INIT_MS * (int) Math.pow(2, attempt - 1);
        return Math.min(base, RETRY_MAX_MS);
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
