package com.clearread.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlLogRedactorTest {

    @Test
    void redactsSensitiveQueryParams() {
        String out = UrlLogRedactor.redact("https://example.com/a?token=abc&q=1&api_key=zzz&ok=yes");
        assertTrue(out.contains("token=REDACTED"));
        assertTrue(out.contains("api_key=REDACTED"));
        assertTrue(out.contains("ok=yes"));
        assertFalse(out.contains("abc"));
        assertFalse(out.contains("zzz"));
    }
}
