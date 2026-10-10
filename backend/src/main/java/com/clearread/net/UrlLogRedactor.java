package com.clearread.net;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Redacts sensitive query parameters before logging. */
public final class UrlLogRedactor {

    private static final Set<String> SENSITIVE = Set.of(
            "token", "access_token", "refresh_token", "id_token", "key", "api_key", "apikey",
            "password", "passwd", "secret", "authorization", "auth", "sig", "signature",
            "session", "sessionid", "cookie", "code", "client_secret"
    );

    private UrlLogRedactor() {
    }

    public static String redact(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return stripUserInfo(uri).toString();
            }
            String redactedQuery = java.util.Arrays.stream(query.split("&"))
                    .map(UrlLogRedactor::redactPair)
                    .collect(Collectors.joining("&"));
            URI base = stripUserInfo(uri);
            return new URI(
                    base.getScheme(),
                    base.getAuthority(),
                    base.getPath(),
                    redactedQuery,
                    base.getFragment()
            ).toString();
        } catch (Exception e) {
            return "[unparseable-url]";
        }
    }

    private static String redactPair(String pair) {
        int eq = pair.indexOf('=');
        String name = eq >= 0 ? pair.substring(0, eq) : pair;
        String decoded;
        try {
            decoded = URLDecoder.decode(name, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            decoded = name.toLowerCase(Locale.ROOT);
        }
        if (SENSITIVE.contains(decoded) || decoded.contains("token") || decoded.contains("secret")
                || decoded.contains("password") || decoded.endsWith("key")) {
            return name + "=REDACTED";
        }
        return pair;
    }

    private static URI stripUserInfo(URI uri) throws Exception {
        return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null);
    }
}
