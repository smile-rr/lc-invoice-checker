package com.lc.checker.infra.persistence;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lc.checker.domain.session.CheckSession;
import com.lc.checker.domain.result.DiscrepancyReport;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.lc.checker.infra.persistence.jdbc.JdbcCheckSessionStore;

/**
 * In-process Caffeine cache of fully-built {@link CheckSession} objects. Acts as L1
 * in {@link CachingCheckSessionStore}. Progressive write methods are no-ops — all
 * per-step state lives in PostgreSQL via {@code JdbcCheckSessionStore}.
 */
@Component
public class CaffeineCheckSessionStore implements CheckSessionStore {

    private static final Logger log = LoggerFactory.getLogger(CaffeineCheckSessionStore.class);

    private final Cache<String, CheckSession> cache;

    public CaffeineCheckSessionStore(
            @Value("${session.ttl-minutes:60}") long ttlMinutes,
            @Value("${session.max-size:1000}") long maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(java.time.Duration.ofMinutes(ttlMinutes))
                .recordStats()
                .build();
        log.info("CaffeineCheckSessionStore initialised: ttl={}min maxSize={}", ttlMinutes, maxSize);
    }

    @Override
    public void put(CheckSession session) {
        cache.put(session.sessionId(), session);
    }

    @Override
    public Optional<CheckSession> find(String sessionId) {
        return Optional.ofNullable(cache.getIfPresent(sessionId));
    }

    @Override
    public long size() {
        return cache.estimatedSize();
    }

    // ── Progressive recording: L1 holds only fully-built sessions; no-ops here. ──

    @Override public void createSession(String sessionId, String lcReference, String beneficiary, String applicant) {}

    @Override public void finalizeSession(String sessionId, Boolean compliant, String error,
                                          DiscrepancyReport finalReport, Instant completedAt) {}

    @Override public void putStep(String sessionId, String stage, String stepKey, String status,
                                  Instant startedAt, Instant completedAt, Object result, String error) {}

    @Override public void markExtractSelected(String sessionId, String source) {}
}
