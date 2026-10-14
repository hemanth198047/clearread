package com.clearread.ai;

import com.clearread.config.ClearReadProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiConnectionConfigServiceTest {

    @Mock AiConnectionSettingsRepository repository;

    ClearReadProperties properties;
    AiConnectionConfigService service;
    UUID tenantId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @BeforeEach
    void setUp() {
        properties = new ClearReadProperties();
        properties.getAi().getOllama().setEnabled(false);
        properties.getAi().getOllama().setBaseUrl("http://127.0.0.1:11434");
        properties.getAi().getOllama().setModel("llama3.2");
        properties.getAi().getExternal().setEnabled(false);
        properties.getAi().getExternal().setBaseUrl("");
        properties.getAi().getExternal().setApiKey("env-secret-key-9999");
        properties.getAi().getExternal().setModel("");
        service = new AiConnectionConfigService(repository, properties);
    }

    @Test
    void envFallbackWhenNoDbRow() {
        when(repository.find(tenantId)).thenReturn(Optional.empty());
        var ollama = service.ollama(tenantId);
        assertThat(ollama.enabled()).isFalse();
        assertThat(ollama.available()).isFalse();
        assertThat(ollama.baseUrl()).isEqualTo("http://127.0.0.1:11434");
        assertThat(ollama.fromDb()).isFalse();
    }

    @Test
    void dbOverrideEnablesOllama() {
        when(repository.find(tenantId)).thenReturn(Optional.of(new AiConnectionSettingsRepository.AiConnectionSettings(
                tenantId, true, "http://host.docker.internal:11434", "llama3.2",
                false, "", "", ""
        )));
        var ollama = service.ollama(tenantId);
        assertThat(ollama.available()).isTrue();
        assertThat(ollama.baseUrl()).isEqualTo("http://host.docker.internal:11434");
        assertThat(ollama.fromDb()).isTrue();
    }

    @Test
    void publicMapNeverEchoesFullApiKey() {
        when(repository.find(tenantId)).thenReturn(Optional.of(new AiConnectionSettingsRepository.AiConnectionSettings(
                tenantId, false, "http://host.docker.internal:11434", "llama3.2",
                true, "https://api.example.com/v1", "sk-live-super-secret-abcd", "gpt-test"
        )));
        Map<String, Object> pub = service.toPublicMap(tenantId);
        assertThat(pub.get("apiKeyConfigured")).isEqualTo(true);
        assertThat(pub.get("apiKeyHint")).isEqualTo("••••abcd");
        assertThat(pub.toString()).doesNotContain("sk-live-super-secret");
        assertThat(pub.get("externalAvailable")).isEqualTo(true);
    }

    @Test
    void blankApiKeyKeepsExistingOnUpsert() {
        when(repository.find(tenantId)).thenReturn(Optional.of(new AiConnectionSettingsRepository.AiConnectionSettings(
                tenantId, true, "http://ollama:11434", "m",
                true, "https://x", "keep-me-key-1234", "m"
        )));
        org.mockito.ArgumentCaptor<AiConnectionSettingsRepository.AiConnectionSettings> cap =
                org.mockito.ArgumentCaptor.forClass(AiConnectionSettingsRepository.AiConnectionSettings.class);
        service.upsert(tenantId, true, "http://ollama:11434", "m", true, "https://x", "m", null, false);
        verify(repository).upsert(cap.capture());
        assertThat(cap.getValue().externalApiKey()).isEqualTo("keep-me-key-1234");
    }

    @Test
    void clearApiKeyWipesSecret() {
        when(repository.find(tenantId)).thenReturn(Optional.of(new AiConnectionSettingsRepository.AiConnectionSettings(
                tenantId, false, "http://o", "m", true, "https://x", "old-key", "m"
        )));
        org.mockito.ArgumentCaptor<AiConnectionSettingsRepository.AiConnectionSettings> cap =
                org.mockito.ArgumentCaptor.forClass(AiConnectionSettingsRepository.AiConnectionSettings.class);
        service.upsert(tenantId, false, "http://o", "m", true, "https://x", "m", null, true);
        verify(repository).upsert(cap.capture());
        assertThat(cap.getValue().externalApiKey()).isEmpty();
    }

    @Test
    void maskApiKeyHelpers() {
        assertThat(AiConnectionConfigService.maskApiKey("")).isEmpty();
        assertThat(AiConnectionConfigService.maskApiKey("ab")).isEqualTo("••••");
        assertThat(AiConnectionConfigService.maskApiKey("sk-abcdef")).isEqualTo("••••cdef");
    }

    @Test
    void withTenantScopesResolutionForWorkers() {
        when(repository.find(tenantId)).thenReturn(Optional.of(new AiConnectionSettingsRepository.AiConnectionSettings(
                tenantId, true, "http://scoped:11434", "scoped-model",
                false, "", "", ""
        )));
        var result = service.withTenant(tenantId, () -> service.ollama());
        assertThat(result.baseUrl()).isEqualTo("http://scoped:11434");
        assertThat(result.model()).isEqualTo("scoped-model");
        assertThat(result.available()).isTrue();
    }
}
