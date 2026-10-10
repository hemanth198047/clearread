package com.clearread.export;

import com.clearread.transform.cleardoc.ClearDocModels;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.Metadata;
import com.clearread.transform.cleardoc.ClearDocModels.Source;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PdfExportConverterTest {

    @Test
    void convertsClearDocToPdfBytes() {
        ClearDoc doc = new ClearDoc(
                "1.0.0",
                "d1",
                "r1",
                "t1",
                new Source("markdown", "markdown-commonmark-gfm", "1.0.0", "abc", "a.md", 10),
                new Metadata("Hello PDF", "en", Map.of()),
                List.of(),
                List.of(),
                List.of(
                        ClearDocModels.heading(1, "n1", List.of(ClearDocModels.text("Hello PDF", null)), null),
                        ClearDocModels.paragraph("n2", List.of(ClearDocModels.text("Body text.", null)), null)
                )
        );
        ExportConverter.ExportResult result = new PdfExportConverter().convert(doc);
        assertThat(result.contentType()).isEqualTo("application/pdf");
        assertThat(result.filenameSuffix()).isEqualTo(".pdf");
        assertThat(result.bytes().length).isGreaterThan(100);
        assertThat(new String(result.bytes(), 0, 4)).isEqualTo("%PDF");
    }
}
