package com.clearread.ai;

import com.clearread.ai.AiConnectionSettingsRepository.AiConnectionSettings;
import com.clearread.config.ClearReadProperties;
import com.clearread.tenancy.TenantContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Resolves effective AI connection config: per-tenant DB overrides when present,
 * otherwise env / ClearReadProperties defaults. API keys are never returned in full.
 */
@Service
public class AiConnectionConfigService {

    private static final ThreadLocal<UUID> TENANT_OVERRIDE = new ThreadLocal<>();

    private final AiConnectionSettingsRepository repository;
    private final ClearReadProperties properties;

    public AiConnectionConfigService(AiConnectionSettingsRepository repository, ClearReadProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public <T> T withTenant(UUID tenantId, Supplier<T> action) {
        UUID prev = TENANT_OVERRIDE.get();
        TENANT_OVERRIDE.set(tenantId);
        try {
            return action.get();
        } finally {
            if (prev == null) {
                TENANT_OVERRIDE.remove();
            } else {
                TENANT_OVERRIDE.set(prev);
            }
        }
    }

    public void withTenant(UUID tenantId, Runnable action) {
        withTenant(tenantId, () -> {
            action.run();
            return null;
        });
    }

    public <T> T callWithTenant(UUID tenantId, Callable<T> action) throws Exception {
        UUID prev = TENANT_OVERRIDE.get();
        TENANT_OVERRIDE.set(tenantId);
        try {
            return action.call();
        } finally {
            if (prev == null) {
                TENANT_OVERRIDE.remove();
            } else {
                TENANT_OVERRIDE.set(prev);
            }
        }
    }

    public void runWithTenant(UUID tenantId, Callable<Void> action) throws Exception {
        callWithTenant(tenantId, action);
    }

    public UUID resolveTenantId() {
        UUID override = TENANT_OVERRIDE.get();
        if (override != null) {
            return override;
        }
        return TenantContext.tenantIdOrNull();
    }

    public EffectiveOllama ollama() {
        return ollama(resolveTenantId());
    }

    public EffectiveOllama ollama(UUID tenantId) {
        ClearReadProperties.Ai.Ollama env = properties.getAi().getOllama();
        Optional<AiConnectionSettings> row = tenantId == null ? Optional.empty() : repository.find(tenantId);
        if (row.isPresent()) {
            AiConnectionSettings s = row.get();
            String url = blankTo(s.ollamaBaseUrl(), env.getBaseUrl());
            String model = blankTo(s.ollamaModel(), env.getModel());
            return new EffectiveOllama(s.ollamaEnabled(), url, model, true);
        }
        return new EffectiveOllama(env.isEnabled(), env.getBaseUrl(), env.getModel(), false);
    }

    public EffectiveExternal external() {
        return external(resolveTenantId());
    }

    public EffectiveExternal external(UUID tenantId) {
        ClearReadProperties.Ai.External env = properties.getAi().getExternal();
        Optional<AiConnectionSettings> row = tenantId == null ? Optional.empty() : repository.find(tenantId);
        if (row.isPresent()) {
            AiConnectionSettings s = row.get();
            String url = blankTo(s.externalBaseUrl(), env.getBaseUrl());
            String model = blankTo(s.externalModel(), env.getModel());
            String key = s.externalApiKey() != null && !s.externalApiKey().isBlank()
                    ? s.externalApiKey()
                    : (env.getApiKey() == null ? "" : env.getApiKey());
            return new EffectiveExternal(s.externalEnabled(), url, key, model, true);
        }
        return new EffectiveExternal(
                env.isEnabled(),
                env.getBaseUrl() == null ? "" : env.getBaseUrl(),
                env.getApiKey() == null ? "" : env.getApiKey(),
                env.getModel() == null ? "" : env.getModel(),
                false
        );
    }

    public Map<String, Object> toPublicMap(UUID tenantId) {
        EffectiveOllama ollama = ollama(tenantId);
        EffectiveExternal external = external(tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ollamaEnabled", ollama.enabled());
        m.put("ollamaBaseUrl", ollama.baseUrl());
        m.put("ollamaModel", ollama.model());
        m.put("ollamaAvailable", ollama.available());
        m.put("ollamaSource", ollama.fromDb() ? "db" : "env");
        m.put("externalEnabled", external.enabled());
        m.put("externalBaseUrl", external.baseUrl());
        m.put("externalModel", external.model());
        m.put("externalAvailable", external.available());
        m.put("externalSource", external.fromDb() ? "db" : "env");
        m.put("apiKeyConfigured", external.apiKeyConfigured());
        m.put("apiKeyHint", maskApiKey(external.apiKey()));
        m.put("defaults", Map.of(
                "ollamaBaseUrl", properties.getAi().getOllama().getBaseUrl(),
                "ollamaModel", properties.getAi().getOllama().getModel(),
                "externalBaseUrl", nullToEmpty(properties.getAi().getExternal().getBaseUrl()),
                "externalModel", nullToEmpty(properties.getAi().getExternal().getModel())
        ));
        return m;
    }

    public AiConnectionSettings upsert(
            UUID tenantId,
            boolean ollamaEnabled,
            String ollamaBaseUrl,
            String ollamaModel,
            boolean externalEnabled,
            String externalBaseUrl,
            String externalModel,
            String apiKeyOrNull,
            boolean clearApiKey
    ) {
        String existingKey = repository.find(tenantId).map(AiConnectionSettings::externalApiKey).orElse("");
        if (existingKey.isBlank()) {
            String envKey = properties.getAi().getExternal().getApiKey();
            existingKey = envKey == null ? "" : envKey;
        }
        String nextKey;
        if (clearApiKey) {
            nextKey = "";
        } else if (apiKeyOrNull != null && !apiKeyOrNull.isBlank()) {
            nextKey = apiKeyOrNull.trim();
        } else {
            nextKey = existingKey;
        }
        String ollamaUrl = blankTo(ollamaBaseUrl, properties.getAi().getOllama().getBaseUrl());
        String ollamaMod = blankTo(ollamaModel, properties.getAi().getOllama().getModel());
        String extUrl = externalBaseUrl == null ? "" : externalBaseUrl.trim();
        String extMod = externalModel == null ? "" : externalModel.trim();
        AiConnectionSettings next = new AiConnectionSettings(
                tenantId,
                ollamaEnabled,
                ollamaUrl,
                ollamaMod,
                externalEnabled,
                extUrl,
                nextKey,
                extMod
        );
        repository.upsert(next);
        return next;
    }

    public String configFingerprint(UUID tenantId) {
        EffectiveOllama o = ollama(tenantId);
        EffectiveExternal e = external(tenantId);
        return o.enabled() + "|" + o.baseUrl() + "|" + o.model()
                + "|" + e.enabled() + "|" + e.baseUrl() + "|" + e.model()
                + "|" + (e.apiKeyConfigured() ? "key" : "nokey");
    }

    static String maskApiKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 4) {
            return "••••";
        }
        return "••••" + key.substring(key.length() - 4);
    }

    private static String blankTo(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return value.trim();
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    public record EffectiveOllama(boolean enabled, String baseUrl, String model, boolean fromDb) {
        public boolean available() {
            return enabled && baseUrl != null && !baseUrl.isBlank();
        }
    }

    public record EffectiveExternal(
            boolean enabled,
            String baseUrl,
            String apiKey,
            String model,
            boolean fromDb
    ) {
        public boolean apiKeyConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }

        public boolean available() {
            return enabled && baseUrl != null && !baseUrl.isBlank();
        }
    }
}
