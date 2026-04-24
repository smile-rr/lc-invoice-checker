package com.lc.checker.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates {@link MdcKeys#SESSION_ID} for every inbound HTTP request so every log line
 * emitted during the request carries it automatically. {@code sessionId} is accepted
 * from the {@code X-Session-Id} header if present; otherwise a fresh UUID is generated
 * and echoed back in the same header on the response so the caller can correlate.
 *
 * <p>Runs early in the filter chain so later code (controllers, strategies) can read
 * / extend MDC without having to populate it themselves.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MdcFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Session-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String sessionId = request.getHeader(HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        MDC.put(MdcKeys.SESSION_ID, sessionId);
        response.setHeader(HEADER, sessionId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
