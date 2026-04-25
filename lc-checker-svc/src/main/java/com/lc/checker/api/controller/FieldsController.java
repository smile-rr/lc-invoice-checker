package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.infra.fields.FieldDefinition;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * UI/integration extensibility hook.
 *
 * <p>Returns the canonical-field registry verbatim so any consumer can render
 * labels, types, and applicability without hardcoding field names anywhere on
 * the client side. Adding a row to {@code field-pool.yaml} is sufficient — the
 * UI picks it up automatically on next page-load.
 *
 * <p>Optional filter: {@code GET /api/v1/fields?applies_to=LC} returns only the
 * LC-side keys; {@code ?applies_to=INVOICE} returns the invoice-side keys.
 */
@RestController
@RequestMapping("/api/v1/fields")
public class FieldsController {

    private final FieldPoolRegistry registry;

    public FieldsController(FieldPoolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ResponseEntity<FieldsResponse> list(
            @RequestParam(value = "applies_to", required = false) String appliesTo) {
        List<FieldDefinition> picked;
        if (appliesTo == null) {
            picked = registry.all();
        } else {
            String want = appliesTo.toUpperCase();
            picked = registry.all().stream()
                    .filter(f -> f.appliesTo().contains(want))
                    .toList();
        }
        return ResponseEntity.ok(new FieldsResponse(picked, picked.size()));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FieldsResponse(List<FieldDefinition> fields, int total) {
    }
}
