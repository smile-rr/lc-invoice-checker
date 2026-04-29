package com.lc.checker;

import com.lc.checker.infra.queue.QueueProperties;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
/**
 * Entry point for the LC Invoice Checker API.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Stage 1a — Mt700Parser (regex; no LLM)</li>
 *   <li>Stage 1b — InvoiceExtractionOrchestrator (vision / docling / mineru)</li>
 *   <li>Stage 2  — ProgrammaticChecksStage (deterministic SpEL rules)</li>
 *   <li>Stage 3  — AgentChecksStage (one LLM call per AGENT rule)</li>
 *   <li>Stage 4  — ReportAssembler (DiscrepancyReport JSON)</li>
 * </ol>
 *
 * <p>Session storage: L1 Caffeine + L2 PostgreSQL (unified {@code pipeline_steps}
 * table, progressive recording via JDBC Template).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(QueueProperties.class)
public class LcCheckerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LcCheckerApplication.class, args);
    }
}
