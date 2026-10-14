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
 * Local Ollama adapter. Available when enabled via Admin AI connection settings
 * or CLEARREAD_AI_OLLAMA_ENABLED. Never logs document content by default.
 */
@Component
public class OllamaAiProvider implements AiProvider {

    public static final String ID = "ollama";
    private static final Logger log = LoggerFactory.getLogger(OllamaAiProvider.class);

    private final ClearReadProperties properties;
    private final AiConnectionConfigService connectionConfig;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OllamaAiProvider(
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
        return true;
    }

    @Override
    public boolean isAvailable() {
        return connectionConfig.ollama().available();
    }

    @Override
    public String modelId() {
        return connectionConfig.ollama().model();
    }

    @Override
    public EnrichmentResponse enrich(EnrichmentRequest request) throws AiProviderException {
        AiConnectionConfigService.EffectiveOllama cfg = connectionConfig.ollama();
        if (!cfg.available()) {
            throw new AiProviderException(
                    "Ollama provider is not configured. Set base URL/model under Admin → AI provider connection "
                            + "(or CLEARREAD_AI_OLLAMA_ENABLED=true)."
            );
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", cfg.model());
            body.put("stream", false);
            body.put("format", "json");
            body.put("messages", java.util.List.of(
                    Map.of("role", "system", "content", request.systemPrompt()),
                    Map.of("role", "user", "content", buildUserMessage(request))
            ));
            String json = objectMapper.writeValueAsString(body);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(cfg.baseUrl()) + "/api/chat"))
                    .timeout(Duration.ofMillis(properties.getAi().getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            log.info("Ollama enrich kind={} model={} excerpts={}", request.kind(), cfg.model(), request.excerpts().size());
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new AiProviderException("Ollama HTTP " + response.statusCode());
            }
            if (PromptInjectionGuard.responseLooksLikeToolAbuse(response.body())) {
                throw new AiProviderException("Ollama response rejected by safety filter");
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("message").path("content").asText("");
            @SuppressWarnings("unchecked")
            Map<String, Object> proposal = objectMapper.readValue(content, Map.class);
            proposal.putIfAbsent("artifactKind", "ai-derived");
            proposal.putIfAbsent("label", "AI-generated " + request.kind());
            proposal.put("generated", true);
            int tokensIn = root.path("prompt_eval_count").asInt(0);
            int tokensOut = root.path("eval_count").asInt(0);
            return new EnrichmentResponse(providerId(), modelId(), proposal, tokensIn, tokensOut);
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiProviderException("Ollama call failed: " + e.getMessage(), e);
        }
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

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
