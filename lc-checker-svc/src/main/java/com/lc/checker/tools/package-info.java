/**
 * Deterministic tool callbacks for Tier-3 (AGENT + tool) rules.
 *
 * <p>Every method annotated with {@link org.springframework.ai.tool.annotation.Tool}
 * is a pure function — no I/O, no LLM, no shared state. Spring AI converts each
 * {@code @Tool} method into a {@code ToolCallback} that the chat client can hand
 * to the model. Tools are bound per-rule via {@code Rule.tools()} from
 * {@code rules/catalog.yml}; resolution is done by
 * {@link com.lc.checker.tools.ToolRegistry}.
 *
 * <p>Tools are <em>task-shaped</em> (one call per rule's deterministic core),
 * not atomic primitives. The shape of a tool intentionally matches the shape
 * of the rule that uses it — duplicate logic is acceptable in exchange for
 * lower LLM error rates from chained tool calls.
 *
 * @see com.lc.checker.tools.ArithmeticTools
 * @see com.lc.checker.tools.ToolRegistry
 */
package com.lc.checker.tools;
