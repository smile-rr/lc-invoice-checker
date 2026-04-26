package com.lc.checker.pipeline;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.result.CheckResult;
import com.lc.checker.domain.result.CheckTrace;
import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.domain.result.StageTrace;
import com.lc.checker.infra.stream.CheckEventPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable carrier passed stage-to-stage. Replaces the long parameter list that the
 * old {@code ComplianceEngine.run()} threaded between inline blocks. Each stage
 * reads its inputs from earlier-populated fields and writes its outputs into the
 * fields the next stage consumes.
 */
public final class StageContext {

    public final String sessionId;
    public final String lcText;
    public final byte[] pdfBytes;
    public final String pdfName;
    public final CheckEventPublisher publisher;
    public final Instant pipelineStarted;

    public LcDocument lc;
    public StageTrace lcParseTrace;

    public InvoiceDocument invoice;
    public StageTrace invoiceExtractTrace;

    public final List<CheckResult> checkResults = new ArrayList<>();
    public final List<CheckTrace> checkTraces = new ArrayList<>();

    public DiscrepancyReport report;

    public StageContext(String sessionId, String lcText, byte[] pdfBytes, String pdfName,
                        CheckEventPublisher publisher, Instant pipelineStarted) {
        this.sessionId = sessionId;
        this.lcText = lcText;
        this.pdfBytes = pdfBytes;
        this.pdfName = pdfName;
        this.publisher = publisher;
        this.pipelineStarted = pipelineStarted;
    }
}
