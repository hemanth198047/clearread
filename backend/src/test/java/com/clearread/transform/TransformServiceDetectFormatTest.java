package com.clearread.transform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransformServiceDetectFormatTest {

    private final TransformService service = new TransformService(List.of());

    @Test
    void detectsAsciidocAndRstForHonestRejection() {
        assertThat(service.detectFormat("guide.adoc", "text/plain")).isEqualTo("asciidoc");
        assertThat(service.detectFormat("guide.asciidoc", null)).isEqualTo("asciidoc");
        assertThat(service.detectFormat("guide.rst", null)).isEqualTo("rst");
        assertThat(service.detectFormat("doc.rest", "text/x-rst")).isEqualTo("rst");
    }

    @Test
    void stillDetectsCoreFormats() {
        assertThat(service.detectFormat("a.md", "text/markdown")).isEqualTo("markdown");
        assertThat(service.detectFormat("a.json", "application/json")).isEqualTo("json");
        assertThat(service.detectFormat("a.csv", null)).isEqualTo("csv");
    }
}
