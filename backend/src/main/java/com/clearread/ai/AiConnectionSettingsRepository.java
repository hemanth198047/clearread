package com.clearread.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class AiConnectionSettingsRepository {

    public record AiConnectionSettings(
            UUID tenantId,
            boolean ollamaEnabled,
            String ollamaBaseUrl,
            String ollamaModel,
            boolean externalEnabled,
            String externalBaseUrl,
            String externalApiKey,
            String externalModel
    ) {
    }

    private static final RowMapper<AiConnectionSettings> MAPPER = (rs, i) -> new AiConnectionSettings(
            rs.getObject("tenant_id", UUID.class),
            rs.getBoolean("ollama_enabled"),
            nullToEmpty(rs.getString("ollama_base_url")),
            nullToEmpty(rs.getString("ollama_model")),
            rs.getBoolean("external_enabled"),
            nullToEmpty(rs.getString("external_base_url")),
            nullToEmpty(rs.getString("external_api_key")),
            nullToEmpty(rs.getString("external_model"))
    );

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private final JdbcTemplate jdbc;

    public AiConnectionSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AiConnectionSettings> find(UUID tenantId) {
        return jdbc.query("SELECT * FROM ai_connection_settings WHERE tenant_id = ?", MAPPER, tenantId)
                .stream().findFirst();
    }

    public void upsert(AiConnectionSettings s) {
        jdbc.update(
                """
                INSERT INTO ai_connection_settings (
                  tenant_id, ollama_enabled, ollama_base_url, ollama_model,
                  external_enabled, external_base_url, external_api_key, external_model, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (tenant_id) DO UPDATE SET
                  ollama_enabled = EXCLUDED.ollama_enabled,
                  ollama_base_url = EXCLUDED.ollama_base_url,
                  ollama_model = EXCLUDED.ollama_model,
                  external_enabled = EXCLUDED.external_enabled,
                  external_base_url = EXCLUDED.external_base_url,
                  external_api_key = EXCLUDED.external_api_key,
                  external_model = EXCLUDED.external_model,
                  updated_at = now()
                """,
                s.tenantId(),
                s.ollamaEnabled(),
                s.ollamaBaseUrl(),
                s.ollamaModel(),
                s.externalEnabled(),
                s.externalBaseUrl(),
                s.externalApiKey(),
                s.externalModel()
        );
    }
}
