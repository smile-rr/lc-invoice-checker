package com.lc.checker.infra.samples;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
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
 * Loads {@code samples.yaml} once at startup and exposes lookups by sample id.
 * Backing files are held on the classpath under {@code samples/files/} and
 * read on demand via {@link #readBytes(String)}.
 *
 * <p>Why classpath instead of MinIO: samples are static, version-controlled
 * demo assets that travel with the API jar. Bundling avoids an extra startup
 * race against MinIO and keeps the deploy atomic. User-uploaded LC/invoice
 * pairs continue to use {@code InvoiceFileStore} → MinIO; only the curated
 * picker assets live here.
 *
 * <p>Fail-fast on duplicate ids or missing backing files at boot.
 */
@Component
public class SamplesRegistry {

    private static final Logger log = LoggerFactory.getLogger(SamplesRegistry.class);

    private final ResourceLoader resourceLoader;
    private final String filesPathBase;
    private final List<SampleManifest> samples;
    private final Map<String, SampleManifest> byId;

    public SamplesRegistry(
            ResourceLoader resourceLoader,
            @Value("${samples.manifest-path:classpath:/samples/samples.yaml}") String manifestPath,
            @Value("${samples.files-path:classpath:/samples/files/}") String filesPath) throws IOException {
        this.resourceLoader = resourceLoader;
        this.filesPathBase = filesPath.endsWith("/") ? filesPath : filesPath + "/";

        Resource resource = resourceLoader.getResource(manifestPath);
        if (!resource.exists()) {
            throw new IllegalStateException("Samples manifest not found at " + manifestPath);
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        SampleManifest.Envelope parsed;
        try (InputStream in = resource.getInputStream()) {
            parsed = mapper.readValue(in, SampleManifest.Envelope.class);
        }
        if (parsed == null || parsed.samples() == null || parsed.samples().isEmpty()) {
            throw new IllegalStateException("Samples manifest at " + manifestPath + " is empty");
        }

        Map<String, SampleManifest> idMap = new LinkedHashMap<>();
        for (SampleManifest s : parsed.samples()) {
            if (s.id() == null || s.id().isBlank()) {
                throw new IllegalStateException("samples.yaml entry has no id: " + s);
            }
            if (idMap.put(s.id(), s) != null) {
                throw new IllegalStateException("Duplicate sample id: " + s.id());
            }
            requireExists(s.invoice(), "invoice", s.id());
            requireExists(s.passLc(), "pass_lc", s.id());
            if (s.failLc() != null && !s.failLc().isBlank()) {
                requireExists(s.failLc(), "fail_lc", s.id());
            }
        }
        this.samples = List.copyOf(parsed.samples());
        this.byId = Map.copyOf(idMap);
        log.info("SamplesRegistry loaded {} sample pairs from {}", samples.size(), manifestPath);
    }

    /** All registered samples, in declaration order. */
    public List<SampleManifest> all() {
        return samples;
    }

    public Optional<SampleManifest> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Read a backing file by its filename (relative to {@code samples/files/}). */
    public byte[] readBytes(String filename) throws IOException {
        Resource r = resourceLoader.getResource(filesPathBase + filename);
        if (!r.exists()) {
            throw new IOException("Sample file not found on classpath: " + filename);
        }
        try (InputStream in = r.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private void requireExists(String filename, String role, String sampleId) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalStateException(
                    "sample '" + sampleId + "' missing required field: " + role);
        }
        Resource r = resourceLoader.getResource(filesPathBase + filename);
        if (!r.exists()) {
            throw new IllegalStateException(
                    "sample '" + sampleId + "' " + role + " refers to missing file: " + filename);
        }
    }
}
