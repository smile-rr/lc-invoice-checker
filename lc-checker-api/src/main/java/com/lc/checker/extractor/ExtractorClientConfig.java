package com.lc.checker.extractor;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@link RestClient} instances for both extractor sidecars.
 *
 * <p>Docling is the primary (V1). Mineru is the fallback (V1.5, disabled by default).
 * Only the URL differs — the HTTP contract is identical per
 * {@code extractors/CONTRACT.md v1.0}.
 *
 * <p>Tests can replace either bean with a MockRestServiceServer-bound instance via
 * a {@code @Primary} override in a test configuration class.
 */
@Configuration
public class ExtractorClientConfig {

    public static final String DOCLING_REST_CLIENT = "doclingRestClient";
    public static final String MINERU_REST_CLIENT  = "mineruRestClient";

    @Bean(DOCLING_REST_CLIENT)
    public RestClient doclingRestClient(
            RestClient.Builder builder,
            @Value("${extractor.docling-url:http://localhost:8081}") String baseUrl,
            @Value("${extractor.timeout-seconds:30}") int timeoutSeconds) {
        return builder.clone().baseUrl(baseUrl).requestFactory(defaultFactory(timeoutSeconds)).build();
    }

    @Bean(MINERU_REST_CLIENT)
    public RestClient mineruRestClient(
            RestClient.Builder builder,
            @Value("${extractor.mineru-url:http://localhost:8082}") String baseUrl,
            @Value("${extractor.timeout-seconds:30}") int timeoutSeconds) {
        return builder.clone().baseUrl(baseUrl).requestFactory(defaultFactory(timeoutSeconds)).build();
    }

    private static ClientHttpRequestFactory defaultFactory(int timeoutSeconds) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        return f;
    }
}
