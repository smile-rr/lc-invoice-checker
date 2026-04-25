package com.lc.checker.api.controller;

import com.lc.checker.pipeline.LcCheckPipeline;
import com.lc.checker.api.exception.SessionNotFoundException;
import com.lc.checker.domain.session.CheckSession;
import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.persistence.CheckSessionStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.lc.checker.infra.observability.MdcFilter;

/**
 * Public V1 API surface.
 *
 * <ul>
 *   <li>{@code POST /api/v1/lc-check} — single-shot multipart endpoint: supply LC text +
 *       invoice PDF, receive {@link DiscrepancyReport} synchronously.</li>
 *   <li>{@code GET /api/v1/lc-check/{sessionId}/trace} — full intermediate state of a
 *       previous run, for UI / debugging.</li>
 * </ul>
 *
 * <p>{@code sessionId} is sourced from the {@link MdcKeys#SESSION_ID} value that
 * {@code MdcFilter} puts into MDC on every request, so the log lines emitted during
 * the pipeline and the returned report are trivially correlatable.
 */
@RestController
@RequestMapping("/api/v1/lc-check")
public class LcCheckController {

    private static final Logger log = LoggerFactory.getLogger(LcCheckController.class);

    private final LcCheckPipeline pipeline;
    private final CheckSessionStore store;

    public LcCheckController(LcCheckPipeline pipeline, CheckSessionStore store) {
        this.pipeline = pipeline;
        this.store = store;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<DiscrepancyReport> check(
            @RequestPart("lc") MultipartFile lc,
            @RequestPart("invoice") MultipartFile invoice) throws IOException {
        String sessionId = MDC.get(MdcKeys.SESSION_ID);
        log.info("Received lc-check: lcBytes={} invoiceBytes={} invoiceName={}",
                lc.getSize(), invoice.getSize(), invoice.getOriginalFilename());

        String lcText = new String(lc.getBytes(), StandardCharsets.UTF_8);
        CheckSession session = pipeline.run(sessionId, lcText, invoice.getBytes(), invoice.getOriginalFilename());
        return ResponseEntity.ok(session.finalReport());
    }

    @GetMapping("/{sessionId}/trace")
    public ResponseEntity<CheckSession> trace(@PathVariable String sessionId) {
        CheckSession session = store.find(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        return ResponseEntity.ok(session);
    }
}
