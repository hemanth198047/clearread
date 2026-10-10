package com.clearread.ai;

import java.util.List;
import java.util.Map;

/**
 * Provider-neutral enrichment SPI. Implementations must not fetch URLs, execute tools,
 * change permissions, contact unrelated services, or modify documents outside the request.
 */
public interface AiProvider {

    String providerId();

    boolean isLocal();

    boolean isAvailable();

    String modelId();

    record BlockExcerpt(String blockNodeId, String type, String text, int sourceStart, int sourceEnd) {
    }

    record EnrichmentRequest(
            String kind,
            String systemPrompt,
            String userPromptTemplate,
            String promptTemplateId,
            String promptVersion,
            List<BlockExcerpt> excerpts,
            Map<String, Object> options
    ) {
    }

    record EnrichmentResponse(
            String providerId,
            String modelId,
            Map<String, Object> proposal,
            int tokensIn,
            int tokensOut
    ) {
    }

    EnrichmentResponse enrich(EnrichmentRequest request) throws AiProviderException;

    class AiProviderException extends Exception {
        public AiProviderException(String message) {
            super(message);
        }

        public AiProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
