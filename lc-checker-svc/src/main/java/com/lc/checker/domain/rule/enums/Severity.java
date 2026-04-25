package com.lc.checker.domain.rule.enums;

import com.lc.checker.domain.result.Discrepancy;

/**
 * Discrepancy severity per UCP/ISBP intent. MAJOR blocks the presentation; MINOR is recorded
 * but surfaced for the reviewer rather than treated as a hard fail.
 */
public enum Severity {
    MAJOR,
    MINOR
}
