package com.lc.checker.api;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Drives the public API reference shown in Scalar at /docs.html.
 *
 * <p>Only the two demo endpoints are included — all other paths are omitted
 * intentionally so the reference stays focused for demos and newcomers.
 */
@Configuration
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer")
public class OpenApiConfig {

    // -------------------------------------------------------------------------
    // Schema builders
    // -------------------------------------------------------------------------

    private static Schema<?> checkEventSchema() {
        var typeEnum = new Schema<>()
                .type("string")
                .description("Event type: status | rule | error | complete")
                ._enum(List.of("status", "rule", "error", "complete"));

        var schema = new Schema<>();
        schema.setType("object");
        schema.setDescription("Unified SSE envelope shared by the /stream endpoint and /trace replay API.");
        schema.setProperties(Map.of(
                "seq",      new Schema<>().type("integer").description("Monotonically increasing sequence number (0-based)").example(0),
                "ts",       new Schema<>().type("string").format("date-time").description("Wall-clock timestamp of event emission (ISO-8601 UTC)").example("2026-04-28T10:15:30.123Z"),
                "type",     typeEnum,
                "stage",    new Schema<>().type("string").description("Pipeline stage name, present for status and error events").example("lc_parse"),
                "state",    new Schema<>().type("string").description("'started' or 'completed', present for status events").example("completed"),
                "message",  new Schema<>().type("string").description("Human-readable message for status and error events").example("LC parsed successfully"),
                "data",     dataSchema()
        ));
        return schema;
    }

    private static Schema<?> dataSchema() {
        var schema = new Schema<>();
        schema.setDescription("""
                Payload shape is determined by event type:
                - status+completed: LcDocument | InvoiceDocument | checks-summary Map
                - rule:             CheckResult
                - error:            null
                - complete:         DiscrepancyReport""");
        schema.setExample(Map.of("passed", 11, "failed", 2, "warnings", 1, "skipped", 0));
        return schema;
    }

    private static Schema<?> startResponseSchema() {
        var schema = new Schema<>();
        schema.setType("object");
        schema.setDescription("Response returned immediately on submission.");
        schema.setProperties(Map.of(
                "session_id",     new Schema<>().type("string").description("UUID assigned to this run").example("550e8400-e29b-41d4-a716-446655440000"),
                "status",         new Schema<>().type("string").description("Always 'QUEUED' on success").example("QUEUED"),
                "queue_position", new Schema<>().type("integer").description("1-based position in the run queue").example(1),
                "invoice_filename", new Schema<>().type("string").description("Original invoice filename").example("invoice.pdf"),
                "invoice_bytes",  new Schema<>().type("integer").description("Invoice file size in bytes").example(45000),
                "lc_length",     new Schema<>().type("integer").description("LC text length in characters").example(2048)
        ));
        return schema;
    }

    // -------------------------------------------------------------------------
    // Operation builders
    // -------------------------------------------------------------------------

    private static Operation startOperation() {
        var op = new Operation();
        op.setSummary("submit LC text + invoice PDF, get sessionId immediately");
        op.setDescription("""
                Submit a multipart POST with two files:
                - `lc`   — MT700 plain text (text/plain)
                - `invoice` — commercial invoice PDF (application/pdf)

                Validation runs immediately. On success the session is queued and
                a `session_id` is returned instantly — the actual check runs asynchronously.
                Poll `GET /{sessionId}/stream` for live progress.
                """);
        op.addTagsItem("Demo");

        var okResponse = new ApiResponse();
        okResponse.setDescription("Session queued. Returns sessionId immediately.");
        var okContent = new Content();
        var okMedia = new MediaType();
        okMedia.setSchema(startResponseSchema());
        okContent.addMediaType("application/json", okMedia);
        okResponse.setContent(okContent);
        op.getResponses().addApiResponse("200", okResponse);

        return op;
    }

    private static Operation streamOperation() {
        var op = new Operation();
        op.setSummary("live SSE stream of status and result");
        op.setDescription("""
                Opens a Server-Sent Events (SSE) stream for the given session. Four event
                types are emitted sequentially:

                1. **status** — stage transition (e.g. `lc_parse: started` → `lc_parse: completed`).
                   On `completed`, structured data is included (LcDocument, InvoiceDocument, or a
                   checks summary map).
                2. **rule** — one event per completed rule check. `data` is a `CheckResult`.
                3. **error** — pipeline halted. `data` is null; no further events follow.
                4. **complete** — final report assembled. `data` is a `DiscrepancyReport`.
                   UI should navigate to the review tab on receipt.

                All events share the same envelope shape (`CheckEvent`).
                """);
        op.addTagsItem("Demo");

        var okResponse = new ApiResponse();
        okResponse.setDescription("""
                SSE stream (text/event-stream). One `CheckEvent` JSON per line.
                The SSE `event:` field carries the type (status|rule|error|complete).
                `data` shape depends on type — see description above.
                """);
        var okContent = new Content();
        var okMedia = new MediaType();
        okMedia.setSchema(checkEventSchema());
        okContent.addMediaType("text/event-stream", okMedia);
        okResponse.setContent(okContent);
        op.getResponses().addApiResponse("200", okResponse);

        return op;
    }

    // -------------------------------------------------------------------------
    // Bean
    // -------------------------------------------------------------------------

    /**
     * Explicit spec covering only the two demo endpoints.
     * Springdoc will use this bean as the authoritative spec and will NOT
     * auto-scan other controllers — no path exclusions needed.
     */
    @Bean
    public OpenAPI lcCheckerOpenApi() {
        var openApi = new OpenAPI();
        openApi.setServers(List.of(
                new Server().url("http://localhost:8080").description("Local dev")
        ));

        var info = new io.swagger.v3.oas.models.info.Info();
        info.setTitle("LC Invoice Checker API");
        info.setVersion("0.0.1-SNAPSHOT");
        info.setDescription("""
                Trade-finance backend that checks a commercial invoice (PDF) against
                a SWIFT MT700 Letter of Credit (plain text) using UCP 600 / ISBP 821.

                Pipeline: LC parse → multi-source invoice extract
                (vision + docling + mineru) → rule activation → tiered rule check
                (Tier 1 Type A, Tier 2 Type B, Tier 3 Type AB) → report assembly.
                """);
        openApi.setInfo(info);

        var paths = new io.swagger.v3.oas.models.PathItem();
        paths.setPost(startOperation());
        openApi.path("/api/v1/lc-check/start", paths);

        var streamPaths = new io.swagger.v3.oas.models.PathItem();
        streamPaths.setGet(streamOperation());
        openApi.path("/api/v1/lc-check/{sessionId}/stream", streamPaths);

        return openApi;
    }

    /** GroupedOpenApi that excludes all auto-scanned paths so only the demo group shows. */
    @Bean
    public GroupedOpenApi demoApi() {
        return GroupedOpenApi.builder()
                .group("demo")
                .pathsToMatch("/api/v1/lc-check/start", "/api/v1/lc-check/{sessionId}/stream")
                .build();
    }
}
