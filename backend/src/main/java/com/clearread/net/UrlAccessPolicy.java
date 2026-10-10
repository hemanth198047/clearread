package com.clearread.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF defenses for outbound URL fetches (Phase 0 threat model T2 / Phase 3).
 * Private-network ingestion is disabled by default.
 */
public final class UrlAccessPolicy {

    public static final class Denied extends RuntimeException {
        private final String code;

        public Denied(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record ValidatedTarget(URI uri, List<InetAddress> addresses, int port) {
    }

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<Integer> BLOCKED_PORTS = Set.of(25, 465, 587, 2525);

    private final boolean allowPrivateNetwork;
    private final Set<Integer> allowedPorts;

    public UrlAccessPolicy(boolean allowPrivateNetwork, Set<Integer> allowedPorts) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.allowedPorts = allowedPorts == null || allowedPorts.isEmpty()
                ? Set.of(80, 443)
                : Set.copyOf(allowedPorts);
    }

    public ValidatedTarget validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new Denied("EMPTY_URL", "URL is required");
        }
        String trimmed = rawUrl.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new Denied("INVALID_URL", "URL is not a valid absolute URI");
        }
        return validateUri(uri);
    }

    public ValidatedTarget validateUri(URI uri) {
        if (uri == null || !uri.isAbsolute()) {
            throw new Denied("INVALID_URL", "URL must be absolute");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new Denied("SCHEME_DENIED", "Only http and https URLs are allowed");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
            throw new Denied("USERINFO_DENIED", "URLs with userinfo (credentials) are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new Denied("HOST_REQUIRED", "URL must include a host");
        }
        String hostLower = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(hostLower)
                || hostLower.endsWith(".localhost")
                || hostLower.endsWith(".local")
                || "metadata.google.internal".equals(hostLower)) {
            throw new Denied("HOST_DENIED", "Host is not allowed");
        }
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        if (BLOCKED_PORTS.contains(port) || !allowedPorts.contains(port)) {
            throw new Denied("PORT_DENIED", "Port " + port + " is not allowed");
        }

        List<InetAddress> addresses = resolveAll(host);
        for (InetAddress address : addresses) {
            assertPublic(address);
        }
        return new ValidatedTarget(uri, List.copyOf(addresses), port);
    }

    /** Re-validate after redirect: same policy on the Location URI. */
    public ValidatedTarget validateRedirect(URI location) {
        return validateUri(location);
    }

    public void assertPublic(InetAddress address) {
        if (address == null) {
            throw new Denied("RESOLVE_FAILED", "Could not resolve host");
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isSiteLocalAddress()) {
            if (!allowPrivateNetwork) {
                throw new Denied("PRIVATE_ADDRESS", "Private, loopback, link-local, or multicast addresses are blocked");
            }
        }
        if (isUniqueLocalIpv6(address) || isCarrierGradeNat(address) || isMetadataAddress(address)) {
            if (!allowPrivateNetwork) {
                throw new Denied("PRIVATE_ADDRESS", "Non-public address ranges are blocked");
            }
        }
        // IPv4-mapped IPv6
        if (address instanceof Inet6Address inet6 && inet6.isIPv4CompatibleAddress()) {
            // handled via isLoopback / site local on mapped forms in JDK for many cases
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            // 0.0.0.0/8, 100.64.0.0/10 (CGNAT already), 169.254.0.0/16 link-local already,
            // 192.0.0.0/24, 192.0.2.0/24, 198.18.0.0/15, 198.51.100.0/24, 203.0.113.0/24, 224.0.0.0/4, 240.0.0.0/4
            if (b0 == 0
                    || (b0 == 192 && b1 == 0)
                    || (b0 == 198 && (b1 == 18 || b1 == 19 || b1 == 51))
                    || (b0 == 203 && b1 == 0)
                    || b0 >= 224) {
                if (!allowPrivateNetwork) {
                    throw new Denied("PRIVATE_ADDRESS", "Reserved or non-routable address blocked");
                }
            }
        }
    }

    public void assertResolvedMatches(ValidatedTarget expected, InetAddress connected) {
        assertPublic(connected);
        boolean match = expected.addresses().stream().anyMatch(a -> a.equals(connected));
        if (!match) {
            // Allow if connected address is still public and same host re-resolved (anti-rebind: we pin)
            throw new Denied("DNS_REBIND", "Connection address does not match pre-validated resolution");
        }
    }

    private static List<InetAddress> resolveAll(String host) {
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            if (all == null || all.length == 0) {
                throw new Denied("RESOLVE_FAILED", "Host did not resolve");
            }
            List<InetAddress> list = new ArrayList<>();
            for (InetAddress a : all) {
                list.add(a);
            }
            return list;
        } catch (UnknownHostException e) {
            throw new Denied("RESOLVE_FAILED", "Host did not resolve");
        }
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address inet6)) {
            return false;
        }
        byte[] b = inet6.getAddress();
        // fc00::/7
        return (b[0] & 0xfe) == 0xfc;
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] b = address.getAddress();
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        // 100.64.0.0/10
        return b0 == 100 && b1 >= 64 && b1 <= 127;
    }

    private static boolean isMetadataAddress(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] b = address.getAddress();
        // 169.254.169.254 and 169.254.0.0/16 already link-local; explicit cloud metadata
        return (b[0] & 0xff) == 169 && (b[1] & 0xff) == 254;
    }
}
