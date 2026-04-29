package com.lc.checker.pipeline.stages;

import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.result.StageTrace;
import com.lc.checker.domain.session.enums.StageStatus;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.observability.PipelineMetrics;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.pipeline.Stage;
import com.lc.checker.pipeline.StageContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.infra.observability.LangfuseTags;
import com.lc.checker.stage.parse.Mt700Parser;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Stage 1a — SWIFT MT700 parse (regex / Prowide only, no LLM).
 *
 * <p>Special-cases error handling: pre-parse failure has no session row to mark as
 * failed, so this stage rethrows without calling the shared error handler. After a
 * successful parse the session row is created here (we now have the LC reference,
 * beneficiary, applicant scalars to populate it).
 */
@Component
public class LcParseStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(LcParseStage.class);

    private final Mt700Parser parser;
    private final CheckSessionStore store;
    private final PipelineMetrics metrics;
    private final Tracer tracer;
    private final ObjectMapper json;

    public LcParseStage(Mt700Parser parser, CheckSessionStore store,
                        PipelineMetrics metrics, Tracer tracer, ObjectMapper json) {
        this.parser = parser;
        this.store = store;
        this.metrics = metrics;
        this.tracer = tracer;
        this.json = json;
    }

    @Override
    public String name() {
        return "lc_parse";
    }

    @Override
    public void execute(StageContext ctx) {
        // ── Setup: metrics timer + Langfuse span ──────────────────────────────
        long t0 = System.currentTimeMillis();
        Span span = traceSpanStart(ctx.lcText);
        ctx.publisher.status(name(), "started", "Parsing MT700");
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            MDC.put(MdcKeys.STAGE, name());

            // ═══════════════════════════════════════════════════════════════════
            // CORE: MT700 → LcDocument (regex / Prowide only, no LLM)
            // ═══════════════════════════════════════════════════════════════════
            LcDocument lc = parser.parse(ctx.lcText);
            long dur = System.currentTimeMillis() - t0;

            // ── Post-parse: populate context + emit events ───────────────────
            ctx.lc = lc;
            ctx.lcParseTrace = new StageTrace(name(), StageStatus.SUCCESS,
                    Instant.now(), dur, lc, List.of(), null);
            metrics.recordStage(PipelineMetrics.TIMER_PARSE, dur, "success");
            ctx.publisher.status(name(), "completed",
                    "MT700 parsed (LC " + lc.lcNumber() + ")", lc);
            traceSpanEnd(span, lc, dur);
        } catch (RuntimeException e) {
            metrics.recordStage(PipelineMetrics.TIMER_PARSE, System.currentTimeMillis() - t0, "failed");
            log.error("Stage 1a (lc_parse) failed before session persistence: {}", e.getMessage());
            ctx.publisher.error(name(), String.valueOf(e.getMessage()));
            span.error(e);
            throw e;
        } finally {
            MDC.remove(MdcKeys.STAGE);
            span.end();
        }

        // ── DB persistence: session row + pipeline_steps ───────────────────
        store.createSession(ctx.sessionId, ctx.lc.lcNumber(),
                ctx.lc.beneficiaryName(), ctx.lc.applicantName());
        store.putStep(ctx.sessionId, name(), "-", "SUCCESS",
                Instant.ofEpochMilli(t0), Instant.now(),
                Map.of("lc_output", ctx.lc), null);
        log.debug("Stage 1a complete: lc_parse recorded");
    }

    private Span traceSpanStart(String lcText) {
        return LangfuseTags.applySession(tracer.nextSpan())
                .name("lc-parse")
                .tag("langfuse.observation.type", "span")
                .tag("stage", name())
                .tag("langfuse.observation.input", toJson(Map.of(
                        "lc_text_length", lcText.length(),
                        "lc_text_preview", preview(lcText, 280))))
                .start();
    }

    private void traceSpanEnd(Span span, LcDocument lc, long dur) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("lc_number", lc.lcNumber());
        output.put("currency", lc.currency());
        output.put("amount", lc.amount());
        output.put("expiry_date", lc.expiryDate());
        output.put("applicant_name", lc.applicantName());
        output.put("beneficiary_name", lc.beneficiaryName());
        output.put("incoterms", lc.envelope() == null ? null : lc.envelope().fields().get("incoterms"));
        output.put("port_of_loading", lc.portOfLoading());
        output.put("port_of_discharge", lc.portOfDischarge());
        output.put("duration_ms", dur);
        span.tag("langfuse.observation.output", toJson(output));
        span.tag("lc.number", String.valueOf(lc.lcNumber()));
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (JsonProcessingException e) { return String.valueOf(o); }
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
