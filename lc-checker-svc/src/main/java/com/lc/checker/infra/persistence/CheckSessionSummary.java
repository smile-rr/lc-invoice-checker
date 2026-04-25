package com.lc.checker.infra.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight DTO for session history lists.
 * Avoids fetching the full JSONB payload when only metadata is needed.
 */
public record CheckSessionSummary(
    UUID id,
    String lcReference,
    String beneficiary,
    String applicant,
    Instant createdAt,
    Boolean compliant
) {}
