package com.clearread.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
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
        insertSource(id, tenantId, documentId, kind, objectKey, sha256, byteLength, contentType, filename, null, "{}");
    }

    public void insertSource(UUID id, UUID tenantId, UUID documentId, String kind, String objectKey,
                             String sha256, Long byteLength, String contentType, String filename,
                             String sourceUrl, String fetchMetaJson) {
        jdbc.update(
                """
                INSERT INTO document_sources (
                  id, tenant_id, document_id, source_kind, object_key, content_sha256,
                  byte_length, content_type, original_filename, source_url, fetch_meta_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                id, tenantId, documentId, kind, objectKey, sha256, byteLength, contentType, filename,
                sourceUrl, fetchMetaJson == null ? "{}" : fetchMetaJson
        );
    }

    public void updateSourceFetched(
            UUID tenantId, UUID documentId, String objectKey, String sha256, long byteLength,
            String contentType, String filename, String fetchMetaJson
    ) {
        jdbc.update(
                """
                UPDATE document_sources
                SET object_key = ?, content_sha256 = ?, byte_length = ?, content_type = ?,
                    original_filename = ?, fetch_meta_json = ?::jsonb
                WHERE tenant_id = ? AND document_id = ?
                """,
                objectKey, sha256, byteLength, contentType, filename, fetchMetaJson, tenantId, documentId
        );
    }

    public void attachOriginal(UUID tenantId, UUID documentId, String objectKey, String sha256,
                               long byteLength, String filename, String formatId) {
        jdbc.update(
                """
                UPDATE documents
                SET original_object_key = ?, original_sha256 = ?, original_byte_length = ?,
                    original_filename = ?, format_id = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """,
                objectKey, sha256, byteLength, filename, formatId, tenantId, documentId
        );
    }

    public int nextRevisionNumber(UUID tenantId, UUID documentId) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(revision_number), 0) + 1
                FROM document_revisions
                WHERE tenant_id = ? AND document_id = ?
                """,
                Integer.class,
                tenantId, documentId
        );
        return n == null ? 1 : n;
    }

    public List<Map<String, Object>> listRevisions(UUID tenantId, UUID documentId) {
        return jdbc.query(
                """
                SELECT id, revision_number, adapter_id, adapter_version, registry_version,
                       cleardoc_sha256, warnings_json::text, created_at
                FROM document_revisions
                WHERE tenant_id = ? AND document_id = ?
                ORDER BY revision_number DESC
                """,
                (rs, rowNum) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("revisionNumber", rs.getInt("revision_number"));
                    m.put("adapterId", rs.getString("adapter_id"));
                    m.put("adapterVersion", rs.getString("adapter_version"));
                    m.put("registryVersion", rs.getString("registry_version"));
                    m.put("cleardocSha256", rs.getString("cleardoc_sha256"));
                    m.put("warningsJson", rs.getString("warnings_json"));
                    m.put("createdAt", rs.getObject("created_at").toString());
                    return m;
                },
                tenantId, documentId
        );
    }

    public Optional<String> findSourceUrl(UUID tenantId, UUID documentId) {
        return jdbc.query(
                "SELECT source_url FROM document_sources WHERE tenant_id = ? AND document_id = ?",
                (rs, rowNum) -> rs.getString("source_url"),
                tenantId, documentId
        ).stream().findFirst();
    }

    public Optional<String> findFetchMeta(UUID tenantId, UUID documentId) {
        return jdbc.query(
                "SELECT fetch_meta_json::text FROM document_sources WHERE tenant_id = ? AND document_id = ?",
                (rs, rowNum) -> rs.getString(1),
                tenantId, documentId
        ).stream().findFirst();
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

    public void softDelete(UUID tenantId, UUID documentId) {
        jdbc.update(
                """
                UPDATE documents
                SET deleted_at = now(), status = 'deleted', updated_at = now()
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                """,
                tenantId, documentId
        );
    }

    public void restore(UUID tenantId, UUID documentId) {
        jdbc.update(
                """
                UPDATE documents
                SET deleted_at = NULL,
                    status = CASE WHEN current_revision_id IS NULL THEN 'queued' ELSE 'ready' END,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NOT NULL
                """,
                tenantId, documentId
        );
    }

    /** Policy hook for permanent deletion (admin); originals remain until GC policy. */
    public void permanentDeleteMarker(UUID tenantId, UUID documentId) {
        jdbc.update(
                """
                UPDATE documents
                SET deleted_at = now(), status = 'deleted', title = '[purged]', updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """,
                tenantId, documentId
        );
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
