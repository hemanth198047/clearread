package com.clearread.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coarse per-IP ingress rate limit for abuse resistance (Phase 6). Not a substitute for edge WAF.
 *
 * <p>Authenticated workspace traffic (Library, document open, ingest after folder scan) uses
 * {@code clearread.limits.api-requests-per-minute} (0 = disabled). Unauthenticated public edges
 * (preview / share / invites) use the stricter
 * {@code clearread.limits.public-api-requests-per-minute}. Local Compose sets the authenticated
 * limit to 0 so a single-user demo never 429s after large folder scans.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final ClearReadProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public ApiRateLimitFilter(ClearReadProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        // Health + cheap read-mostly bootstrap endpoints (SPA remounts / StrictMode).
        if (path.startsWith("/actuator/health") || path.equals("/v1/health")) {
            return true;
        }
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            if (path.equals("/v1/capabilities") || path.startsWith("/v1/branding/")) {
                return true;
            }
            // Authenticated read-heavy paths used by Library / Reader / Recent after folder scans.
            if (hasBearer(request) && isAuthenticatedReadPath(path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean authed = hasBearer(request);
        int limit = authed
                ? properties.getLimits().getApiRequestsPerMinute()
                : properties.getLimits().getPublicApiRequestsPerMinute();
        if (limit <= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = (authed ? "a:" : "p:") + clientKey(request);
        long minute = System.currentTimeMillis() / 60_000L;
        Window w = windows.compute(key, (k, prev) -> {
            if (prev == null || prev.minute != minute) {
                return new Window(minute, new AtomicInteger(1));
            }
            prev.count.incrementAndGet();
            return prev;
        });
        if (w.count.get() > limit) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limit\",\"message\":\"Too many requests\"}");
            return;
        }
        // opportunistic cleanup
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute < minute - 1);
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Library browse, document open, search, and related GETs that spike after large local-folder
     * scans / SPA remounts. Mutations (upload, reprocess, share create) still count when a limit is set.
     */
    static boolean isAuthenticatedReadPath(String path) {
        if (path.equals("/v1/library") || path.startsWith("/v1/library?")) {
            return true;
        }
        if (path.equals("/v1/search") || path.startsWith("/v1/search?")) {
            return true;
        }
        if (path.equals("/v1/documents") || path.startsWith("/v1/documents?")) {
            return true;
        }
        // /v1/documents/{id} and nested read paths (cleardoc, original, diagnostics, meta GET, etc.)
        if (path.startsWith("/v1/documents/")) {
            // Mutating subpaths are POST/PATCH/DELETE and are not exempted via shouldNotFilter GET gate.
            return true;
        }
        if (path.startsWith("/v1/workspaces")) {
            return true;
        }
        if (path.startsWith("/v1/me")) {
            return true;
        }
        return false;
    }

    private static boolean hasBearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
