package com.lc.checker.model.enums;

/**
 * Which document a parsed :47A: condition targets. V1 only dispatches INVOICE-targeted
 * conditions through the DYNAMIC_47A activation path; BL / ALL / OTHER are recorded in
 * the trace but routed to the human-review queue (Layer-3 responsibility in V2).
 */
public enum ConditionTarget {
    INVOICE,
    BL,
    ALL,
    OTHER
}
