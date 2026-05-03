package com.lc.checker.infra.llm;

import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import com.lc.checker.stage.extract.vision.VisionLlmExtractor;

/**
 * Single point where Spring AI enters the application — for the <b>text LLM</b>
 * only (AGENT rule checks).
 *
 * <p><b>Vision LLM extraction does NOT use Spring AI.</b> {@code VisionLlmExtractor}
 * goes through a plain {@code RestClient} against an OpenAI-compatible
 * {@code /chat/completions} endpoint. Rationale:
 * <ul>
 *   <li>Two vision instances run side-by-side (remote Qwen Cloud + local MLX server) —
 *       both speak the same OpenAI-compatible protocol; switching is a config-only swap.</li>
 *   <li>Spring AI's multimodal support is provider-specific (response_format, image
 *       payload shapes) and would constrain provider choice / version coupling.</li>
 *   <li>Our prompt + parser tolerate the JSON-or-text response variability that small
 *       vision models exhibit; a strict {@code .entity(...)} mapping would be brittle.</li>
 * </ul>
 * See {@code extractors/README.md} for the canonical-field flow that ties both
 * extractors back into the field-pool registry.
 *
 * <p>Provider swap (text LLM): set {@code LLM_BASE_URL} / {@code LLM_MODEL} /
 * {@code LLM_API_KEY} env vars; the OpenAI-compatible client targets DeepSeek
 * (default), MiniMax, or any compliant endpoint. Temperature is pinned to {@code 0.0}
 * in {@code application.yml} for deterministic compliance-check output.
 */
@Configuration
public class ChatClientConfig {

    /**
     * Default system message applied to every ChatClient call. Individual call sites
     * (AgentStrategy, VisionLlmExtractor) override with task-specific instructions.
     */
    private static final String DEFAULT_SYSTEM = """
            You are a trade finance compliance assistant working strictly from UCP 600 and ISBP 821.
            You return ONLY valid JSON matching the requested schema, with no markdown fences and no prose.
            If uncertain, return a null field rather than guessing.
            """;

    /**
     * Marks the auto-configured language {@link ChatModel} as Primary, so that
     * {@code ChatClient.Builder} (auto-configured by Spring AI) resolves to it
     * unambiguously. {@link VisionLlmExtractor} bypasses Spring AI entirely
     * (see class javadoc).
     */
    @Bean
    @Primary
    public ChatModel primaryChatModel(
            @Qualifier("openAiChatModel") ChatModel delegate) {
        return delegate;
    }

    @Bean
    public ChatClient chatClient(
            ChatClient.Builder builder,
            @Value("${spring.ai.openai.chat.options.model:}") String model) {
        ChatClient.Builder b = builder.defaultSystem(DEFAULT_SYSTEM);
        if (isQwenFamily(model)) {
            // Qwen3-family models emit <think>…</think> reasoning tokens by default,
            // breaking AgentStrategy's strict JSON contract and inflating token cost.
            // enable_thinking=false is Qwen-proprietary — GLM, Kimi, and MiniMax reject
            // it with HTTP 400 when routed through the DashScope compatible endpoint.
            b = b.defaultOptions(OpenAiChatOptions.builder()
                    .extraBody(Map.of("enable_thinking", false))
                    .build());
        }
        return b.build();
    }

    private static boolean isQwenFamily(String model) {
        return model != null && model.toLowerCase().startsWith("qwen");
    }
}
