package com.clearread.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockLocalAiProviderTest {

    @Test
    void producesLabeledDerivedArtifactsWithoutNetwork() {
        MockLocalAiProvider provider = new MockLocalAiProvider();
        assertThat(provider.isLocal()).isTrue();
        assertThat(provider.isAvailable()).isTrue();

        var excerpts = List.of(
                new AiProvider.BlockExcerpt("h1", "heading", "Getting started with ClearRead", 0, 30),
                new AiProvider.BlockExcerpt("p1", "paragraph", "ClearRead preserves originals. MarkdownAdapter parses structure.", 31, 90)
        );
        var response = provider.enrich(new AiProvider.EnrichmentRequest(
                "summary", "guard", "template", "summary-v1", "2026.10.1", excerpts, Map.of()
        ));
        assertThat(response.providerId()).isEqualTo("mock-local");
        assertThat(response.proposal().get("label")).isEqualTo("AI-generated summary");
        assertThat(response.proposal().get("artifactKind")).isEqualTo("ai-derived");
        assertThat(String.valueOf(response.proposal().get("summary"))).isNotBlank();
    }
}
