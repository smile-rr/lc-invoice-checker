package com.lc.checker.model.enums;

/**
 * Discrepancy severity per UCP/ISBP intent. MAJOR blocks the presentation; MINOR is recorded
 * but surfaced for the reviewer rather than treated as a hard fail.
 */
public enum Severity {
    MAJOR,
    MINOR
}
