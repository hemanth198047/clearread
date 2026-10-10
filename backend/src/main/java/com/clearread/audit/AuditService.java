package com.clearread.audit;

import com.clearread.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public AuditService(JdbcTemplate jdbc, tools.jackson.databind.ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(UUID actorUserId, String action, String resourceType, String resourceId, Map<String, ?> meta) {
        UUID tenantId = TenantContext.tenantIdOrNull();
        String metaJson = "{}";
        try {
            if (meta != null) {
                metaJson = objectMapper.writeValueAsString(meta);
            }
        } catch (Exception ignored) {
            metaJson = "{}";
        }
        jdbc.update(
                """
                INSERT INTO audit_events (id, tenant_id, actor_user_id, actor_type, action, resource_type, resource_id, meta_json)
                VALUES (?, ?, ?, 'user', ?, ?, ?, ?::jsonb)
                """,
                UUID.randomUUID(), tenantId, actorUserId, action, resourceType, resourceId, metaJson
        );
    }

    public void recordSystem(UUID tenantId, String action, String resourceType, String resourceId, Map<String, ?> meta) {
        String metaJson = "{}";
        try {
            if (meta != null) {
                metaJson = objectMapper.writeValueAsString(meta);
            }
        } catch (Exception ignored) {
            metaJson = "{}";
        }
        jdbc.update(
                """
                INSERT INTO audit_events (id, tenant_id, actor_user_id, actor_type, action, resource_type, resource_id, meta_json)
                VALUES (?, ?, NULL, 'system', ?, ?, ?, ?::jsonb)
                """,
                UUID.randomUUID(), tenantId, action, resourceType, resourceId, metaJson
        );
    }
}
