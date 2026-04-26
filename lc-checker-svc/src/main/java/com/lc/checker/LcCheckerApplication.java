package com.lc.checker;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
@OpenAPIDefinition(
        info = @Info(
                title = "LC Invoice Checker API",
                version = "0.0.1-SNAPSHOT",
                description = """
                        Trade-finance backend that checks a commercial invoice (PDF) against
                        a SWIFT MT700 Letter of Credit (plain text) using UCP 600 / ISBP 821.

                        Pipeline: LC parse → multi-source invoice extract
                        (vision + docling + mineru) → rule activation → tiered rule check
                        (Tier 1 Type A, Tier 2 Type B, Tier 3 Type AB) → report assembly.

                        Every run is persisted to the unified `pipeline_steps` table —
                        query `v_latest_*` views for step-by-step forensics.
                        """,
                contact = @Contact(name = "lc-checker-svc")),
        servers = {
                @Server(url = "http://localhost:8080", description = "Local dev")
        })
public class LcCheckerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LcCheckerApplication.class, args);
    }
}
