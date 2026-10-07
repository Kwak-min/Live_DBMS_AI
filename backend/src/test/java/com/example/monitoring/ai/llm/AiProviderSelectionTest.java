package com.example.monitoring.ai.llm;

import com.example.monitoring.ai.config.AiConfiguration;
import com.example.monitoring.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfiguration.class, GeminiAiInsightClient.class, ClaudeAiInsightClient.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void geminiIsTheDefaultProviderWithItsOwnKeyAndModel() {
        runner.withPropertyValues("monitoring.ai.enabled=true", "monitoring.ai.gemini-api-key=g-key",
                        "monitoring.ai.api-key=")
                .run(context -> {
                    assertThat(context).hasSingleBean(AiInsightClient.class);
                    assertThat(context.getBean(AiInsightClient.class)).isInstanceOf(GeminiAiInsightClient.class);
                    AiProperties properties = context.getBean(AiProperties.class);
                    assertThat(properties.provider()).isEqualTo("gemini");
                    assertThat(properties.model()).isEqualTo("gemini-3.8-flash");
                    assertThat(properties.generationAvailable()).isTrue();
                });
    }

    @Test
    void claudeIsSelectedExplicitlyAndUsesTheAnthropicKey() {
        runner.withPropertyValues("monitoring.ai.enabled=true", "monitoring.ai.provider=claude",
                        "monitoring.ai.gemini-api-key=g-key")
                .run(context -> {
                    assertThat(context.getBean(AiInsightClient.class)).isInstanceOf(ClaudeAiInsightClient.class);
                    AiProperties properties = context.getBean(AiProperties.class);
                    assertThat(properties.model()).isEqualTo("claude-opus-5-5");
                    assertThat(properties.generationAvailable()).isFalse();
                });
    }

    @Test
    void unknownProviderFailsFast() {
        runner.withPropertyValues("monitoring.ai.provider=openai")
                .run(context -> assertThat(context).hasFailed());
    }
}
