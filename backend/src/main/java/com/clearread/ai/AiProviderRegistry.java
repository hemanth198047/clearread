package com.clearread.ai;

import com.clearread.config.ClearReadProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AiProviderRegistry {

    private final Map<String, AiProvider> providers;
    private final ClearReadProperties properties;

    public AiProviderRegistry(List<AiProvider> providers, ClearReadProperties properties) {
        this.providers = providers.stream().collect(Collectors.toMap(AiProvider::providerId, Function.identity()));
        this.properties = properties;
    }

    public AiProvider require(String providerId) {
        String id = providerId == null || providerId.isBlank()
                ? properties.getAi().getDefaultProvider()
                : providerId;
        AiProvider provider = providers.get(id);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown AI provider: " + id);
        }
        return provider;
    }

    public List<Map<String, Object>> list() {
        return providers.values().stream().map(p -> Map.<String, Object>of(
                "id", p.providerId(),
                "local", p.isLocal(),
                "available", p.isAvailable(),
                "modelId", p.modelId()
        )).toList();
    }
}
