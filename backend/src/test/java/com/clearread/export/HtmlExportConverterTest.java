package com.clearread.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlExportConverterTest {

    @Test
    void mitigatesCsvFormulaInjection() {
        assertEquals("'=1+1", HtmlExportConverter.csvSafe("=1+1"));
        assertEquals("ok", HtmlExportConverter.csvSafe("ok"));
        assertTrue(HtmlExportConverter.csvSafe("+cmd").startsWith("'"));
    }
}
