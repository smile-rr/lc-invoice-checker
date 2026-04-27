package com.lc.checker.infra.observability;

import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Restricts what reaches Langfuse to ONLY the LC check workflow.
 *
 * <p>Langfuse is a workflow-trace store, not a generic request log. The only
 * traces it should see are:
 * <ol>
 *   <li>The {@code POST /api/v1/lc-check/start} HTTP entry that kicks off a
 *       pipeline run (so each Langfuse trace begins at the user-facing call).</li>
 *   <li>The manual pipeline / stage / LLM / extractor spans the code creates
 *       inside the run (these all go through {@code LangfuseTags.applySession}
 *       and self-attach to the session trace).</li>
 *   <li>Spring AI {@code ChatClient} auto-observations that fire as children of
 *       those manual spans (carry {@code gen_ai.*} attributes Langfuse turns
 *       into Generation rows + cost).</li>
 * </ol>
 *
 * <p>Everything else is dropped at the {@link io.micrometer.observation.ObservationPredicate}
 * stage — before a span object is even allocated, so there is zero export
 * cost and zero noise in Langfuse:
 * <ul>
 *   <li><b>{@code tasks.scheduled.execution}</b> — Spring's
 *       {@code @Scheduled} instrumentation. Without this filter, the
 *       {@link com.lc.checker.infra.queue.JobDispatcher#pickup()} tick
 *       produces a {@code job-dispatcher.pickup} trace every poll interval
 *       (default 2s) and floods Langfuse.</li>
 *   <li><b>All HTTP endpoints except {@code POST /lc-check/start}</b> —
 *       admin/UI reads ({@code /queue/status}, {@code /sessions},
 *       {@code /rules}, {@code /lc-meta/tags}, {@code /pipeline},
 *       {@code /fields}, {@code /actuator/**}, {@code /v3/api-docs},
 *       {@code /static/**}), session-scoped serves
 *       ({@code /lc-check/{id}/{stream,trace,lc-raw,extracts,invoice}},
 *       {@code /files/{key}}, {@code DELETE /lc-check/{id}}), the standalone
 *       {@code /files/upload} (file is uploaded again with the LC at
 *       {@code /start}), and {@code /debug/**} probes.
 *       None of these are part of the LC check workflow itself, so they have
 *       no place in the workflow trace store.</li>
 * </ul>
 */
@Configuration
public class TracingFilterConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingFilterConfig.class);

    /** Spring 6.1+ {@code @Scheduled} observation name. */
    private static final String SCHEDULED_TASK_OBSERVATION = "tasks.scheduled.execution";

    /** The single HTTP entry point that kicks off a pipeline run. */
    private static final String PIPELINE_START_PATH = "/api/v1/lc-check/start";

    @Bean
    public ObservationRegistryCustomizer<ObservationRegistry> langfusePipelineOnly() {
        return registry -> registry.observationConfig().observationPredicate((name, context) -> {
            // 1. Hard-drop scheduled task observations — JobDispatcher.pickup
            //    runs continuously and is not workflow work.
            if (SCHEDULED_TASK_OBSERVATION.equals(name)) {
                return false;
            }
            // 2. HTTP server: allowlist only the pipeline-start endpoint.
            if (context instanceof ServerRequestObservationContext httpContext) {
                HttpServletRequest req = httpContext.getCarrier();
                return req != null && isPipelineStart(req);
            }
            // 3. Everything else (manual pipeline spans, Spring AI ChatClient,
            //    outbound RestClient calls inside pipeline scope) — let through.
            return true;
        });
    }

    static boolean isPipelineStart(HttpServletRequest req) {
        String method = req.getMethod();
        String uri = req.getRequestURI();
        if (method == null || uri == null) return false;
        // endsWith covers an optional servlet context-path prefix.
        return "POST".equalsIgnoreCase(method) && uri.endsWith(PIPELINE_START_PATH);
    }
}
