package com.clearread.ai;

import com.clearread.config.ClearReadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configurable external provider seam. Supports:
 * <ul>
 *   <li>OpenAI-compatible Chat Completions ({@code .../chat/completions})</li>
 *   <li>Anthropic Messages API ({@code api.anthropic.com/.../messages})</li>
 *   <li>Legacy ClearRead custom enrich POST (JSON body with {@code proposal})</li>
 * </ul>
 * Disabled by default. Requires workspace ai_allow_external=true and ai_local_only=false.
 * API key is never logged and never echoed in full from the Admin API.
 */
@Component
public class ExternalAiProviderSeam implements AiProvider {

    public static final String ID = "external";
    private static final Logger log = LoggerFactory.getLogger(ExternalAiProviderSeam.class);

    enum Protocol {
        OPENAI_CHAT,
        ANTHROPIC_MESSAGES,
        CUSTOM_ENRICH
    }

    private final ClearReadProperties properties;
    private final AiConnectionConfigService connectionConfig;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ExternalAiProviderSeam(
            ClearReadProperties properties,
            AiConnectionConfigService connectionConfig,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.connectionConfig = connectionConfig;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, properties.getAi().getTimeoutMs())))
                .build();
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return connectionConfig.external().available();
    }

    @Override
    public String modelId() {
        String model = connectionConfig.external().model();
        return model == null || model.isBlank() ? "external-default" : model;
    }

    @Override
    public EnrichmentResponse enrich(EnrichmentRequest request) throws AiProviderException {
        AiConnectionConfigService.EffectiveExternal cfg = connectionConfig.external();
        if (!cfg.available()) {
            throw new AiProviderException(
                    "External AI provider is not configured. Pick a provider under Admin → AI provider connection and paste an API key."
            );
        }
        Protocol protocol = detectProtocol(cfg.baseUrl());
        try {
            return switch (protocol) {
                case OPENAI_CHAT -> enrichOpenAiChat(cfg, request);
                case ANTHROPIC_MESSAGES -> enrichAnthropic(cfg, request);
                case CUSTOM_ENRICH -> enrichCustom(cfg, request);
            };
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiProviderException("External provider call failed: " + e.getMessage(), e);
        }
    }

    static Protocol detectProtocol(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim().toLowerCase(Locale.ROOT);
        if (u.contains("api.anthropic.com") || (u.contains("/v1/messages") && u.contains("anthropic"))) {
            return Protocol.ANTHROPIC_MESSAGES;
        }
        if (u.contains("/chat/completions") || u.contains("openai.azure.com") || u.contains("openrouter.ai")
                || u.contains("api.groq.com") || u.contains("api.openai.com")) {
            return Protocol.OPENAI_CHAT;
        }
        return Protocol.CUSTOM_ENRICH;
    }

    private EnrichmentResponse enrichOpenAiChat(
            AiConnectionConfigService.EffectiveExternal cfg,
            EnrichmentRequest request
    ) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId());
        body.put("temperature", 0.2);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()
                        + "\nRespond with a single JSON object only (no markdown fences)."),
                Map.of("role", "user", "content", buildUserMessage(request))
        ));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(cfg.baseUrl().trim()))
                .timeout(Duration.ofMillis(properties.getAi().getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        applyAuthHeaders(builder, cfg);
        log.info("External OpenAI-chat enrich kind={} model={} excerpts={}",
                request.kind(), modelId(), request.excerpts().size());
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new AiProviderException("External provider HTTP " + response.statusCode());
        }
        if (PromptInjectionGuard.responseLooksLikeToolAbuse(response.body())) {
            throw new AiProviderException("External response rejected by safety filter");
        }
        JsonNode root = objectMapper.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        Map<String, Object> proposal = decorateProposal(parseProposalJson(content), request.kind());
        int tokensIn = root.path("usage").path("prompt_tokens").asInt(0);
        int tokensOut = root.path("usage").path("completion_tokens").asInt(0);
        return new EnrichmentResponse(providerId(), modelId(), proposal, tokensIn, tokensOut);
    }

    private EnrichmentResponse enrichAnthropic(
            AiConnectionConfigService.EffectiveExternal cfg,
            EnrichmentRequest request
    ) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId());
        body.put("max_tokens", 4096);
        body.put("system", request.systemPrompt()
                + "\nRespond with a single JSON object only (no markdown fences).");
        body.put("messages", List.of(
                Map.of("role", "user", "content", buildUserMessage(request))
        ));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(cfg.baseUrl().trim()))
                .timeout(Duration.ofMillis(properties.getAi().getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (cfg.apiKeyConfigured()) {
            builder.header("x-api-key", cfg.apiKey());
        }
        log.info("External Anthropic enrich kind={} model={} excerpts={}",
                request.kind(), modelId(), request.excerpts().size());
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new AiProviderException("External provider HTTP " + response.statusCode());
        }
        if (PromptInjectionGuard.responseLooksLikeToolAbuse(response.body())) {
            throw new AiProviderException("External response rejected by safety filter");
        }
        JsonNode root = objectMapper.readTree(response.body());
        String content = "";
        JsonNode contentArr = root.path("content");
        if (contentArr.isArray() && !contentArr.isEmpty()) {
            content = contentArr.path(0).path("text").asText("");
        }
        Map<String, Object> proposal = decorateProposal(parseProposalJson(content), request.kind());
        int tokensIn = root.path("usage").path("input_tokens").asInt(0);
        int tokensOut = root.path("usage").path("output_tokens").asInt(0);
        return new EnrichmentResponse(providerId(), modelId(), proposal, tokensIn, tokensOut);
    }

    private EnrichmentResponse enrichCustom(
            AiConnectionConfigService.EffectiveExternal cfg,
            EnrichmentRequest request
    ) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId());
        body.put("kind", request.kind());
        body.put("system", request.systemPrompt());
        body.put("prompt", request.userPromptTemplate());
        body.put("excerpts", request.excerpts().stream().map(e -> {
            var scan = PromptInjectionGuard.scanAndWrap(e.text());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blockNodeId", e.blockNodeId());
            m.put("type", e.type());
            m.put("sourceStart", e.sourceStart());
            m.put("sourceEnd", e.sourceEnd());
            m.put("text", scan.wrappedExcerpt());
            return m;
        }).toList());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(cfg.baseUrl().trim()))
                .timeout(Duration.ofMillis(properties.getAi().getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        applyAuthHeaders(builder, cfg);
        log.info("External custom enrich kind={} model={} excerpts={}",
                request.kind(), modelId(), request.excerpts().size());
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new AiProviderException("External provider HTTP " + response.statusCode());
        }
        if (PromptInjectionGuard.responseLooksLikeToolAbuse(response.body())) {
            throw new AiProviderException("External response rejected by safety filter");
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode proposalNode = root.has("proposal") ? root.get("proposal") : root;
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = objectMapper.convertValue(proposalNode, Map.class);
        return new EnrichmentResponse(
                providerId(),
                modelId(),
                decorateProposal(proposal, request.kind()),
                root.path("tokensIn").asInt(0),
                root.path("tokensOut").asInt(0)
        );
    }

    private void applyAuthHeaders(
            HttpRequest.Builder builder,
            AiConnectionConfigService.EffectiveExternal cfg
    ) {
        if (!cfg.apiKeyConfigured()) {
            return;
        }
        builder.header("Authorization", "Bearer " + cfg.apiKey());
        String url = cfg.baseUrl() == null ? "" : cfg.baseUrl().toLowerCase(Locale.ROOT);
        if (url.contains("openai.azure.com") || url.contains("cognitiveservices.azure.com")) {
            builder.header("api-key", cfg.apiKey());
        }
    }

    private Map<String, Object> parseProposalJson(String content) throws Exception {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                trimmed = trimmed.substring(firstNl + 1, lastFence).trim();
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = objectMapper.readValue(trimmed, Map.class);
        return proposal;
    }

    static Map<String, Object> decorateProposal(Map<String, Object> proposal, String kind) {
        proposal.putIfAbsent("artifactKind", "ai-derived");
        if (kind != null) {
            proposal.putIfAbsent("label", "AI-generated " + kind);
        }
        proposal.put("generated", true);
        return proposal;
    }

    private String buildUserMessage(EnrichmentRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.userPromptTemplate()).append("\n\nExcerpts (source-range references only):\n");
        for (AiProvider.BlockExcerpt e : request.excerpts()) {
            var scan = PromptInjectionGuard.scanAndWrap(e.text());
            sb.append("- blockNodeId=").append(e.blockNodeId())
                    .append(" type=").append(e.type())
                    .append(" sourceStart=").append(e.sourceStart())
                    .append(" sourceEnd=").append(e.sourceEnd())
                    .append('\n')
                    .append(scan.wrappedExcerpt())
                    .append('\n');
        }
        return sb.toString();
    }
}
