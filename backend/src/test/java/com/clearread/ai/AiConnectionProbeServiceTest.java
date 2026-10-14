package com.clearread.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiConnectionProbeServiceTest {

    @Test
    void deriveOpenAiModelsUrlFromChatCompletions() {
        assertThat(AiConnectionProbeService.deriveOpenAiModelsUrl(
                "https://api.openai.com/v1/chat/completions"))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(AiConnectionProbeService.deriveOpenAiModelsUrl(
                "https://api.groq.com/openai/v1/chat/completions"))
                .isEqualTo("https://api.groq.com/openai/v1/models");
    }
}
