package com.lc.checker.infra.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import com.lc.checker.stage.check.strategy.TypeABStrategy;
import com.lc.checker.stage.check.strategy.TypeBStrategy;
import com.lc.checker.stage.extract.vision.VisionLlmExtractor;

/**
 * Single point where Spring AI enters the application.
 *
 * <p>Spring AI is used <b>only</b> for LLM inference (Type-B / Type-AB semantic rule
 * checks and Vision-LLM invoice extraction). The Docling / MinerU extractor hops use
 * plain {@code RestClient} — see {@code memory/feedback_spring_ai_scope.md} for rationale.
 *
 * <p>Provider swap is a runtime concern: set {@code LLM_BASE_URL} / {@code LLM_MODEL} /
 * {@code LLM_API_KEY} env vars and the OpenAI-compatible client targets DeepSeek (default),
 * MiniMax, or any compliant endpoint. Temperature is pinned to {@code 0.0} in
 * {@code application.yml} for deterministic compliance-check output.
 *
 * <p>Two {@link ChatModel} beans exist in the app:
 * <ul>
 *   <li>{@code openAiChatModel} (auto-configured, @Primary) — language LLM for TypeBStrategy / TypeABStrategy</li>
 *   <li>{@code visionChatModel} (named) — vision LLM for VisionLlmExtractor</li>
 * </ul>
 */
@Configuration
public class ChatClientConfig {

    /**
     * Default system message applied to every ChatClient call. Individual call sites
     * (TypeBStrategy, TypeABStrategy, VisionLlmExtractor) override with task-specific instructions.
     */
    private static final String DEFAULT_SYSTEM = """
            You are a trade finance compliance assistant working strictly from UCP 600 and ISBP 821.
            You return ONLY valid JSON matching the requested schema, with no markdown fences and no prose.
            If uncertain, return a null field rather than guessing.
            """;

    /**
     * Marks the auto-configured language {@link ChatModel} as Primary, so that
     * {@code ChatClient.Builder} (auto-configured by Spring AI) resolves to it unambiguously.
     * The vision extractor uses its own named {@code visionChatModel} bean via
     * {@code @Qualifier("visionChatModel")} in {@link VisionLlmExtractor}.
     */
    @Bean
    @Primary
    public ChatModel primaryChatModel(
            @Qualifier("openAiChatModel") ChatModel delegate) {
        return delegate;
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(DEFAULT_SYSTEM)
                .build();
    }
}
