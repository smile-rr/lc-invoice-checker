package com.lc.checker.api.controller;

import com.lc.checker.stage.extract.InvoiceExtractionOrchestrator;
import com.lc.checker.stage.parse.Mt700Parser;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Temporary debug endpoints for verifying each pipeline stage independently.
 * All responses are plain text — no JSON DTOs.
 *
 * <ul>
 *   <li>{@code POST /api/v1/debug/mt700/parse} — raw LC fields from the deterministic Stage 1a parser</li>
 *   <li>{@code POST /api/v1/debug/invoice/compare} — all enabled extractors side-by-side</li>
 * </ul>
 *
 * <p>These are dev-only endpoints. They are not part of the V1 production API contract.
 */
@RestController
@RequestMapping("/api/v1/debug")
public class DebugController {

    private final Mt700Parser mt700Parser;
    private final InvoiceExtractionOrchestrator extractionOrchestrator;

    public DebugController(Mt700Parser mt700Parser,
                           InvoiceExtractionOrchestrator extractionOrchestrator) {
        this.mt700Parser = mt700Parser;
        this.extractionOrchestrator = extractionOrchestrator;
    }

    /**
     * Parse MT700 text with the deterministic regex parser only. Stage 1a output:
     * scalar fields plus raw verbatim :45A:, :46A:, :47A: strings. No LLM involved.
     */
    @PostMapping(value = "/mt700/parse", consumes = MediaType.TEXT_PLAIN_VALUE)
    public String parseMt700(@RequestBody String mt700Text) {
        return mt700Parser.parseAsText(mt700Text);
    }

    /**
     * Run every enabled extractor (vision, docling, mineru) against the same PDF
     * and dump results side-by-side as plain text. Does NOT persist to DB.
     */
    @PostMapping(value = "/invoice/compare", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String compareExtractors(@RequestPart("invoice") MultipartFile pdf) throws IOException {
        return extractionOrchestrator.compareAllAsText(pdf.getBytes(), pdf.getOriginalFilename());
    }
}
