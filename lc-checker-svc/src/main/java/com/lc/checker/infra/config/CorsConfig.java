package com.lc.checker.infra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allow the Vite dev server (and its preview mode) to call the API from a
 * different origin. Prod deployments serve the UI from the same origin as the
 * API (Spring static resources under {@code src/main/resources/static}) and do
 * not need CORS.
 *
 * <p>{@code allowCredentials=false} is deliberate — we do not ship session
 * cookies, so the browser can safely use wildcard-style origin matching.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://localhost:4173",
                        "http://127.0.0.1:4173")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type", "X-Session-Id")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
