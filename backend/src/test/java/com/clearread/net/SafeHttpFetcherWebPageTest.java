package com.clearread.net;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeHttpFetcherWebPageTest {

    @Test
    void detectsOrdinaryWebPages() {
        byte[] html = "<!DOCTYPE html><html><head><title>Hi</title></head><body>Hello</body></html>"
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(SafeHttpFetcher.looksLikeWebPage("text/html; charset=utf-8", html, null));
        assertFalse(SafeHttpFetcher.looksLikeWebPage("text/html", html, "attachment; filename=\"page.html\""));
        assertFalse(SafeHttpFetcher.looksLikeWebPage("application/json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8), null));
    }
}
