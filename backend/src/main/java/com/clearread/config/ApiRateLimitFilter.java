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
        String path = request.getRequestURI();
        // Health + cheap read-mostly bootstrap endpoints (SPA remounts / StrictMode).
        // Still rate-limit document list/mutate paths that caused Recent "Too many requests".
        if (path.startsWith("/actuator/health") || path.equals("/v1/health")) {
            return true;
        }
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            if (path.equals("/v1/capabilities") || path.startsWith("/v1/branding/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        int limit = properties.getLimits().getApiRequestsPerMinute();
        if (limit <= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = clientKey(request);
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
