package com.clearread.ai;

import com.clearread.tenancy.WorkspaceSettingsRepository.WorkspaceSettings;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentAvailabilityMessageTest {

    private static final UUID TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void externalKeyConfigured_messageConnected() {
        WorkspaceSettings ws = new WorkspaceSettings(
                TENANT, true, true, null, 1_000_000L, null, true,
                true, false, true, "external", 50, 2
        );
        AiProvider provider = stub("external", false, true);
        String msg = EnrichmentService.availabilityMessage(ws, provider, true, false);
        assertThat(msg).contains("connected");
    }

    @Test
    void localOnlyBlocksExternal_actionableMessage() {
        WorkspaceSettings ws = new WorkspaceSettings(
                TENANT, true, true, null, 1_000_000L, null, true,
                true, true, false, "external", 50, 2
        );
        AiProvider provider = stub("external", false, true);
        String msg = EnrichmentService.availabilityMessage(ws, provider, true, true);
        assertThat(msg).containsIgnoringCase("blocked by workspace policy");
        assertThat(msg).containsIgnoringCase("Local-only");
        assertThat(msg).containsIgnoringCase("auto-align");
    }

    @Test
    void externalMissingKey_configureMessage() {
        WorkspaceSettings ws = new WorkspaceSettings(
                TENANT, true, true, null, 1_000_000L, null, true,
                true, false, true, "external", 50, 2
        );
        AiProvider provider = stub("external", false, false);
        String msg = EnrichmentService.availabilityMessage(ws, provider, false, false);
        assertThat(msg).containsIgnoringCase("API key");
    }

    private static AiProvider stub(String id, boolean local, boolean available) {
        return new AiProvider() {
            @Override public String providerId() { return id; }
            @Override public boolean isLocal() { return local; }
            @Override public boolean isAvailable() { return available; }
            @Override public String modelId() { return "m"; }
            @Override public EnrichmentResponse enrich(EnrichmentRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
