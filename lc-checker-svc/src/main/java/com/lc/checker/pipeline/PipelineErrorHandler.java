package com.lc.checker.pipeline;

import com.lc.checker.infra.persistence.CheckSessionStore;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shared error sink for every stage that runs after the session row exists
 * (i.e. everything except {@code lc_parse}). Mirrors the
 * {@code ComplianceEngine#handlePipelineError} helper that this refactor replaces —
 * marks the session row as failed without raising the secondary exception so the
 * caller still sees the original cause.
 */
@Component
public class PipelineErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(PipelineErrorHandler.class);

    private final CheckSessionStore store;

    public PipelineErrorHandler(CheckSessionStore store) {
        this.store = store;
    }

    public void onStageFailure(String sessionId, String stageName, RuntimeException e) {
        log.error("Pipeline failed at stage {}: {}", stageName, e.getMessage());
        try {
            store.finalizeSession(sessionId, null,
                    stageName + ": " + e.getMessage(), null, Instant.now());
        } catch (Exception ex) {
            log.error("Failed to record pipeline error to DB: {}", ex.getMessage());
        }
    }
}
