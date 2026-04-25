package com.lc.checker.infra.fields;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.lc.checker.domain.common.FieldType;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Loads {@code field-pool.yaml} once at startup and exposes lookups by canonical
 * key and by invoice alias. This is the single source of truth for "what fields
 * exist in our domain" — LC parser, invoice extractor, rule catalog, and the
 * {@code GET /api/v1/fields} endpoint all read from here.
 *
 * <p>Fail-fast: a missing file, duplicate key, or duplicate alias throws
 * {@link IllegalStateException} at boot.
 */
@Component
public class FieldPoolRegistry {

    private static final Logger log = LoggerFactory.getLogger(FieldPoolRegistry.class);

    private final List<FieldDefinition> fields;
    private final Map<String, FieldDefinition> byKey;
    private final Map<String, String> aliasToKey;   // invoice_alias → canonical key

    public FieldPoolRegistry(
            ResourceLoader resourceLoader,
            @Value("${fields.pool-path:classpath:/fields/field-pool.yaml}") String poolPath) throws IOException {
        Resource resource = resourceLoader.getResource(poolPath);
        if (!resource.exists()) {
            throw new IllegalStateException("Field pool not found at " + poolPath);
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        FieldPoolFile parsed;
        try (InputStream in = resource.getInputStream()) {
            parsed = mapper.readValue(in, FieldPoolFile.class);
        }
        if (parsed == null || parsed.fields() == null || parsed.fields().isEmpty()) {
            throw new IllegalStateException("Field pool at " + poolPath + " is empty");
        }

        Map<String, FieldDefinition> keyMap = new LinkedHashMap<>();
        Map<String, String> aliasMap = new LinkedHashMap<>();
        Set<String> aliasSeen = new LinkedHashSet<>();
        for (FieldDefinition f : parsed.fields()) {
            if (f.key() == null || f.key().isBlank()) {
                throw new IllegalStateException("field-pool entry has no key: " + f);
            }
            if (keyMap.put(f.key(), f) != null) {
                throw new IllegalStateException("Duplicate field-pool key: " + f.key());
            }
            validateColumns(f);
            // Each field is its own alias too, so {credit_amount → credit_amount}.
            aliasMap.put(f.key(), f.key());
            for (String alias : f.invoiceAliases()) {
                if (alias == null || alias.isBlank()) continue;
                String norm = alias.toLowerCase();
                if (aliasMap.containsKey(norm) && !aliasMap.get(norm).equals(f.key())) {
                    throw new IllegalStateException(
                            "Duplicate invoice_alias '" + alias + "' on " + f.key()
                            + " — already mapped to " + aliasMap.get(norm));
                }
                aliasMap.put(norm, f.key());
                aliasSeen.add(norm);
            }
        }

        this.fields = List.copyOf(parsed.fields());
        this.byKey = Collections.unmodifiableMap(keyMap);
        this.aliasToKey = Collections.unmodifiableMap(aliasMap);

        long lc = parsed.fields().stream().filter(FieldDefinition::appliesToLc).count();
        long inv = parsed.fields().stream().filter(FieldDefinition::appliesToInvoice).count();
        log.info("FieldPoolRegistry loaded {} keys ({} apply to LC, {} apply to invoice, {} aliases)",
                fields.size(), lc, inv, aliasSeen.size());
    }

    /** All registered field definitions, in declaration order. */
    public List<FieldDefinition> all() {
        return fields;
    }

    public Optional<FieldDefinition> byKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /**
     * Resolve a raw extractor key (verbatim, possibly case-varied) to its canonical
     * key. Returns the canonical key if a registered alias matches, or empty if
     * the input is unknown (caller should drop into the {@code extras} map).
     */
    public Optional<String> resolveInvoiceAlias(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return Optional.empty();
        return Optional.ofNullable(aliasToKey.get(rawKey.toLowerCase()));
    }

    public List<FieldDefinition> appliesToLc() {
        return fields.stream().filter(FieldDefinition::appliesToLc).toList();
    }

    public List<FieldDefinition> appliesToInvoice() {
        return fields.stream().filter(FieldDefinition::appliesToInvoice).toList();
    }

    private static void validateColumns(FieldDefinition f) {
        boolean isTable = f.type() == FieldType.TABLE;
        boolean hasColumns = f.columns() != null && !f.columns().isEmpty();
        if (isTable && !hasColumns) {
            throw new IllegalStateException(
                    "TABLE field '" + f.key() + "' must declare a columns: block");
        }
        if (!isTable && hasColumns) {
            throw new IllegalStateException(
                    "Field '" + f.key() + "' (type=" + f.type()
                    + ") declared columns: but only TABLE fields may have columns");
        }
        if (!isTable) return;

        Set<String> colKeys = new LinkedHashSet<>();
        for (ColumnDefinition c : f.columns()) {
            if (c.key() == null || c.key().isBlank()) {
                throw new IllegalStateException(
                        "TABLE field '" + f.key() + "' has a column without a key");
            }
            if (!colKeys.add(c.key())) {
                throw new IllegalStateException(
                        "TABLE field '" + f.key() + "' has duplicate column key: " + c.key());
            }
            if (c.type() == FieldType.TABLE) {
                throw new IllegalStateException(
                        "Column '" + c.key() + "' on '" + f.key()
                        + "' is type=TABLE — nested tables are not supported");
            }
        }
    }

    /** Top-level YAML envelope: {@code fields: [...]}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldPoolFile(List<FieldDefinition> fields) {
    }
}
