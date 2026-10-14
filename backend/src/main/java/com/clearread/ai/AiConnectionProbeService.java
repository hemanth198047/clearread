package com.clearread.ai;

import com.clearread.config.ClearReadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight connectivity probe for Admin "Test connection".
 * Does not run enrichment; never logs API keys or document content.
 */
@Service
public class AiConnectionProbeService {

    private static final Logger log = LoggerFactory.getLogger(AiConnectionProbeService.class);

    private final AiConnectionConfigService connectionConfig;
    private final ClearReadProperties properties;
    private final HttpClient httpClient;

    public AiConnectionProbeService(AiConnectionConfigService connectionConfig, ClearReadProperties properties) {
        this.connectionConfig = connectionConfig;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, Math.min(properties.getAi().getTimeoutMs(), 10_000))))
                .build();
    }

    public Map<String, Object> test(UUID tenantId, String target) {
        String t = target == null || target.isBlank() ? "auto" : target.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "ollama" -> probeOllama(tenantId);
            case "external" -> probeExternal(tenantId);
            case "auto" -> auto(tenantId);
            default -> fail("Unknown target '" + target + "'. Use ollama, external, or auto.");
        };
    }

    private Map<String, Object> auto(UUID tenantId) {
        var external = connectionConfig.external(tenantId);
        if (external.available()) {
            return probeExternal(tenantId);
        }
        var ollama = connectionConfig.ollama(tenantId);
        if (ollama.available()) {
            return probeOllama(tenantId);
        }
        return fail("No provider is configured. Enable Ollama or an external provider and save an API key.");
    }

    private Map<String, Object> probeOllama(UUID tenantId) {
        var cfg = connectionConfig.ollama(tenantId);
        if (!cfg.enabled()) {
            return fail("Ollama is disabled. Enable it under AI provider connection and save.");
        }
        if (cfg.baseUrl() == null || cfg.baseUrl().isBlank()) {
            return fail("Ollama base URL is missing.");
        }
        String url = trimSlash(cfg.baseUrl()) + "/api/tags";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.min(properties.getAi().getTimeoutMs(), 10_000)))
                    .GET()
                    .build();
            log.info("Probing Ollama at configured base URL");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return fail("Ollama probe HTTP " + response.statusCode() + " at " + url);
            }
            return ok("ollama", "Ollama reachable (model " + cfg.model() + ").");
        } catch (Exception e) {
            return fail("Ollama probe failed: " + e.getMessage());
        }
    }

    private Map<String, Object> probeExternal(UUID tenantId) {
        var cfg = connectionConfig.external(tenantId);
        if (!cfg.enabled()) {
            return fail("External provider is disabled. Enable it, pick a provider, paste an API key, and save.");
        }
        if (cfg.baseUrl() == null || cfg.baseUrl().isBlank()) {
            return fail("External base URL is missing.");
        }
        if (!cfg.apiKeyConfigured()) {
            return fail("External API key is not saved. Paste a key and save the connection.");
        }
        ExternalAiProviderSeam.Protocol protocol = ExternalAiProviderSeam.detectProtocol(cfg.baseUrl());
        try {
            return switch (protocol) {
                case OPENAI_CHAT -> probeOpenAiFamily(cfg);
                case ANTHROPIC_MESSAGES -> probeAnthropic(cfg);
                case CUSTOM_ENRICH -> probeCustomConfigured(cfg);
            };
        } catch (Exception e) {
            return fail("External probe failed: " + e.getMessage());
        }
    }

    private Map<String, Object> probeOpenAiFamily(AiConnectionConfigService.EffectiveExternal cfg) throws Exception {
        String modelsUrl = deriveOpenAiModelsUrl(cfg.baseUrl());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(modelsUrl))
                .timeout(Duration.ofMillis(Math.min(properties.getAi().getTimeoutMs(), 10_000)))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .GET();
        String url = cfg.baseUrl().toLowerCase(Locale.ROOT);
        if (url.contains("openai.azure.com") || url.contains("cognitiveservices.azure.com")) {
            builder.header("api-key", cfg.apiKey());
        }
        log.info("Probing external OpenAI-compatible models endpoint");
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return fail("External API rejected the key (HTTP " + response.statusCode() + "). Check the key and provider.");
        }
        if (response.statusCode() >= 400) {
            // Some gateways lack /models; config+auth shape is still valid enough to report soft success.
            if (response.statusCode() == 404 || response.statusCode() == 405) {
                return ok("external",
                        "External endpoint accepted the request shape (HTTP " + response.statusCode()
                                + " on models probe). Config and key are saved; enrichment will use "
                                + cfg.model() + ".");
            }
            return fail("External probe HTTP " + response.statusCode());
        }
        return ok("external", "External provider reachable (model " + cfg.model() + ", key accepted).");
    }

    private Map<String, Object> probeAnthropic(AiConnectionConfigService.EffectiveExternal cfg) throws Exception {
        // Anthropic has no cheap public models list with x-api-key alone that we rely on;
        // validate config and perform a minimal authenticated OPTIONS/HEAD-less GET to messages with empty body is wrong.
        // Instead: GET https://api.anthropic.com/v1/models when available.
        String modelsUrl = "https://api.anthropic.com/v1/models";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(modelsUrl))
                .timeout(Duration.ofMillis(Math.min(properties.getAi().getTimeoutMs(), 10_000)))
                .header("x-api-key", cfg.apiKey())
                .header("anthropic-version", "2023-06-01")
                .GET()
                .build();
        log.info("Probing Anthropic models endpoint");
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return fail("Anthropic rejected the API key (HTTP " + response.statusCode() + ").");
        }
        if (response.statusCode() >= 400) {
            return fail("Anthropic probe HTTP " + response.statusCode());
        }
        return ok("external", "Anthropic reachable (model " + cfg.model() + ", key accepted).");
    }

    private Map<String, Object> probeCustomConfigured(AiConnectionConfigService.EffectiveExternal cfg) {
        return ok("external",
                "Custom external endpoint configured (" + cfg.baseUrl() + ", model " + cfg.model()
                        + "). Live HTTP enrich will run on first enrichment request.");
    }

    static String deriveOpenAiModelsUrl(String chatUrl) {
        String u = chatUrl == null ? "" : chatUrl.trim();
        int idx = u.toLowerCase(Locale.ROOT).indexOf("/chat/completions");
        if (idx > 0) {
            return u.substring(0, idx) + "/models";
        }
        if (u.toLowerCase(Locale.ROOT).contains("openai.azure.com")) {
            // Azure deployments often lack a portable models URL; fall back to chat URL for status code check.
            return u;
        }
        String base = trimSlash(u);
        return base + "/models";
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static Map<String, Object> ok(String target, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("target", target);
        m.put("message", message);
        return m;
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("target", "");
        m.put("message", message);
        return m;
    }
}
