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
 * Local Ollama adapter seam. Disabled unless clearread.ai.ollama.enabled=true.
 * Never logs document content by default.
 */
@Component
public class OllamaAiProvider implements AiProvider {

    public static final String ID = "ollama";
    private static final Logger log = LoggerFactory.getLogger(OllamaAiProvider.class);

    private final ClearReadProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OllamaAiProvider(ClearReadProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
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
        return properties.getAi().getOllama().isEnabled();
    }

    @Override
    public String modelId() {
        return properties.getAi().getOllama().getModel();
    }

    @Override
    public EnrichmentResponse enrich(EnrichmentRequest request) throws AiProviderException {
        if (!isAvailable()) {
            throw new AiProviderException("Ollama provider is not enabled (set CLEARREAD_AI_OLLAMA_ENABLED=true)");
        }
        ClearReadProperties.Ai.Ollama cfg = properties.getAi().getOllama();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", cfg.getModel());
            body.put("stream", false);
            body.put("format", "json");
            body.put("messages", java.util.List.of(
                    Map.of("role", "system", "content", request.systemPrompt()),
                    Map.of("role", "user", "content", buildUserMessage(request))
            ));
            String json = objectMapper.writeValueAsString(body);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(cfg.getBaseUrl()) + "/api/chat"))
                    .timeout(Duration.ofMillis(properties.getAi().getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            log.info("Ollama enrich kind={} model={} excerpts={}", request.kind(), cfg.getModel(), request.excerpts().size());
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
