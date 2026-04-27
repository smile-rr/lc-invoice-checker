package com.lc.checker.infra.samples;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One pre-defined LC + invoice sample pair declared in {@code samples.yaml}.
 *
 * <p>{@code passLc} is the MT700 filename whose verdict against this invoice is
 * intended to be PASS-only; {@code failLc} is an alternative MT700 with
 * deliberately introduced discrepancies. Both files live under
 * {@code classpath:samples/files/}.
 *
 * <p>{@code failLc} may be {@code null} when an invoice's extraction is too
 * sparse to construct a meaningful failure case.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SampleManifest(
        String id,
        String title,
        String note,
        String kind,
        String invoice,
        @JsonProperty("pass_lc") String passLc,
        @JsonProperty("fail_lc") String failLc) {

    /** Top-level YAML envelope: {@code samples: [...]}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope(List<SampleManifest> samples) {}
}
