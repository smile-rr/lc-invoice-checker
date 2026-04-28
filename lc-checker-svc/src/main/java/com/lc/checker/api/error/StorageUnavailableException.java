package com.lc.checker.api.error;

/**
 * Raised when a file is known (session exists in DB with SHA-256) but cannot
 * be retrieved from durable storage (MinIO). This is distinct from a 404
 * SESSION_NOT_FOUND — the session is there, the bytes are not.
 *
 * <p>Results in HTTP 503 {@code STORAGE_UNAVAILABLE} so the UI can show a
 * meaningful message ("PDF unavailable — storage error") rather than a generic
 * "Missing PDF" which is ambiguous between "unknown session" and
 * "file lost due to MinIO restart / misconfiguration".
 */
public class StorageUnavailableException extends RuntimeException {

    private final String sessionId;
    private final String fileType;  // "invoice" or "lc_raw"

    public StorageUnavailableException(String sessionId, String fileType, String reason) {
        super(fileType + " file unavailable for session " + sessionId + ": " + reason);
        this.sessionId = sessionId;
        this.fileType = fileType;
    }

    public String sessionId() { return sessionId; }
    public String fileType()  { return fileType; }
}
