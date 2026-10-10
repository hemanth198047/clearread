package com.clearread.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkspaceSettingsRepository {

    public record WorkspaceSettings(
            UUID tenantId,
            boolean sharingEnabled,
            boolean anonymousPreviewEnabled,
            Integer retentionDays,
            long maxUploadBytes,
            Integer maxDocuments,
            boolean defaultExportDownload,
            boolean aiEnabled,
            boolean aiLocalOnly,
            boolean aiAllowExternal,
            String aiProviderId,
            int aiDailyQuota,
            int aiMaxRetries
    ) {
    }

    private static final RowMapper<WorkspaceSettings> MAPPER = (rs, i) -> new WorkspaceSettings(
            rs.getObject("tenant_id", UUID.class),
            rs.getBoolean("sharing_enabled"),
            rs.getBoolean("anonymous_preview_enabled"),
            rs.getObject("retention_days") == null ? null : rs.getInt("retention_days"),
            rs.getLong("max_upload_bytes"),
            rs.getObject("max_documents") == null ? null : rs.getInt("max_documents"),
            rs.getBoolean("default_export_download"),
            columnOrDefault(rs, "ai_enabled", false),
            columnOrDefault(rs, "ai_local_only", true),
            columnOrDefault(rs, "ai_allow_external", false),
            columnOrDefault(rs, "ai_provider_id", "mock-local"),
            columnOrDefaultInt(rs, "ai_daily_quota", 50),
            columnOrDefaultInt(rs, "ai_max_retries", 2)
    );

    private static boolean columnOrDefault(java.sql.ResultSet rs, String col, boolean def) throws java.sql.SQLException {
        try {
            boolean v = rs.getBoolean(col);
            return rs.wasNull() ? def : v;
        } catch (java.sql.SQLException e) {
            return def;
        }
    }

    private static String columnOrDefault(java.sql.ResultSet rs, String col, String def) throws java.sql.SQLException {
        try {
            String v = rs.getString(col);
            return v == null || v.isBlank() ? def : v;
        } catch (java.sql.SQLException e) {
            return def;
        }
    }

    private static int columnOrDefaultInt(java.sql.ResultSet rs, String col, int def) throws java.sql.SQLException {
        try {
            int v = rs.getInt(col);
            return rs.wasNull() ? def : v;
        } catch (java.sql.SQLException e) {
            return def;
        }
    }

    private final JdbcTemplate jdbc;

    public WorkspaceSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WorkspaceSettings getOrDefaults(UUID tenantId) {
        return find(tenantId).orElseGet(() -> {
            jdbc.update(
                    """
                    INSERT INTO workspace_settings (tenant_id) VALUES (?)
                    ON CONFLICT DO NOTHING
                    """,
                    tenantId
            );
            return find(tenantId).orElse(defaults(tenantId));
        });
    }

    public static WorkspaceSettings defaults(UUID tenantId) {
        return new WorkspaceSettings(tenantId, true, true, null, 10_485_760L, null, true,
                false, true, false, "mock-local", 50, 2);
    }

    public Optional<WorkspaceSettings> find(UUID tenantId) {
        return jdbc.query("SELECT * FROM workspace_settings WHERE tenant_id = ?", MAPPER, tenantId).stream().findFirst();
    }

    public void upsert(WorkspaceSettings s) {
        jdbc.update(
                """
                INSERT INTO workspace_settings (
                  tenant_id, sharing_enabled, anonymous_preview_enabled, retention_days,
                  max_upload_bytes, max_documents, default_export_download,
                  ai_enabled, ai_local_only, ai_allow_external, ai_provider_id,
                  ai_daily_quota, ai_max_retries, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (tenant_id) DO UPDATE SET
                  sharing_enabled = EXCLUDED.sharing_enabled,
                  anonymous_preview_enabled = EXCLUDED.anonymous_preview_enabled,
                  retention_days = EXCLUDED.retention_days,
                  max_upload_bytes = EXCLUDED.max_upload_bytes,
                  max_documents = EXCLUDED.max_documents,
                  default_export_download = EXCLUDED.default_export_download,
                  ai_enabled = EXCLUDED.ai_enabled,
                  ai_local_only = EXCLUDED.ai_local_only,
                  ai_allow_external = EXCLUDED.ai_allow_external,
                  ai_provider_id = EXCLUDED.ai_provider_id,
                  ai_daily_quota = EXCLUDED.ai_daily_quota,
                  ai_max_retries = EXCLUDED.ai_max_retries,
                  updated_at = now()
                """,
                s.tenantId(), s.sharingEnabled(), s.anonymousPreviewEnabled(), s.retentionDays(),
                s.maxUploadBytes(), s.maxDocuments(), s.defaultExportDownload(),
                s.aiEnabled(), s.aiLocalOnly(), s.aiAllowExternal(), s.aiProviderId(),
                s.aiDailyQuota(), s.aiMaxRetries()
        );
    }
}
