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
import java.util.Map;

/**
 * Configurable external / OpenAI-compatible provider seam. Disabled by default.
 * Requires workspace ai_allow_external=true and ai_local_only=false.
 * API key is never logged and never echoed in full from the Admin API.
 */
@Component
public class ExternalAiProviderSeam implements AiProvider {

    public static final String ID = "external";
    private static final Logger log = LoggerFactory.getLogger(ExternalAiProviderSeam.class);

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
                    "External AI provider is not configured. Set base URL (and API key) under Admin → AI provider connection."
            );
        }
        try {
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
                    .uri(URI.create(cfg.baseUrl()))
                    .timeout(Duration.ofMillis(properties.getAi().getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (cfg.apiKeyConfigured()) {
                builder.header("Authorization", "Bearer " + cfg.apiKey());
            }
            log.info("External enrich kind={} model={} excerpts={}", request.kind(), modelId(), request.excerpts().size());
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
            proposal.putIfAbsent("artifactKind", "ai-derived");
            proposal.putIfAbsent("label", "AI-generated " + request.kind());
            proposal.put("generated", true);
            return new EnrichmentResponse(
                    providerId(),
                    modelId(),
                    proposal,
                    root.path("tokensIn").asInt(0),
                    root.path("tokensOut").asInt(0)
            );
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiProviderException("External provider call failed: " + e.getMessage(), e);
        }
    }
}
