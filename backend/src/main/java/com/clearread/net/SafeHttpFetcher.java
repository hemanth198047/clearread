package com.clearread.net;

import com.clearread.config.ClearReadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * SSRF-hardened HTTP(S) fetcher: validates every hop, pins resolved addresses,
 * TLS hostname verification, never forwards cookies/credentials, enforces size/time limits.
 */
@Component
public class SafeHttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(SafeHttpFetcher.class);

    public record FetchResult(
            byte[] body,
            String contentType,
            String finalUrl,
            String filenameHint,
            int statusCode,
            Map<String, String> meta,
            boolean looksLikeWebPage
    ) {
    }

    private final UrlAccessPolicy policy;
    private final ClearReadProperties properties;

    public SafeHttpFetcher(ClearReadProperties properties) {
        this.properties = properties;
        var fetch = properties.getFetch();
        this.policy = new UrlAccessPolicy(fetch.isAllowPrivateNetwork(), Set.copyOf(fetch.getAllowedPorts()));
    }

    public UrlAccessPolicy policy() {
        return policy;
    }

    public FetchResult fetch(String rawUrl) {
        long deadline = System.nanoTime() + Duration.ofMillis(properties.getFetch().getTotalTimeoutMs()).toNanos();
        URI current = URI.create(rawUrl.trim());
        List<String> hops = new ArrayList<>();
        int redirects = 0;
        int maxRedirects = properties.getFetch().getMaxRedirects();

        while (true) {
            if (System.nanoTime() > deadline) {
                throw new UrlAccessPolicy.Denied("TIMEOUT", "Fetch exceeded total time limit");
            }
            UrlAccessPolicy.ValidatedTarget target = policy.validateUri(current);
            hops.add(UrlLogRedactor.redact(current.toString()));
            log.info("Fetching URL hop={} url={}", hops.size(), UrlLogRedactor.redact(current.toString()));

            RawResponse response = exchange(target, deadline);
            if (isRedirect(response.statusCode())) {
                redirects++;
                if (redirects > maxRedirects) {
                    throw new UrlAccessPolicy.Denied("TOO_MANY_REDIRECTS", "Too many redirects");
                }
                String location = response.header("location");
                if (location == null || location.isBlank()) {
                    throw new UrlAccessPolicy.Denied("BAD_REDIRECT", "Redirect without Location");
                }
                current = target.uri().resolve(location.trim());
                policy.validateRedirect(current);
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new UrlAccessPolicy.Denied("HTTP_STATUS", "Remote server returned HTTP " + response.statusCode());
            }

            byte[] body = response.body();
            String contentType = response.header("content-type");
            String disposition = response.header("content-disposition");
            boolean webPage = looksLikeWebPage(contentType, body, disposition);
            String filename = filenameFromDisposition(disposition);
            if (filename == null) {
                filename = filenameFromPath(target.uri().getPath());
            }

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("finalUrl", UrlLogRedactor.redact(target.uri().toString()));
            meta.put("hops", String.valueOf(hops.size()));
            meta.put("status", String.valueOf(response.statusCode()));
            if (contentType != null) {
                meta.put("contentType", contentType);
            }

            return new FetchResult(body, contentType, target.uri().toString(), filename, response.statusCode(), meta, webPage);
        }
    }

    private RawResponse exchange(UrlAccessPolicy.ValidatedTarget target, long deadlineNanos) {
        IOException last = null;
        for (InetAddress address : target.addresses()) {
            policy.assertPublic(address);
            try {
                return exchangePinned(target, address, deadlineNanos);
            } catch (IOException e) {
                last = e;
            }
        }
        throw new UrlAccessPolicy.Denied("CONNECT_FAILED", "Could not connect: " + (last != null ? last.getMessage() : "unknown"));
    }

    private RawResponse exchangePinned(UrlAccessPolicy.ValidatedTarget target, InetAddress address, long deadlineNanos)
            throws IOException {
        int connectMs = properties.getFetch().getConnectTimeoutMs();
        int readMs = properties.getFetch().getReadTimeoutMs();
        long remainingMs = Math.max(1, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        connectMs = (int) Math.min(connectMs, remainingMs);
        readMs = (int) Math.min(readMs, remainingMs);

        boolean https = "https".equalsIgnoreCase(target.uri().getScheme());
        try (Socket socket = https ? createTlsSocket(target, address, connectMs, readMs)
                : createPlainSocket(address, target.port(), connectMs, readMs)) {
            InetAddress connected = ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress();
            policy.assertResolvedMatches(target, connected);

            String hostHeader = target.uri().getHost();
            String path = target.uri().getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            if (target.uri().getRawQuery() != null) {
                path = path + "?" + target.uri().getRawQuery();
            }

            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "User-Agent: ClearRead-Fetch/1.0\r\n"
                    + "Accept: */*\r\n"
                    + "Accept-Encoding: gzip, deflate, identity\r\n"
                    + "Connection: close\r\n"
                    // Never forward cookies or Authorization
                    + "\r\n";

            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            return readHttpResponse(in, deadlineNanos);
        }
    }

    private Socket createPlainSocket(InetAddress address, int port, int connectMs, int readMs) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(address, port), connectMs);
        socket.setSoTimeout(readMs);
        return socket;
    }

    private Socket createTlsSocket(UrlAccessPolicy.ValidatedTarget target, InetAddress address, int connectMs, int readMs)
            throws IOException {
        try {
            SSLContext ctx = SSLContext.getDefault();
            SSLSocketFactory factory = ctx.getSocketFactory();
            SSLSocket ssl = (SSLSocket) factory.createSocket();
            ssl.connect(new InetSocketAddress(address, target.port()), connectMs);
            ssl.setSoTimeout(readMs);
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            params.setServerNames(List.of(new SNIHostName(target.uri().getHost())));
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            // Hostname verification via HTTPS endpoint identification + SNI
            return ssl;
        } catch (Exception e) {
            throw new IOException("TLS handshake failed: " + e.getMessage(), e);
        }
    }

    private RawResponse readHttpResponse(InputStream in, long deadlineNanos) throws IOException {
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            ensureTime(deadlineNanos);
            int b = in.read();
            if (b < 0) {
                throw new IOException("Unexpected EOF in headers");
            }
            headerBuf.write(b);
            if (b == '\r' && (state == 0 || state == 2)) {
                state++;
            } else if (b == '\n' && (state == 1 || state == 3)) {
                state++;
            } else {
                state = 0;
            }
            if (headerBuf.size() > 64 * 1024) {
                throw new UrlAccessPolicy.Denied("HEADERS_TOO_LARGE", "HTTP headers too large");
            }
        }
        String headerText = headerBuf.toString(StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0 || !lines[0].startsWith("HTTP/")) {
            throw new IOException("Invalid HTTP response");
        }
        String[] statusParts = lines[0].split(" ", 3);
        int status = Integer.parseInt(statusParts[1]);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }

        long maxBytes = properties.getFetch().getMaxBytes();
        long maxDecompressed = properties.getFetch().getMaxDecompressedBytes();
        String encoding = first(headers, "content-encoding");
        String transfer = first(headers, "transfer-encoding");
        byte[] rawBody;
        if (transfer != null && transfer.toLowerCase(Locale.ROOT).contains("chunked")) {
            rawBody = readChunked(in, maxBytes, deadlineNanos);
        } else {
            Long contentLength = parseLong(first(headers, "content-length"));
            if (contentLength != null && contentLength > maxBytes) {
                throw new UrlAccessPolicy.Denied("BODY_TOO_LARGE", "Response exceeds size limit");
            }
            rawBody = readLimited(in, contentLength != null ? contentLength : maxBytes, maxBytes, deadlineNanos);
        }

        byte[] body = decodeBody(rawBody, encoding, maxDecompressed);
        return new RawResponse(status, headers, body);
    }

    private static byte[] decodeBody(byte[] raw, String encoding, long maxDecompressed) throws IOException {
        if (encoding == null || encoding.isBlank() || "identity".equalsIgnoreCase(encoding)) {
            return raw;
        }
        InputStream wrapped;
        if ("gzip".equalsIgnoreCase(encoding)) {
            wrapped = new GZIPInputStream(new java.io.ByteArrayInputStream(raw));
        } else if ("deflate".equalsIgnoreCase(encoding)) {
            wrapped = new InflaterInputStream(new java.io.ByteArrayInputStream(raw));
        } else {
            throw new UrlAccessPolicy.Denied("ENCODING_DENIED", "Unsupported Content-Encoding");
        }
        try (InputStream in = wrapped) {
            return readLimited(in, maxDecompressed, maxDecompressed, Long.MAX_VALUE);
        }
    }

    private static byte[] readChunked(InputStream in, long maxBytes, long deadlineNanos) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            ensureTime(deadlineNanos);
            String sizeLine = readLine(in);
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int size = Integer.parseInt(hex, 16);
            if (size == 0) {
                // trailers
                while (true) {
                    String trailer = readLine(in);
                    if (trailer.isEmpty()) {
                        break;
                    }
                }
                break;
            }
            if (out.size() + size > maxBytes) {
                throw new UrlAccessPolicy.Denied("BODY_TOO_LARGE", "Response exceeds size limit");
            }
            byte[] chunk = in.readNBytes(size);
            if (chunk.length != size) {
                throw new IOException("Truncated chunk");
            }
            out.write(chunk);
            // CRLF after chunk
            if (in.read() != '\r' || in.read() != '\n') {
                throw new IOException("Bad chunk framing");
            }
        }
        return out.toByteArray();
    }

    private static byte[] readLimited(InputStream in, long expectedOrMax, long hardMax, long deadlineNanos) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long remaining = Math.min(expectedOrMax, hardMax);
        while (remaining > 0) {
            ensureTime(deadlineNanos);
            int toRead = (int) Math.min(buf.length, remaining);
            int n = in.read(buf, 0, toRead);
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            remaining -= n;
            if (out.size() > hardMax) {
                throw new UrlAccessPolicy.Denied("BODY_TOO_LARGE", "Response exceeds size limit");
            }
        }
        // Detect streaming past declared limit
        if (in.available() > 0) {
            int extra = in.read();
            if (extra >= 0) {
                throw new UrlAccessPolicy.Denied("BODY_TOO_LARGE", "Response exceeds size limit");
            }
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > 8192) {
                throw new IOException("Line too long");
            }
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    private static void ensureTime(long deadlineNanos) {
        if (System.nanoTime() > deadlineNanos) {
            throw new UrlAccessPolicy.Denied("TIMEOUT", "Fetch exceeded total time limit");
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    static boolean looksLikeWebPage(String contentType, byte[] body, String disposition) {
        if (disposition != null && disposition.toLowerCase(Locale.ROOT).contains("attachment")) {
            return false;
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean htmlType = ct.contains("text/html") || ct.contains("application/xhtml");
        String sample = new String(body, 0, Math.min(body.length, 2048), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        boolean htmlBody = sample.contains("<html") || sample.contains("<!doctype html") || sample.contains("<head") && sample.contains("<body");
        return htmlType && htmlBody;
    }

    private static String filenameFromDisposition(String disposition) {
        if (disposition == null) {
            return null;
        }
        // filename="..."
        int idx = disposition.toLowerCase(Locale.ROOT).indexOf("filename=");
        if (idx < 0) {
            return null;
        }
        String rest = disposition.substring(idx + 9).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            return end > 1 ? rest.substring(1, end) : null;
        }
        int semi = rest.indexOf(';');
        return (semi >= 0 ? rest.substring(0, semi) : rest).trim();
    }

    private static String filenameFromPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? null : name;
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static Long parseLong(String v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record RawResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            return first(headers, name.toLowerCase(Locale.ROOT));
        }
    }
}
