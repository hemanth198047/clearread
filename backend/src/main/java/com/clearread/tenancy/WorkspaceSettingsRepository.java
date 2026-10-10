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
            boolean defaultExportDownload
    ) {
    }

    private static final RowMapper<WorkspaceSettings> MAPPER = (rs, i) -> new WorkspaceSettings(
            rs.getObject("tenant_id", UUID.class),
            rs.getBoolean("sharing_enabled"),
            rs.getBoolean("anonymous_preview_enabled"),
            rs.getObject("retention_days") == null ? null : rs.getInt("retention_days"),
            rs.getLong("max_upload_bytes"),
            rs.getObject("max_documents") == null ? null : rs.getInt("max_documents"),
            rs.getBoolean("default_export_download")
    );

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
            return find(tenantId).orElse(new WorkspaceSettings(tenantId, true, true, null, 10_485_760L, null, true));
        });
    }

    public Optional<WorkspaceSettings> find(UUID tenantId) {
        return jdbc.query("SELECT * FROM workspace_settings WHERE tenant_id = ?", MAPPER, tenantId).stream().findFirst();
    }

    public void upsert(WorkspaceSettings s) {
        jdbc.update(
                """
                INSERT INTO workspace_settings (
                  tenant_id, sharing_enabled, anonymous_preview_enabled, retention_days,
                  max_upload_bytes, max_documents, default_export_download, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (tenant_id) DO UPDATE SET
                  sharing_enabled = EXCLUDED.sharing_enabled,
                  anonymous_preview_enabled = EXCLUDED.anonymous_preview_enabled,
                  retention_days = EXCLUDED.retention_days,
                  max_upload_bytes = EXCLUDED.max_upload_bytes,
                  max_documents = EXCLUDED.max_documents,
                  default_export_download = EXCLUDED.default_export_download,
                  updated_at = now()
                """,
                s.tenantId(), s.sharingEnabled(), s.anonymousPreviewEnabled(), s.retentionDays(),
                s.maxUploadBytes(), s.maxDocuments(), s.defaultExportDownload()
        );
    }
}
