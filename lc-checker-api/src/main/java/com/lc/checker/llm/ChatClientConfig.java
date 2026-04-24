package com.lc.checker.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single point where Spring AI enters the application.
 *
 * <p>Spring AI is used <b>only</b> for LLM inference (MT700 Part-B parsing + Type-B
 * semantic checks). The Docling / MiniRU extractor hops use plain {@code RestClient} —
 * see {@code memory/feedback_spring_ai_scope.md} for the rationale.
 *
 * <p>Provider swap is a runtime concern: set {@code LLM_BASE_URL} / {@code LLM_MODEL} /
 * {@code LLM_API_KEY} env vars and the OpenAI-compatible client targets DeepSeek (default),
 * MiniMax, or any compliant endpoint. Temperature is pinned to {@code 0.0} in
 * {@code application.yml} for deterministic compliance-check output.
 */
@Configuration
public class ChatClientConfig {

    /**
     * Default system message applied to every ChatClient call. Individual call sites
     * (Mt700LlmParser, TypeBStrategy) override with task-specific instructions.
     */
    private static final String DEFAULT_SYSTEM = """
            You are a trade finance compliance assistant working strictly from UCP 600 and ISBP 821.
            You return ONLY valid JSON matching the requested schema, with no markdown fences and no prose.
            If uncertain, return a null field rather than guessing.
            """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(DEFAULT_SYSTEM)
                .build();
    }
}
