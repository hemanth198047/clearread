package com.clearread.transform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MermaidTextFallbackTest {

    @Test
    void extractsFlowchartLabelsWithoutExecuting() {
        String src = """
                flowchart TD
                  A[Start] --> B{Decision}
                  B -->|Yes| C[Done]
                """;
        MermaidTextFallback.Result result = MermaidTextFallback.summarize(src);
        assertThat(result).isNotNull();
        assertThat(result.outlineText()).contains("text fallback");
        assertThat(result.outlineText()).containsIgnoringCase("flowchart");
        assertThat(result.labels()).contains("Start", "Decision", "Done");
    }

    @Test
    void scrubsHtmlAndScripts() {
        String src = "flowchart LR\n  A[<script>alert(1)</script>Bad] --> B[Ok]";
        MermaidTextFallback.Result result = MermaidTextFallback.summarize(src);
        assertThat(result).isNotNull();
        assertThat(result.scrubbed()).isTrue();
        assertThat(result.outlineText()).doesNotContain("<script>");
        assertThat(result.outlineText()).doesNotContain("alert(1)");
    }

    @Test
    void detectsMermaidLanguage() {
        assertThat(MermaidTextFallback.isMermaidLanguage("mermaid")).isTrue();
        assertThat(MermaidTextFallback.isMermaidLanguage("Mermaid theme")).isTrue();
        assertThat(MermaidTextFallback.isMermaidLanguage("java")).isFalse();
    }
}
