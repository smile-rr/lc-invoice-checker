package com.lc.checker;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.stage.activate.RuleActivator;
import com.lc.checker.stage.assemble.ReportAssembler;
import com.lc.checker.stage.check.CheckExecutor;
import com.lc.checker.stage.extract.InvoiceExtractionOrchestrator;
import com.lc.checker.stage.parse.Mt700Parser;

/**
 * Entry point for the LC Invoice Checker API.
 *
 * <p>Pipeline (see docs/refer-doc/logic-flow.md):
 * <ol>
 *   <li>Stage 1a — Mt700Parser (pure regex, no LLM)</li>
 *   <li>Stage 1b — InvoiceExtractionOrchestrator (multi-source: vision / docling / mineru)</li>
 *   <li>Stage 2  — RuleActivator (catalog-driven activation)</li>
 *   <li>Stage 3  — CheckExecutor Tier 1 (Type A) → Tier 2 (Type B) → Tier 3 (Type AB)</li>
 *   <li>Stage 4  — Holistic sweep (designed, executor deferred)</li>
 *   <li>Stage 5  — ReportAssembler (DiscrepancyReport JSON)</li>
 * </ol>
 *
 * <p>Session storage: L1 Caffeine (in-process cache) + L2 PostgreSQL
 * (unified {@code pipeline_steps} table, progressive recording via JDBC Template).
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
