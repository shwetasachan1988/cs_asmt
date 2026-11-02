package com.sa.agentic.orchestrator.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Claude-backed advisor. Active only when {@code ANTHROPIC_API_KEY} is set; the key is read from the environment and
 * is never logged, stored or written into artifacts. Without a key the advisor is simply off.
 */
@Component
public class ClaudeLlmAdvisor implements LlmAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ClaudeLlmAdvisor.class);

    private final String apiKey;
    private final String model;
    private volatile AnthropicClient client;

    public ClaudeLlmAdvisor(@Value("${orchestrator.llm.api-key:}") String apiKey,
                            @Value("${orchestrator.llm.model:claude-opus-5-5}") String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    @Override
    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public Optional<String> advise(String systemPrompt, String userPrompt) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            Message response = client().messages().create(MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(4000L)
                    .system(systemPrompt)
                    .addUserMessage(userPrompt)
                    .build());
            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining("\n"))
                    .trim();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (RuntimeException e) {
            // advisory only: log the class, never the request (which may contain requirement text) or any credential
            log.warn("LLM advisory call failed ({}); continuing without it", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
                }
                c = client;
            }
        }
        return c;
    }
}
