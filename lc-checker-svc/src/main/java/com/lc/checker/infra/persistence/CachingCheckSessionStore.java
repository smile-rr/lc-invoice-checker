package com.lc.checker.infra.persistence;

import com.lc.checker.domain.session.CheckSession;
import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.infra.persistence.jdbc.JdbcCheckSessionStore;
import com.lc.checker.infra.stream.CheckEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * L1 Caffeine (in-process) + L2 PostgreSQL/JDBC composite store.
 *
 * <p>Write policy — progressive writes go straight to L2. L1 is back-filled at
 * {@link #find} time (and after {@link #finalizeSession}) for fast subsequent reads.
 */
@Component
@Primary
public class CachingCheckSessionStore implements CheckSessionStore {

    private static final Logger log = LoggerFactory.getLogger(CachingCheckSessionStore.class);

    private final CaffeineCheckSessionStore l1;
    private final JdbcCheckSessionStore l2;

    public CachingCheckSessionStore(CaffeineCheckSessionStore l1, JdbcCheckSessionStore l2) {
        this.l1 = l1;
        this.l2 = l2;
        log.info("CachingCheckSessionStore initialised: L1=Caffeine, L2=PostgreSQL/JDBC");
    }

    @Override
    public void put(CheckSession session) {
        l1.put(session);
        l2.put(session);
    }

    @Override
    public Optional<CheckSession> find(String sessionId) {
        Optional<CheckSession> cached = l1.find(sessionId);
        if (cached.isPresent()) return cached;
        Optional<CheckSession> fromDb = l2.find(sessionId);
        fromDb.ifPresent(l1::put);
        return fromDb;
    }

    @Override
    public long size() {
        return l1.size();
    }

    @Override
    public void createSession(String sessionId, String lcReference, String beneficiary, String applicant) {
        l2.createSession(sessionId, lcReference, beneficiary, applicant);
    }

    @Override
    public void finalizeSession(String sessionId, Boolean compliant, String error,
                                DiscrepancyReport finalReport, Instant completedAt) {
        l2.finalizeSession(sessionId, compliant, error, finalReport, completedAt);
        // Force-refresh L1 from L2 directly. Calling find() here would short-
        // circuit on any partial CheckSession cached during streaming (when
        // /trace was hit before the pipeline finished), leaving the cache with
        // a stale final_report=null entry forever.
        l2.find(sessionId).ifPresent(l1::put);
    }

    @Override
    public void putStep(String sessionId, String stage, String stepKey, String status,
                        Instant startedAt, Instant completedAt, Object result, String error) {
        l2.putStep(sessionId, stage, stepKey, status, startedAt, completedAt, result, error);
    }

    @Override
    public void markExtractSelected(String sessionId, String source) {
        l2.markExtractSelected(sessionId, source);
    }

    @Override
    public void recordSessionFiles(String sessionId,
                                    String lcFilename, String lcSha256,
                                    String invoiceFilename, String invoiceSha256) {
        l2.recordSessionFiles(sessionId, lcFilename, lcSha256, invoiceFilename, invoiceSha256);
    }

    @Override
    public Optional<SessionFileRefs> findSessionFiles(String sessionId) {
        return l2.findSessionFiles(sessionId);
    }

    @Override
    public List<SessionSummary> findRecent(int limit) {
        return l2.findRecent(limit);
    }

    @Override
    public List<ExtractAttempt> findInvoiceExtracts(String sessionId) {
        return l2.findInvoiceExtracts(sessionId);
    }

    @Override
    public void appendEvent(String sessionId, CheckEvent event) {
        l2.appendEvent(sessionId, event);
    }

    @Override
    public List<CheckEvent> findEvents(String sessionId) {
        return l2.findEvents(sessionId);
    }
}
