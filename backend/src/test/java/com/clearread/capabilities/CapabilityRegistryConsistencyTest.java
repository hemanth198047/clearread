package com.clearread.capabilities;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRegistryConsistencyTest {

    private static final Set<String> KNOWN_ADAPTERS = Set.of(
            "markdown-commonmark-gfm",
            "plain-text-heuristic",
            "json-tree",
            "yaml-safe",
            "xml-secure",
            "csv-table",
            "tsv-table",
            "log-lines",
            "config-text",
            "source-code",
            "html-sanitize"
    );

    private static final Set<String> KNOWN_CONVERTERS = Set.of(
            "cleardoc-to-markdown",
            "cleardoc-to-html-sanitized",
            "cleardoc-to-pdf-constrained"
    );

    @Test
    void supportedFormatsHaveAdaptersAndExportsIncludePdf() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode registry;
        try (InputStream in = getClass().getResourceAsStream("/capabilities/registry.json")) {
            assertThat(in).isNotNull();
            registry = mapper.readTree(in);
        }

        assertThat(registry.path("registryVersion").asText()).isEqualTo("2026.10.5");

        Set<String> supportedIds = new HashSet<>();
        for (JsonNode format : registry.path("formats")) {
            String id = format.path("formatId").asText();
            String ingest = format.path("ingest").asText();
            if ("supported".equals(ingest)) {
                supportedIds.add(id);
                String adapterId = format.path("adapterId").asText(null);
                assertThat(adapterId)
                        .as("supported format %s must name a known adapter", id)
                        .isIn(KNOWN_ADAPTERS);
            } else {
                assertThat(format.path("adapterId").isNull() || format.path("adapterId").asText().isBlank())
                        .as("unsupported stub %s should not claim a live adapter", id)
                        .isTrue();
            }
        }
        assertThat(supportedIds).contains("markdown", "plain-text", "html", "json", "csv");
        assertThat(supportedIds).doesNotContain("asciidoc", "rst");

        Set<String> converters = new HashSet<>();
        for (JsonNode c : registry.path("exports").path("converters")) {
            converters.add(c.path("converterId").asText());
            assertThat(c.path("artifactKind").asText()).isEqualTo("generated");
        }
        assertThat(converters).containsExactlyInAnyOrderElementsOf(KNOWN_CONVERTERS);
        assertThat(registry.path("exports").path("note").asText()).doesNotContainIgnoringCase("deferred");
    }
}
