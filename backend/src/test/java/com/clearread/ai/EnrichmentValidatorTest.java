package com.clearread.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentValidatorTest {

    @Test
    void validatesHeadingsSchemaAndCoverage() {
        var result = EnrichmentValidator.validate(
                "headings",
                Map.of(
                        "label", "AI-generated headings",
                        "suggestions", List.of(Map.of(
                                "blockNodeId", "b1",
                                "suggestedText", "Intro",
                                "sourceStart", 0,
                                "sourceEnd", 10
                        ))
                ),
                Set.of("b1", "b2")
        );
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void rejectsMissingSuggestions() {
        var result = EnrichmentValidator.validate("headings", Map.of("label", "x"), Set.of("b1"));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void flagsDuplicateAndUnknownBlocksInSegmentation() {
        var result = EnrichmentValidator.validate(
                "segmentation",
                Map.of(
                        "label", "AI-generated segmentation",
                        "segments", List.of(Map.of(
                                "title", "A",
                                "blockNodeIds", List.of("b1", "b1", "missing")
                        ))
                ),
                Set.of("b1", "b2")
        );
        assertThat(result.valid()).isTrue();
        assertThat(result.warnings().stream().anyMatch(w -> w.contains("duplicated") || w.contains("unknown"))).isTrue();
    }
}
