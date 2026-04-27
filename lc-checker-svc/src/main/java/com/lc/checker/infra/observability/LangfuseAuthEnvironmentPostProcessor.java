package com.lc.checker.infra.observability;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Auto-derives {@code LANGFUSE_AUTH_BASIC} from {@code LANGFUSE_PUBLIC_KEY} +
 * {@code LANGFUSE_SECRET_KEY} when not explicitly set.
 *
 * <p>Why: {@code application.yml} ships a literal {@code Authorization: Basic
 * ${LANGFUSE_AUTH_BASIC:}} header to the OTLP exporter. Hand-precomputed in
 * {@code .env}, that value goes stale every time someone rotates Langfuse keys
 * — Java keeps sending the old base64, Langfuse returns 401, and
 * BatchSpanProcessor swallows the failure silently. Computing it from pk/sk at
 * startup makes Java rotation as easy as Python's: update two env vars, restart.
 *
 * <p>An explicit {@code LANGFUSE_AUTH_BASIC} still wins (back-compat for
 * environments that already set it via secrets manager).
 */
public class LangfuseAuthEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String AUTH_KEY = "LANGFUSE_AUTH_BASIC";
    private static final String PUBLIC_KEY = "LANGFUSE_PUBLIC_KEY";
    private static final String SECRET_KEY = "LANGFUSE_SECRET_KEY";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String existing = env.getProperty(AUTH_KEY);
        if (existing != null && !existing.isBlank()) {
            return;
        }
        String pk = env.getProperty(PUBLIC_KEY);
        String sk = env.getProperty(SECRET_KEY);
        if (pk == null || pk.isBlank() || sk == null || sk.isBlank()) {
            return;
        }
        String basic = Base64.getEncoder()
                .encodeToString((pk + ":" + sk).getBytes(StandardCharsets.UTF_8));
        env.getPropertySources().addFirst(
                new MapPropertySource("langfuseAuthDerived", Map.of(AUTH_KEY, basic)));
    }
}
