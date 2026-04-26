package com.lc.checker.infra.storage;

import java.util.Optional;

/**
 * Durable file storage for the LC text + invoice PDF that backs each session.
 *
 * <p>Content-addressed: keys derive from the SHA-256 of the bytes, not the
 * session id, so the same file uploaded across many sessions writes once and
 * is fetched many times. Sessions reference blobs by hash via the
 * {@code lc_sha256} / {@code invoice_sha256} columns on {@code check_sessions}.
 *
 * <p>The pipeline does not depend on this — when MinIO is unavailable callers
 * log a warning and degrade to memory-only behaviour. The {@code /lc-raw} and
 * {@code /invoice} endpoints will then 404 once the in-memory cache evicts
 * (typically on JVM restart).
 */
public interface InvoiceFileStore {

    /** Returns true when a real backend is wired (MinIO healthy on startup). */
    boolean isEnabled();

    /**
     * Upload LC text bytes if the content-addressed object isn't already
     * present. Idempotent — a second call with the same hash short-circuits on
     * a HEAD probe and is essentially free.
     *
     * @return true on success (whether or not the object already existed),
     *         false on any storage error (logged inside).
     */
    boolean putLcIfAbsent(String sha256, String filename, byte[] bytes);

    /**
     * Upload invoice PDF bytes content-addressed. See {@link #putLcIfAbsent}.
     */
    boolean putInvoiceIfAbsent(String sha256, String filename, byte[] bytes);

    /** Fetch LC text bytes by hash. Empty on miss / storage error. */
    Optional<byte[]> getLc(String sha256);

    /** Fetch invoice PDF bytes by hash. Empty on miss / storage error. */
    Optional<byte[]> getInvoice(String sha256);
}
