package com.clearread.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlAccessPolicyTest {

    private final UrlAccessPolicy policy = new UrlAccessPolicy(false, Set.of(80, 443));

    @Test
    void rejectsNonHttpSchemes() {
        var ex = assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("file:///etc/passwd"));
        assertEquals("SCHEME_DENIED", ex.code());
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("ftp://example.com/a"));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("gopher://example.com/a"));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("dict://example.com/a"));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("ldap://example.com/a"));
    }

    @Test
    void rejectsUserInfo() {
        var ex = assertThrows(UrlAccessPolicy.Denied.class,
                () -> policy.validate("https://user:pass@example.com/secret"));
        assertEquals("USERINFO_DENIED", ex.code());
    }

    @Test
    void rejectsLocalhostAndMetadataHosts() {
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("http://localhost/x"));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("http://127.0.0.1/x"));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("http://[::1]/x"));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("http://0.0.0.0/x"));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("http://169.254.169.254/latest/meta-data/"));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("http://metadata.google.internal/"));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("http://metadata/latest/meta-data/"));
    }

    @Test
    void rejectsPrivateAndLinkLocalAddresses() throws Exception {
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.assertPublic(InetAddress.getByName("10.0.0.1")));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.assertPublic(InetAddress.getByName("192.168.1.1")));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.assertPublic(InetAddress.getByName("172.16.0.1")));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.assertPublic(InetAddress.getByName("169.254.1.1")));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.assertPublic(InetAddress.getByName("224.0.0.1")));
    }

    @Test
    void rejectsDisallowedPorts() {
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("https://example.com:22/"));
        assertThrows(UrlAccessPolicy.Denied.class, () -> policy.validate("http://example.com:8080/"));
    }

    @Test
    void allowsPublicHttps() {
        var target = policy.validate("https://example.com/path?q=1");
        assertEquals("example.com", target.uri().getHost());
        assertEquals(443, target.port());
        assertTrue(target.addresses().size() >= 1);
    }

    @Test
    void privateAllowedWhenEnabled() throws Exception {
        UrlAccessPolicy open = new UrlAccessPolicy(true, Set.of(80, 443, 8080));
        open.assertPublic(InetAddress.getByName("127.0.0.1"));
        open.assertPublic(InetAddress.getByName("10.0.0.5"));
    }
}
