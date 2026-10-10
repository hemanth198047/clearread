package com.clearread.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SearchSanitizeTest {

    @Test
    void escapesScriptButKeepsMarks() {
        String raw = "hello <script>alert(1)</script> <mark>hit</mark>";
        String out = SearchService.sanitizeHeadline(raw);
        assertFalse(out.contains("<script>"));
        assertTrueKeepMark(out);
    }

    private static void assertTrueKeepMark(String out) {
        assertEquals(true, out.contains("<mark>") && out.contains("</mark>"));
    }
}
