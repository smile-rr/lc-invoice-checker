package com.lc.checker.infra.storage;

import java.util.Optional;

/**
 * Fallback when {@code storage.minio.enabled=false} or the bucket bootstrap
 * fails. All writes silently succeed-as-noop and reads return empty, so the
 * controller can keep operating on its in-memory hot cache without surfacing
 * storage errors to the user.
 */
final class NoopInvoiceFileStore implements InvoiceFileStore {
    @Override public boolean isEnabled() { return false; }
    @Override public boolean putLcIfAbsent(String sha256, String filename, byte[] bytes) { return false; }
    @Override public boolean putInvoiceIfAbsent(String sha256, String filename, byte[] bytes) { return false; }
    @Override public Optional<byte[]> getLc(String sha256) { return Optional.empty(); }
    @Override public Optional<byte[]> getInvoice(String sha256) { return Optional.empty(); }
}
