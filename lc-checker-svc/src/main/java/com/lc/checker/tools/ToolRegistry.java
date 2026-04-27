package com.lc.checker.tools;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Resolves YAML tool names to Spring AI {@link ToolCallback} instances.
 *
 * <p>At startup we scan every Spring bean in the {@code com.lc.checker.tools}
 * package for methods annotated with {@link Tool}, then index them by the
 * annotation's {@code name} (or the method name as fallback). A rule's
 * {@code tools: [foo, bar]} list in {@code rules/catalog.yml} is resolved to
 * a {@code List<ToolCallback>} that {@code AgentStrategy} hands to
 * {@code ChatClient.prompt().toolCallbacks(...)}.
 *
 * <p>Per-rule narrow toolset is the design rule — never give an agent the
 * union of all tools. Misrouting and tool-call accuracy degrade sharply past
 * ~10–15 tools.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolCallback> byName = new LinkedHashMap<>();

    public ToolRegistry(ApplicationContext context) {
        for (String beanName : context.getBeanDefinitionNames()) {
            // CRITICAL: filter by package BEFORE instantiating. Calling
            // getBean(name) for every bean during this constructor used to
            // eagerly construct the whole graph, which deadlocks the moment
            // any downstream bean depends transitively on ToolRegistry
            // itself (AgentStrategy → ToolRegistry, then JobDispatcher →
            // pipeline → AgentStrategy made the cycle reachable). getType()
            // resolves the bean class without triggering construction.
            Class<?> resolvedType;
            try {
                resolvedType = context.getType(beanName);
            } catch (Exception ignored) {
                continue;
            }
            if (resolvedType == null) continue;
            // Spring may proxy the bean; walk the user class to find original methods.
            Class<?> userClass = resolvedType.getName().contains("$$")
                    ? resolvedType.getSuperclass()
                    : resolvedType;
            if (userClass == null || !userClass.getPackageName().startsWith("com.lc.checker.tools")) {
                continue;
            }
            // Skip ourselves. ToolRegistry lives in the tools package too,
            // so the package filter would otherwise match this bean while
            // it's still under construction, producing a self-cycle.
            if (ToolRegistry.class.equals(userClass)) {
                continue;
            }
            // Only now is it safe to instantiate — the bean lives in the
            // tools package, so its construction graph is tight and won't
            // loop back through ToolRegistry.
            Object bean = context.getBean(beanName);
            for (Method method : userClass.getDeclaredMethods()) {
                Tool ann = method.getAnnotation(Tool.class);
                if (ann == null) continue;
                String toolName = ToolUtils.getToolName(method);
                if (byName.containsKey(toolName)) {
                    throw new IllegalStateException(
                            "Duplicate @Tool name '" + toolName + "' on method " + userClass.getSimpleName() + "."
                                    + method.getName() + " — already registered from another bean");
                }
                ToolDefinition def = ToolDefinition.builder()
                        .name(toolName)
                        .description(ToolUtils.getToolDescription(method))
                        .inputSchema(JsonSchemaGenerator.generateForMethodInput(method))
                        .build();
                MethodToolCallback callback = MethodToolCallback.builder()
                        .toolDefinition(def)
                        .toolMethod(method)
                        .toolObject(bean)
                        .build();
                byName.put(toolName, callback);
            }
        }
        log.info("ToolRegistry loaded {} @Tool method(s): {}", byName.size(), byName.keySet());
    }

    /**
     * Resolve a list of tool names from {@code Rule.tools()} into Spring AI
     * tool callbacks. Throws if any name is unknown — fail fast at rule
     * execution rather than silently dropping a tool the prompt expects.
     */
    public List<ToolCallback> resolve(List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        return names.stream()
                .map(name -> {
                    ToolCallback cb = byName.get(name);
                    if (cb == null) {
                        throw new IllegalStateException(
                                "Unknown tool name '" + name + "'. Known tools: " + byName.keySet());
                    }
                    return cb;
                })
                .toList();
    }

    /** Names of all registered tools — useful for diagnostics. */
    public List<String> registeredNames() {
        return List.copyOf(byName.keySet());
    }
}
