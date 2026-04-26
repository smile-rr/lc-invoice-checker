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
 * Suppresses HTTP-server tracing for endpoints that are routing-level reads
 * with no interesting work behind them. Without this, every SSE subscription,
 * every trace lookup, every file-serving GET shows up in Langfuse as its own
 * empty trace, drowning the actual {@code POST /start} pipeline traces.
 *
 * <p>Endpoints filtered (none of these run business logic worth observing
 * at the request boundary):
 * <ul>
 *   <li>{@code GET /api/v1/lc-check/{sid}/stream} — SSE subscription</li>
 *   <li>{@code GET /api/v1/lc-check/{sid}/trace} — full trace JSON read</li>
 *   <li>{@code GET /api/v1/lc-check/{sid}/lc-raw} — raw LC text serve</li>
 *   <li>{@code GET /api/v1/lc-check/{sid}/extracts} — extracts JSON serve</li>
 *   <li>{@code GET /api/v1/lc-check/{sid}/invoice} — invoice PDF serve</li>
 *   <li>{@code GET /api/v1/files/{key}} — uploaded file serve</li>
 *   <li>{@code GET /actuator/**} — health/prometheus probes</li>
 *   <li>{@code GET /v3/api-docs}, {@code /static/**} — OpenAPI/UI docs</li>
 * </ul>
 *
 * <p>The actual {@code POST /api/v1/lc-check/start} (which kicks off the
 * pipeline) is NOT filtered — its observation becomes the parent of the
 * session span via context capture/restore in the executor.
 */
@Configuration
public class TracingFilterConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingFilterConfig.class);

    @Bean
    public ObservationRegistryCustomizer<ObservationRegistry> excludeNoiseObservations() {
        return registry -> registry.observationConfig().observationPredicate((name, context) -> {
            if (context instanceof ServerRequestObservationContext httpContext) {
                HttpServletRequest req = httpContext.getCarrier();
                if (req == null) return true;
                String uri = req.getRequestURI();
                if (uri == null) return true;
                if (isNoise(uri)) {
                    return false;  // Don't observe → no Tomcat HTTP span
                }
            }
            return true;
        });
    }

    static boolean isNoise(String uri) {
        // Routing-level reads — see class javadoc for the rationale.
        return uri.matches(".*/lc-check/[^/]+/(stream|trace|lc-raw|extracts|invoice|invoice-line-items)$")
                || uri.matches(".*/api/v1/files/[^/]+$")
                || uri.startsWith("/actuator")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/static/")
                || uri.equals("/favicon.ico");
    }
}
