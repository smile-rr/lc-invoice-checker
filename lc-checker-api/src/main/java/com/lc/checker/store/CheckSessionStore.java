package com.lc.checker.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lc.checker.model.CheckSession;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory session store backed by Caffeine. One entry per {@code /lc-check} call,
 * keyed by the assigned {@code sessionId} UUID.
 *
 * <p>V1 keeps this in-process (sized + TTL'd). V2 swaps for Postgres / Redis via the
 * same interface — call sites only see {@link #put(CheckSession)} / {@link #find(String)}.
 */
@Component
public class CheckSessionStore {

    private static final Logger log = LoggerFactory.getLogger(CheckSessionStore.class);

    private final Cache<String, CheckSession> cache;

    public CheckSessionStore(
            @Value("${session.ttl-minutes:60}") long ttlMinutes,
            @Value("${session.max-size:1000}") long maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
                .recordStats()
                .build();
        log.info("CheckSessionStore initialised: ttl={}min maxSize={}", ttlMinutes, maxSize);
    }

    public void put(CheckSession session) {
        cache.put(session.sessionId(), session);
    }

    public Optional<CheckSession> find(String sessionId) {
        return Optional.ofNullable(cache.getIfPresent(sessionId));
    }

    public long size() {
        return cache.estimatedSize();
    }
}
