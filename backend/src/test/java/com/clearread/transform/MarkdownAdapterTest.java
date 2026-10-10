package com.clearread.transform;

import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownAdapterTest {

    private final MarkdownAdapter adapter = new MarkdownAdapter();

    @Test
    void parsesHeadingsListsAndCode() {
        String md = """
                # Title
                
                Intro paragraph.
                
                ## Section
                
                - item one
                - item two
                
                ```java
                System.out.println("hi");
                ```
                """;
        ClearDoc doc = adapter.parse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "sample.md", "abc", md.getBytes(StandardCharsets.UTF_8)
        );

        assertThat(doc.schemaVersion()).isEqualTo("1.0.0");
        assertThat(doc.source().adapterId()).isEqualTo("markdown-commonmark-gfm");
        assertThat(doc.outline()).extracting(o -> o.text()).contains("Title", "Section");
        assertThat(doc.body()).isNotEmpty();
        assertThat(doc.body().stream().anyMatch(n -> "codeBlock".equals(n.get("type")))).isTrue();
        assertThat(doc.body().getFirst().get("provenance")).isNotNull();
    }
}
