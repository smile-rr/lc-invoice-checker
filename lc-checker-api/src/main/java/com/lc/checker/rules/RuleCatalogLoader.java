package com.lc.checker.rules;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.lc.checker.model.Rule;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Loads {@code rules/catalog.yml} into a {@link RuleCatalog} at application start.
 *
 * <p>Parsing is Jackson-YAML-based (not SnakeYAML) so the existing snake-case naming
 * strategy on {@link Rule} round-trips without extra annotations. Unknown YAML keys
 * are ignored so forward-compatible additions in V1.5 don't break V1 containers.
 *
 * <p>Fail-fast on duplicate IDs or missing catalog file — this is a startup concern,
 * the server should refuse to come up with a broken catalog.
 */
@Configuration
public class RuleCatalogLoader {

    private static final Logger log = LoggerFactory.getLogger(RuleCatalogLoader.class);

    private final ResourceLoader resourceLoader;
    private final String catalogPath;

    public RuleCatalogLoader(
            ResourceLoader resourceLoader,
            @Value("${rules.catalog-path:classpath:/rules/catalog.yml}") String catalogPath) {
        this.resourceLoader = resourceLoader;
        this.catalogPath = catalogPath;
    }

    @Bean
    public RuleCatalog ruleCatalog() throws IOException {
        Resource resource = resourceLoader.getResource(catalogPath);
        if (!resource.exists()) {
            throw new IllegalStateException("Rule catalog not found at " + catalogPath);
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        CatalogFile parsed;
        try (InputStream in = resource.getInputStream()) {
            parsed = mapper.readValue(in, CatalogFile.class);
        }
        if (parsed == null || parsed.rules() == null || parsed.rules().isEmpty()) {
            throw new IllegalStateException("Rule catalog at " + catalogPath + " is empty");
        }

        // Duplicate-ID guard.
        Set<String> seen = new HashSet<>();
        for (Rule r : parsed.rules()) {
            if (r.id() == null || r.id().isBlank()) {
                throw new IllegalStateException("Rule catalog contains an entry with no id");
            }
            if (!seen.add(r.id())) {
                throw new IllegalStateException("Rule catalog contains duplicate id: " + r.id());
            }
        }

        RuleCatalog catalog = new RuleCatalog(parsed.rules());
        long enabled = catalog.enabled().size();
        log.info("Loaded rule catalog: total={} enabled={} disabled={}",
                catalog.size(), enabled, catalog.size() - enabled);
        return catalog;
    }

    /** Top-level YAML envelope: {@code rules: [...]}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogFile(List<Rule> rules) {
    }
}
