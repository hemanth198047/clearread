package com.clearread.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InviteRepository {

    public record InviteRecord(
            UUID id,
            UUID tenantId,
            String email,
            String role,
            String token,
            String status,
            UUID invitedBy,
            Instant createdAt,
            Instant expiresAt,
            Instant acceptedAt,
            UUID acceptedUserId,
            String tenantSlug,
            String tenantName
    ) {
    }

    private static final RowMapper<InviteRecord> MAPPER = (rs, rowNum) -> new InviteRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("email"),
            rs.getString("role"),
            rs.getString("token"),
            rs.getString("status"),
            rs.getObject("invited_by", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("accepted_at") == null ? null : rs.getTimestamp("accepted_at").toInstant(),
            rs.getObject("accepted_user_id", UUID.class),
            rs.getString("tenant_slug"),
            rs.getString("tenant_name")
    );

    private final JdbcTemplate jdbc;

    public InviteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public InviteRecord create(UUID tenantId, String email, String role, UUID invitedBy, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                """
                INSERT INTO workspace_invites (id, tenant_id, email, role, token, status, invited_by, expires_at)
                VALUES (?, ?, ?, ?, ?, 'pending', ?, ?)
                """,
                id, tenantId, email.trim().toLowerCase(), role, token, invitedBy, Timestamp.from(expiresAt)
        );
        return findByToken(token).orElseThrow();
    }

    public Optional<InviteRecord> findByToken(String token) {
        return jdbc.query(
                """
                SELECT i.*, t.slug AS tenant_slug, t.name AS tenant_name
                FROM workspace_invites i
                JOIN tenants t ON t.id = i.tenant_id
                WHERE i.token = ?
                """,
                MAPPER,
                token
        ).stream().findFirst();
    }

    public List<InviteRecord> listByTenant(UUID tenantId) {
        return jdbc.query(
                """
                SELECT i.*, t.slug AS tenant_slug, t.name AS tenant_name
                FROM workspace_invites i
                JOIN tenants t ON t.id = i.tenant_id
                WHERE i.tenant_id = ?
                ORDER BY i.created_at DESC
                LIMIT 100
                """,
                MAPPER,
                tenantId
        );
    }

    public Optional<InviteRecord> findPendingByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(
                """
                SELECT i.*, t.slug AS tenant_slug, t.name AS tenant_name
                FROM workspace_invites i
                JOIN tenants t ON t.id = i.tenant_id
                WHERE LOWER(i.email) = LOWER(?) AND i.status = 'pending' AND i.expires_at > now()
                ORDER BY i.created_at DESC
                LIMIT 1
                """,
                MAPPER,
                email.trim()
        ).stream().findFirst();
    }

    public void markAccepted(UUID inviteId, UUID userId) {
        jdbc.update(
                """
                UPDATE workspace_invites
                SET status = 'accepted', accepted_at = now(), accepted_user_id = ?
                WHERE id = ? AND status = 'pending'
                """,
                userId, inviteId
        );
    }
}
