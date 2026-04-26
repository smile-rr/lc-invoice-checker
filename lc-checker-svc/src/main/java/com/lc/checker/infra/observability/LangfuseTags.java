package com.lc.checker.infra.observability;

import io.micrometer.tracing.Span;
import org.slf4j.MDC;

/**
 * Helpers to apply Langfuse-recognised attributes to spans.
 *
 * <p>The whole point of {@link #applySession(Span)} is to force every span
 * the pipeline creates onto the same Langfuse trace. We do this by setting
 * two attributes that Langfuse's OTel ingestion treats as authoritative:
 *
 * <ul>
 *   <li>{@code langfuse.trace.id} — overrides the OTel trace_id Langfuse would
 *       otherwise compute. Set to the {@code session_id}, so every span carrying
 *       the same session_id ends up in the same Langfuse trace, even if the
 *       OTel parent-child wiring is broken (async dispatch, crossed thread
 *       pools, etc.). Belt to the OTel context's braces.</li>
 *   <li>{@code langfuse.session.id} — groups multiple traces of the same
 *       session in Langfuse's Session view.</li>
 * </ul>
 *
 * <p>Reads {@code session_id} from MDC. The pipeline puts it there at the
 * controller boundary (see {@code LcCheckStreamController}, {@code MdcFilter}).
 * If MDC is empty (test code, ad-hoc span outside the pipeline), the helper
 * is a no-op.
 */
public final class LangfuseTags {

    private LangfuseTags() {
    }

    /**
     * Tag the span with langfuse.trace.id + langfuse.session.id from MDC.
     * Call this immediately after {@code tracer.nextSpan()} (before any other
     * tag) so the attributes are present from span start.
     */
    public static Span applySession(Span span) {
        if (span == null) return span;
        String sid = MDC.get(MdcKeys.SESSION_ID);
        if (sid != null && !sid.isBlank()) {
            span.tag("langfuse.trace.id", sid);
            span.tag("langfuse.session.id", sid);
            // Stamp trace name on every span. Langfuse uses whichever name
            // it sees first (or last, depending on version) as the trace
            // title — setting it everywhere means the title becomes
            // "LC Check <sessionId>" as soon as the FIRST span exports
            // (typically lc-parse, ~1–2s in), not 60s later when the root
            // session span ends.
            span.tag("langfuse.trace.name", "LC Check " + sid);
        }
        return span;
    }
}
