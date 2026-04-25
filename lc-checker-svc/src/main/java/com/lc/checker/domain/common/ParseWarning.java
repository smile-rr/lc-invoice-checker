package com.lc.checker.domain.common;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Non-fatal note from parsing or extraction. The document is still returned;
 * the warning travels alongside it on {@link FieldEnvelope#warnings()}.
 *
 * <p>Codes (free-form strings, not an enum, so new codes can be added without
 * touching this file): {@code UNKNOWN_TAG}, {@code UNKNOWN_ALIAS},
 * {@code TAG_SEQUENCE_VIOLATION}, {@code MT701_CONTINUATION_DETECTED},
 * {@code CHARSET_VIOLATION}, {@code CROSS_FIELD_*}, etc.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ParseWarning(
        String code,
        String source,    // tag name (LC) or extractor field name (invoice); may be null
        String message
) {
}
