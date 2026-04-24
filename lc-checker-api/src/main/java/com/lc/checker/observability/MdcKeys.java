package com.lc.checker.observability;

/**
 * Canonical MDC key names. Centralised so log patterns, encoders, and the filter can't
 * drift apart. If a key name needs to change, update here and every user follows.
 */
public final class MdcKeys {

    public static final String SESSION_ID = "sessionId";
    public static final String STAGE = "stage";
    public static final String RULE_ID = "ruleId";
    public static final String CHECK_TYPE = "checkType";
    public static final String LLM_MODEL = "llmModel";

    private MdcKeys() {
    }
}
