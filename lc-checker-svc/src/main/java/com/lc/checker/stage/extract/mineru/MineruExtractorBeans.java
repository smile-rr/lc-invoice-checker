package com.lc.checker.stage.extract.mineru;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.stage.extract.InvoiceFieldMapper;
import com.lc.checker.stage.extract.PromptBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Registers the {@link MineruExtractorClient} bean when
 * {@code extractor.mineru-enabled=true}. Disabled by default — enable via
 * {@code EXTRACTOR_MINERU_ENABLED=true} and start the full docker-compose profile.
 */
@Configuration
@ConditionalOnProperty(name = "extractor.mineru-enabled", havingValue = "true")
public class MineruExtractorBeans {

    @Bean("mineruExtractorClient")
    MineruExtractorClient mineruExtractorClient(
            RestClient.Builder restClientBuilder,
            InvoiceFieldMapper mapper,
            ObjectMapper objectMapper,
            PromptBuilder promptBuilder,
            @Value("${mineru.base-url:http://mineru-svc:8082}") String baseUrl,
            @Value("${mineru.timeout-seconds:180}") int timeoutSeconds) {
        return new MineruExtractorClient(restClientBuilder, mapper, objectMapper,
                new MineruExtractorConfig("mineru", baseUrl, timeoutSeconds),
                promptBuilder);
    }
}
