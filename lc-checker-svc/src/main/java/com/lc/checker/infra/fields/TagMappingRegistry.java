package com.lc.checker.infra.fields;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Loads {@code lc-tag-mapping.yaml}: MT700 tag → canonical field key(s) +
 * parser type. The Mt700 field-mapper looks up each parsed Prowide tag here
 * to decide which canonical key(s) to write to and which sub-field parser to
 * apply.
 *
 * <p>Fail-fast at boot: missing file, duplicate tag, or {@code field_keys}
 * referencing a non-registered canonical key all throw
 * {@link IllegalStateException}.
 */
@Component
public class TagMappingRegistry {

    private static final Logger log = LoggerFactory.getLogger(TagMappingRegistry.class);

    private final Map<String, TagMapping> officialByTag;
    private final List<String> mandatoryTags;

    public TagMappingRegistry(
            ResourceLoader resourceLoader,
            FieldPoolRegistry fieldPool,
            @Value("${fields.tag-mapping-path:classpath:/fields/lc-tag-mapping.yaml}") String mappingPath) throws IOException {
        Resource resource = resourceLoader.getResource(mappingPath);
        if (!resource.exists()) {
            throw new IllegalStateException("Tag mapping not found at " + mappingPath);
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        TagMappingFile parsed;
        try (InputStream in = resource.getInputStream()) {
            parsed = mapper.readValue(in, TagMappingFile.class);
        }
        if (parsed == null || parsed.mt700() == null || parsed.mt700().officialTags() == null
                || parsed.mt700().officialTags().isEmpty()) {
            throw new IllegalStateException("Tag mapping at " + mappingPath + " is empty");
        }

        Map<String, TagMapping> tagMap = new LinkedHashMap<>();
        var mandatory = new java.util.ArrayList<String>();

        for (Map.Entry<String, TagMapping> e : parsed.mt700().officialTags().entrySet()) {
            String tag = e.getKey();
            TagMapping bare = e.getValue();
            if (bare == null) {
                throw new IllegalStateException("Tag mapping for :" + tag + ": is empty");
            }
            // The YAML key is the tag — re-stamp it onto the record so callers don't have to track both.
            TagMapping mapping = new TagMapping(tag, bare.fieldKeys(), bare.parser(),
                    bare.mandatory(), bare.defaults());
            for (String key : mapping.fieldKeys()) {
                if (fieldPool.byKey(key).isEmpty()) {
                    throw new IllegalStateException("Tag :" + tag + ": references unknown field_key: " + key);
                }
            }
            if (mapping.parser() == null) {
                throw new IllegalStateException("Tag :" + tag + ": has no parser type");
            }
            if (tagMap.put(tag, mapping) != null) {
                throw new IllegalStateException("Duplicate tag in mapping: " + tag);
            }
            if (mapping.mandatory()) {
                mandatory.add(tag);
            }
        }

        this.officialByTag = Collections.unmodifiableMap(tagMap);
        this.mandatoryTags = List.copyOf(mandatory);

        log.info("TagMappingRegistry loaded {} official tags ({} mandatory)",
                officialByTag.size(), mandatoryTags.size());
    }

    public Optional<TagMapping> byTag(String tag) {
        return Optional.ofNullable(officialByTag.get(tag));
    }

    public List<TagMapping> all() {
        return List.copyOf(officialByTag.values());
    }

    public List<String> mandatoryTags() {
        return mandatoryTags;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TagMappingFile(Mt700Section mt700) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Mt700Section(Map<String, TagMapping> officialTags) {
    }
}
