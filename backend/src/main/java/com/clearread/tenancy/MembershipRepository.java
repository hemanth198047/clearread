package com.clearread.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MembershipRepository {

    private static final RowMapper<MembershipRecord> MAPPER = (rs, rowNum) -> new MembershipRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("role"),
            rs.getString("slug"),
            rs.getString("name")
    );

    private final JdbcTemplate jdbc;

    public MembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MembershipRecord> findByUserId(UUID userId) {
        return jdbc.query(
                """
                SELECT m.id, m.tenant_id, m.user_id, m.role, t.slug, t.name
                FROM memberships m
                JOIN tenants t ON t.id = m.tenant_id
                WHERE m.user_id = ? AND t.status = 'active'
                ORDER BY t.name
                """,
                MAPPER,
                userId
        );
    }

    public Optional<MembershipRecord> find(UUID tenantId, UUID userId) {
        return jdbc.query(
                """
                SELECT m.id, m.tenant_id, m.user_id, m.role, t.slug, t.name
                FROM memberships m
                JOIN tenants t ON t.id = m.tenant_id
                WHERE m.tenant_id = ? AND m.user_id = ?
                """,
                MAPPER,
                tenantId,
                userId
        ).stream().findFirst();
    }

    public void ensureMembership(UUID tenantId, UUID userId, String role) {
        if (find(tenantId, userId).isPresent()) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO memberships (id, tenant_id, user_id, role)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id) DO NOTHING
                """,
                UUID.randomUUID(), tenantId, userId, role
        );
    }
}
