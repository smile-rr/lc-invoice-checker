package com.lc.checker.stage.extract.docling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.stage.extract.InvoiceFieldMapper;
import com.lc.checker.stage.extract.PromptBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Registers the {@link DoclingExtractorClient} bean when
 * {@code extractor.docling-enabled=true}. Disabled by default — enable via
 * {@code EXTRACTOR_DOCLING_ENABLED=true} and start the full docker-compose profile.
 */
@Configuration
@ConditionalOnProperty(name = "extractor.docling-enabled", havingValue = "true")
public class DoclingExtractorBeans {

    @Bean("doclingExtractorClient")
    DoclingExtractorClient doclingExtractorClient(
            RestClient.Builder restClientBuilder,
            InvoiceFieldMapper mapper,
            ObjectMapper objectMapper,
            PromptBuilder promptBuilder,
            @Value("${docling.base-url:http://docling-svc:8081}") String baseUrl,
            @Value("${docling.timeout-seconds:180}") int timeoutSeconds) {
        return new DoclingExtractorClient(restClientBuilder, mapper, objectMapper,
                new DoclingExtractorConfig("docling", baseUrl, timeoutSeconds),
                promptBuilder);
    }
}
