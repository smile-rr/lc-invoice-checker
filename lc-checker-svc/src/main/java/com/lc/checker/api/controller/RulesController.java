package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.rule.RuleCatalog;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only catalog browser. Surfaces the loaded {@link RuleCatalog} so the UI can
 * render rule metadata that doesn't travel on the per-rule SSE wire — phase, check
 * type, output_field, rule_reference_label, and the inline UCP/ISBP paraphrases.
 *
 * <p>The catalog rarely changes; clients fetch this once at app startup and cache.
 * Every {@link Rule} record field is included verbatim — Jackson handles the
 * snake_case wire format via {@link com.fasterxml.jackson.databind.PropertyNamingStrategies}.
 */
@RestController
@RequestMapping("/api/v1/rules")
public class RulesController {

    private final RuleCatalog catalog;

    public RulesController(RuleCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<CatalogResponse> all() {
        return ResponseEntity.ok(new CatalogResponse(catalog.all()));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CatalogResponse(List<Rule> rules) {
    }
}
