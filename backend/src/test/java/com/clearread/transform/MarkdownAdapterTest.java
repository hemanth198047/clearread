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
        Object prov = doc.body().getFirst().get("provenance");
        assertThat(prov.toString()).contains("sourceRange");
        assertThat(doc.body().stream().noneMatch(n -> {
            Object p = n.get("provenance");
            return p != null && p.toString().contains("kind=inferred");
        })).isTrue();
    }

    @Test
    void parsesGfmTablesAsDataTable() {
        String md = """
                | Name | Role |
                | --- | --- |
                | Ada | Author |
                | Grace | Reader |
                """;
        ClearDoc doc = adapter.parse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Table doc", "table.md", "abc", md.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(doc.body().stream().anyMatch(n -> "dataTable".equals(n.get("type")))).isTrue();
        @SuppressWarnings("unchecked")
        var table = doc.body().stream().filter(n -> "dataTable".equals(n.get("type"))).findFirst().orElseThrow();
        assertThat(table.get("headers").toString()).contains("Name", "Role");
        assertThat(table.get("rows").toString()).contains("Ada", "Grace");
    }

    @Test
    void mermaidFenceKeepsSourceAndAddsTextFallback() {
        String md = """
                # Diagram
                
                ```mermaid
                flowchart TD
                  A[Start] --> B[End]
                ```
                """;
        ClearDoc doc = adapter.parse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "diagram.md", "abc", md.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(doc.body().stream().anyMatch(n ->
                "codeBlock".equals(n.get("type")) && String.valueOf(n.get("language")).contains("mermaid")
        )).isTrue();
        assertThat(doc.body().stream().anyMatch(n ->
                "formattedSource".equals(n.get("type")) && "mermaid-outline".equals(n.get("language"))
        )).isTrue();
        assertThat(doc.warnings().stream().anyMatch(w -> "mermaid-text-fallback".equals(w.code()))).isTrue();
        assertThat(doc.source().adapterVersion()).isEqualTo("1.0.1");
    }
}
