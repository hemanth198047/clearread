package com.clearread.export;

import com.clearread.transform.cleardoc.ClearDocModels;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.Metadata;
import com.clearread.transform.cleardoc.ClearDocModels.Source;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlExportConverterTest {

    @Test
    void mitigatesCsvFormulaInjection() {
        assertEquals("'=1+1", HtmlExportConverter.csvSafe("=1+1"));
        assertEquals("ok", HtmlExportConverter.csvSafe("ok"));
        assertTrue(HtmlExportConverter.csvSafe("+cmd").startsWith("'"));
    }

    @Test
    void includesProvenanceBannerAndPrintCss() {
        ClearDoc doc = new ClearDoc(
                "1.0.0",
                "d1",
                "r1",
                "t1",
                new Source("markdown", "markdown-commonmark-gfm", "1.0.1", "abc", "a.md", 10),
                new Metadata("Hello HTML", "en", Map.of()),
                List.of(),
                List.of(),
                List.of(ClearDocModels.paragraph("n1", List.of(ClearDocModels.text("Body", null)), null))
        );
        ExportConverter.ExportResult result = new HtmlExportConverter().convert(doc);
        String html = new String(result.bytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains("clearread-artifact\" content=\"generated\""));
        assertTrue(html.contains("ClearRead generated HTML export"));
        assertTrue(html.contains("@media print"));
        assertTrue(html.contains("break-inside:avoid"));
    }
}
