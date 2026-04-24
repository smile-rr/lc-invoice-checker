package com.lc.checker.model.enums;

/**
 * Classification of parsed :47A: conditions per logic-flow.md Stage 1 Part B.
 *
 * <ul>
 *   <li>{@link #REQUIREMENT} — condition imposes a must-do on the presentation.</li>
 *   <li>{@link #RESTRICTION} — condition forbids something (e.g. "third party documents not acceptable").</li>
 *   <li>{@link #RELAXATION} — condition waives a default rule.</li>
 *   <li>{@link #UNKNOWN} — LLM could not classify; treat as REQUIREMENT for safety during review.</li>
 * </ul>
 */
public enum ConditionType {
    REQUIREMENT,
    RESTRICTION,
    RELAXATION,
    UNKNOWN
}
