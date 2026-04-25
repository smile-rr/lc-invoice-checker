package com.lc.checker.domain.session.enums;

import com.lc.checker.domain.result.StageTrace;

/**
 * Outcome marker for a single pipeline stage recorded in {@code StageTrace}.
 */
public enum StageStatus {
    SUCCESS,
    FAILED,
    SKIPPED
}
