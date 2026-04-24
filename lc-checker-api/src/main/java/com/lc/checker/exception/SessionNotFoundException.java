package com.lc.checker.exception;

/**
 * Raised by the trace endpoint when the session id is unknown (typo, TTL eviction, or
 * cross-instance lookup). Mapped by {@code GlobalExceptionHandler} to 404
 * {@code SESSION_NOT_FOUND}.
 */
public class SessionNotFoundException extends RuntimeException {
    private final String sessionId;

    public SessionNotFoundException(String sessionId) {
        super("Session not found: " + sessionId);
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
