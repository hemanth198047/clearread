package com.clearread.ai;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalAiProviderSeamTest {

    @Test
    void detectProtocolOpenAiFamily() {
        assertThat(ExternalAiProviderSeam.detectProtocol("https://api.openai.com/v1/chat/completions"))
                .isEqualTo(ExternalAiProviderSeam.Protocol.OPENAI_CHAT);
        assertThat(ExternalAiProviderSeam.detectProtocol("https://api.groq.com/openai/v1/chat/completions"))
                .isEqualTo(ExternalAiProviderSeam.Protocol.OPENAI_CHAT);
        assertThat(ExternalAiProviderSeam.detectProtocol("https://openrouter.ai/api/v1/chat/completions"))
                .isEqualTo(ExternalAiProviderSeam.Protocol.OPENAI_CHAT);
        assertThat(ExternalAiProviderSeam.detectProtocol(
                "https://res.openai.azure.com/openai/deployments/x/chat/completions?api-version=2024-08-01-preview"))
                .isEqualTo(ExternalAiProviderSeam.Protocol.OPENAI_CHAT);
    }

    @Test
    void detectProtocolAnthropic() {
        assertThat(ExternalAiProviderSeam.detectProtocol("https://api.anthropic.com/v1/messages"))
                .isEqualTo(ExternalAiProviderSeam.Protocol.ANTHROPIC_MESSAGES);
    }

    @Test
    void detectProtocolCustomEnrich() {
        assertThat(ExternalAiProviderSeam.detectProtocol("https://api.example.com/v1/enrich"))
                .isEqualTo(ExternalAiProviderSeam.Protocol.CUSTOM_ENRICH);
    }

    @Test
    void decorateProposalAddsMarkers() {
        Map<String, Object> proposal = new HashMap<>();
        proposal.put("summary", "hello");
        Map<String, Object> out = ExternalAiProviderSeam.decorateProposal(proposal, "summary");
        assertThat(out.get("artifactKind")).isEqualTo("ai-derived");
        assertThat(out.get("label")).isEqualTo("AI-generated summary");
        assertThat(out.get("generated")).isEqualTo(true);
    }
}
