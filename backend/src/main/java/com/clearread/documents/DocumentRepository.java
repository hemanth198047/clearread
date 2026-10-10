package com.clearread.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentRepository {

    private static final RowMapper<DocumentRecord> MAPPER = (rs, rowNum) -> new DocumentRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("title"),
            rs.getString("format_id"),
            rs.getString("status"),
            rs.getString("original_object_key"),
            rs.getString("original_sha256"),
            rs.getObject("original_byte_length") == null ? null : rs.getLong("original_byte_length"),
            rs.getString("original_filename"),
            rs.getObject("created_by", UUID.class),
            rs.getObject("current_revision_id", UUID.class)
    );

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(DocumentRecord doc) {
        jdbc.update(
                """
                INSERT INTO documents (
                  id, tenant_id, title, format_id, status, original_object_key, original_sha256,
                  original_byte_length, original_filename, created_by, current_revision_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                doc.id(), doc.tenantId(), doc.title(), doc.formatId(), doc.status(),
                doc.originalObjectKey(), doc.originalSha256(), doc.originalByteLength(),
                doc.originalFilename(), doc.createdBy(), doc.currentRevisionId()
        );
    }

    public void insertSource(UUID id, UUID tenantId, UUID documentId, String kind, String objectKey,
                             String sha256, long byteLength, String contentType, String filename) {
        jdbc.update(
                """
                INSERT INTO document_sources (
                  id, tenant_id, document_id, source_kind, object_key, content_sha256,
                  byte_length, content_type, original_filename
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, documentId, kind, objectKey, sha256, byteLength, contentType, filename
        );
    }

    public void updateStatus(UUID tenantId, UUID documentId, String status) {
        jdbc.update(
                "UPDATE documents SET status = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                status, tenantId, documentId
        );
    }

    public void markReady(UUID tenantId, UUID documentId, UUID revisionId) {
        jdbc.update(
                """
                UPDATE documents
                SET status = 'ready', current_revision_id = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """,
                revisionId, tenantId, documentId
        );
    }

    public void markFailed(UUID tenantId, UUID documentId) {
        updateStatus(tenantId, documentId, "failed");
    }

    public void insertRevision(
            UUID id, UUID tenantId, UUID documentId, int revisionNumber,
            String cleardocKey, String cleardocSha, String schemaVersion,
            String adapterId, String adapterVersion, String registryVersion, String warningsJson
    ) {
        jdbc.update(
                """
                INSERT INTO document_revisions (
                  id, tenant_id, document_id, revision_number, cleardoc_object_key, cleardoc_sha256,
                  schema_version, adapter_id, adapter_version, registry_version, warnings_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                id, tenantId, documentId, revisionNumber, cleardocKey, cleardocSha,
                schemaVersion, adapterId, adapterVersion, registryVersion, warningsJson
        );
    }

    public Optional<DocumentRecord> findByTenantAndId(UUID tenantId, UUID documentId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, title, format_id, status, original_object_key, original_sha256,
                       original_byte_length, original_filename, created_by, current_revision_id
                FROM documents
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                """,
                MAPPER,
                tenantId, documentId
        ).stream().findFirst();
    }

    public Optional<DocumentRecord> findByIdAnyTenant(UUID documentId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, title, format_id, status, original_object_key, original_sha256,
                       original_byte_length, original_filename, created_by, current_revision_id
                FROM documents
                WHERE id = ? AND deleted_at IS NULL
                """,
                MAPPER,
                documentId
        ).stream().findFirst();
    }

    public List<DocumentRecord> listByTenant(UUID tenantId, int limit) {
        return jdbc.query(
                """
                SELECT id, tenant_id, title, format_id, status, original_object_key, original_sha256,
                       original_byte_length, original_filename, created_by, current_revision_id
                FROM documents
                WHERE tenant_id = ? AND deleted_at IS NULL
                ORDER BY updated_at DESC
                LIMIT ?
                """,
                MAPPER,
                tenantId, limit
        );
    }

    public Optional<String> findCleardocKey(UUID tenantId, UUID documentId, UUID revisionId) {
        return jdbc.query(
                """
                SELECT cleardoc_object_key FROM document_revisions
                WHERE tenant_id = ? AND document_id = ? AND id = ?
                """,
                (rs, rowNum) -> rs.getString("cleardoc_object_key"),
                tenantId, documentId, revisionId
        ).stream().findFirst();
    }

    public void insertAcl(UUID tenantId, UUID documentId, String principalType, String principalId, String permission) {
        jdbc.update(
                """
                INSERT INTO document_acls (id, tenant_id, document_id, principal_type, principal_id, permission)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(), tenantId, documentId, principalType, principalId, permission
        );
    }

    public boolean canRead(UUID tenantId, UUID documentId, UUID userId, String role) {
        DocumentRecord doc = findByTenantAndId(tenantId, documentId).orElse(null);
        if (doc == null) {
            return false;
        }
        if (doc.createdBy().equals(userId) || "admin".equals(role)) {
            return true;
        }
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM document_acls
                WHERE tenant_id = ? AND document_id = ?
                  AND (
                    (principal_type = 'user' AND principal_id = ?)
                    OR (principal_type = 'role' AND principal_id = ?)
                  )
                  AND permission IN ('read', 'write', 'share', 'admin')
                """,
                Integer.class,
                tenantId, documentId, userId.toString(), role
        );
        return count != null && count > 0;
    }
}
