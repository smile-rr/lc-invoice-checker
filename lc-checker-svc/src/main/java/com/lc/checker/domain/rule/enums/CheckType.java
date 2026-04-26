package com.lc.checker.domain.rule.enums;

/**
 * Two-type rule engine. Every rule is either deterministic (PROGRAMMATIC) or
 * resolved by an LLM agent (AGENT). The agent handles applicability
 * (replaces the old activation gate) and verdict in one round-trip.
 */
public enum CheckType {
    PROGRAMMATIC,
    AGENT
}
