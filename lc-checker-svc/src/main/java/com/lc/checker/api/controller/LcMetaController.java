package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.infra.fields.TagMapping;
import com.lc.checker.infra.fields.TagMappingRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only metadata about MT700 tags so the UI can mirror server-side
 * validation rules. Returns the mandatory tag list plus client-relevant
 * length constraints. Patterns are server-side only — surfacing them to the
 * client would force the UI to import a regex engine and re-implement the
 * server's intent, which we want to avoid.
 */
@RestController
@RequestMapping("/api/v1/lc-meta")
public class LcMetaController {

    private final TagMappingRegistry tagMappings;

    public LcMetaController(TagMappingRegistry tagMappings) {
        this.tagMappings = tagMappings;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TagMeta(String tag, boolean mandatory, Integer maxLength, Integer minLength) {}

    @GetMapping("/tags")
    public ResponseEntity<List<TagMeta>> tags() {
        List<TagMeta> out = new ArrayList<>();
        for (TagMapping m : tagMappings.all()) {
            Integer maxLen = m.validation() == null ? null : m.validation().maxLength();
            Integer minLen = m.validation() == null ? null : m.validation().minLength();
            out.add(new TagMeta(m.tag(), m.mandatory(), maxLen, minLen));
        }
        return ResponseEntity.ok(out);
    }
}
