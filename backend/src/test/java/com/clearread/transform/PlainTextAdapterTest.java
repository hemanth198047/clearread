package com.clearread.transform;

import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlainTextAdapterTest {

    private final PlainTextAdapter adapter = new PlainTextAdapter();

    @Test
    void marksInferredHeadings() {
        String text = "OPS RUNBOOK\n\nDo the thing carefully.\n";
        ClearDoc doc = adapter.parse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Runbook", "notes.txt", "def", text.getBytes(StandardCharsets.UTF_8)
        );

        assertThat(doc.warnings()).anyMatch(w -> "INFERRED_HEADING".equals(w.code()));
        assertThat(doc.body().getFirst().get("type")).isEqualTo("heading");
        assertThat(doc.body().getFirst().get("provenance")).isNotNull();
    }
}
