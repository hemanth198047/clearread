package com.clearread.library;

import com.clearread.documents.DocumentRepository;
import com.clearread.identity.UserRecord;
import com.clearread.storage.ObjectStorageService;
import com.clearread.tenancy.TenantContext;
import com.clearread.tenancy.WorkspaceSettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LibraryService {

    private final JdbcTemplate jdbc;
    private final DocumentRepository documents;
    private final WorkspaceSettingsRepository settings;
    private final ObjectStorageService storage;

    public LibraryService(
            JdbcTemplate jdbc,
            DocumentRepository documents,
            WorkspaceSettingsRepository settings,
            ObjectStorageService storage
    ) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.settings = settings;
        this.storage = storage;
    }

    public List<Map<String, Object>> listLibrary(
            UserRecord user,
            String status,
            Boolean favorite,
            UUID collectionId,
            String tag,
            String sort,
            boolean includeDeleted
    ) {
        UUID tenantId = TenantContext.requireTenantId();
        String order = switch (sort == null ? "updated" : sort) {
            case "title" -> "d.title ASC";
            case "created" -> "d.created_at DESC";
            case "opened" -> "d.last_opened_at DESC NULLS LAST";
            default -> "d.updated_at DESC";
        };
        StringBuilder sql = new StringBuilder(
                """
                SELECT d.id, d.title, d.description, d.format_id, d.status, d.favorite, d.collection_id,
                       d.original_object_key, d.original_sha256, d.original_byte_length, d.original_filename,
                       d.current_revision_id, d.created_at, d.updated_at, d.last_opened_at, d.deleted_at
                FROM documents d
                WHERE d.tenant_id = ?
                """
        );
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (includeDeleted) {
            sql.append(" AND d.deleted_at IS NOT NULL");
        } else {
            sql.append(" AND d.deleted_at IS NULL");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND d.status = ?");
            args.add(status);
        }
        if (favorite != null) {
            sql.append(" AND d.favorite = ?");
            args.add(favorite);
        }
        if (collectionId != null) {
            sql.append(" AND d.collection_id = ?");
            args.add(collectionId);
        }
        if (tag != null && !tag.isBlank()) {
            sql.append(
                    """
                     AND EXISTS (
                       SELECT 1 FROM document_tags dt
                       JOIN tags t ON t.id = dt.tag_id
                       WHERE dt.document_id = d.id AND t.tenant_id = d.tenant_id AND lower(t.name) = lower(?)
                     )
                    """
            );
            args.add(tag);
        }
        sql.append(" ORDER BY ").append(order).append(" LIMIT 100");

        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            UUID id = rs.getObject("id", UUID.class);
            m.put("id", id.toString());
            m.put("title", rs.getString("title"));
            m.put("description", rs.getString("description") == null ? "" : rs.getString("description"));
            m.put("formatId", rs.getString("format_id"));
            m.put("status", rs.getString("status"));
            m.put("favorite", rs.getBoolean("favorite"));
            m.put("collectionId", rs.getObject("collection_id") == null ? "" : rs.getObject("collection_id", UUID.class).toString());
            String originalKey = rs.getString("original_object_key");
            m.put("_originalObjectKey", originalKey == null ? "" : originalKey);
            m.put("originalSha256", rs.getString("original_sha256") == null ? "" : rs.getString("original_sha256"));
            m.put("originalByteLength", rs.getObject("original_byte_length") == null ? 0 : rs.getLong("original_byte_length"));
            m.put("originalFilename", rs.getString("original_filename") == null ? "" : rs.getString("original_filename"));
            m.put("currentRevisionId", rs.getObject("current_revision_id") == null ? "" : rs.getObject("current_revision_id", UUID.class).toString());
            m.put("createdAt", rs.getObject("created_at").toString());
            m.put("updatedAt", rs.getObject("updated_at").toString());
            m.put("lastOpenedAt", rs.getObject("last_opened_at") == null ? "" : rs.getObject("last_opened_at").toString());
            m.put("deleted", rs.getObject("deleted_at") != null);
            return m;
        }, args.toArray());

        return rows.stream()
                .filter(m -> documents.canRead(tenantId, UUID.fromString((String) m.get("id")), user.id(), TenantContext.roleOrNull()))
                .filter(m -> {
                    String originalKey = (String) m.remove("_originalObjectKey");
                    String docStatus = (String) m.get("status");
                    boolean alreadyDeleted = Boolean.TRUE.equals(m.get("deleted"));
                    if (!alreadyDeleted && shouldPurgeMissingOriginal(originalKey, docStatus)) {
                        // Silent cleanup: drop orphaned library rows so the UI never shows re-upload recovery.
                        documents.softDelete(tenantId, UUID.fromString((String) m.get("id")));
                        return false;
                    }
                    m.put("originalMissing", false);
                    return true;
                })
                .toList();
    }

    /**
     * Purge when a stored original key is gone. Blank keys are only purged for terminal statuses
     * (not mid-flight URL ingest).
     */
    private boolean shouldPurgeMissingOriginal(String originalKey, String status) {
        if (originalKey != null && !originalKey.isBlank()) {
            return isOriginalObjectGone(originalKey);
        }
        String s = status == null ? "" : status;
        return "ready".equals(s) || "failed".equals(s) || "cancelled".equals(s);
    }

    private boolean isOriginalObjectGone(String originalKey) {
        try {
            return !storage.exists(originalKey);
        } catch (RuntimeException e) {
            // Do not fail the whole library list if object storage is briefly unreachable.
            return false;
        }
    }

    @Transactional
    public Map<String, Object> updateMeta(UserRecord user, UUID documentId, String title, String description,
                                          Boolean favorite, UUID collectionId) {
        requireWrite(user, documentId);
        UUID tenantId = TenantContext.requireTenantId();
        if (title != null) {
            jdbc.update("UPDATE documents SET title = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                    title, tenantId, documentId);
        }
        if (description != null) {
            jdbc.update("UPDATE documents SET description = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                    description, tenantId, documentId);
        }
        if (favorite != null) {
            jdbc.update("UPDATE documents SET favorite = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                    favorite, tenantId, documentId);
        }
        if (collectionId != null || (collectionId == null && false)) {
            // allow explicit null via sentinel handled by controller
        }
        return getOne(user, documentId);
    }

    public void setCollection(UserRecord user, UUID documentId, UUID collectionId) {
        requireWrite(user, documentId);
        UUID tenantId = TenantContext.requireTenantId();
        jdbc.update("UPDATE documents SET collection_id = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                collectionId, tenantId, documentId);
    }

    public void markOpened(UserRecord user, UUID documentId) {
        UUID tenantId = TenantContext.requireTenantId();
        if (!documents.canRead(tenantId, documentId, user.id(), TenantContext.roleOrNull())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
        jdbc.update("UPDATE documents SET last_opened_at = now() WHERE tenant_id = ? AND id = ?", tenantId, documentId);
    }

    @Transactional
    public Map<String, Object> createCollection(UserRecord user, String name, UUID parentId) {
        requireAuthor();
        UUID tenantId = TenantContext.requireTenantId();
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO collections (id, tenant_id, parent_id, name, created_by)
                VALUES (?, ?, ?, ?, ?)
                """,
                id, tenantId, parentId, name, user.id()
        );
        return Map.of("id", id.toString(), "name", name, "parentId", parentId == null ? "" : parentId.toString());
    }

    public List<Map<String, Object>> listCollections(UserRecord user) {
        UUID tenantId = TenantContext.requireTenantId();
        return jdbc.query(
                """
                SELECT id, name, parent_id FROM collections
                WHERE tenant_id = ? AND deleted_at IS NULL
                ORDER BY name
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("name", rs.getString("name"));
                    m.put("parentId", rs.getObject("parent_id") == null ? "" : rs.getObject("parent_id", UUID.class).toString());
                    return m;
                },
                tenantId
        );
    }

    @Transactional
    public void addTag(UserRecord user, UUID documentId, String tagName) {
        requireWrite(user, documentId);
        UUID tenantId = TenantContext.requireTenantId();
        UUID tagId = jdbc.query(
                "SELECT id FROM tags WHERE tenant_id = ? AND lower(name) = lower(?)",
                (rs, i) -> rs.getObject("id", UUID.class),
                tenantId, tagName
        ).stream().findFirst().orElseGet(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO tags (id, tenant_id, name) VALUES (?, ?, ?)", id, tenantId, tagName.trim());
            return id;
        });
        jdbc.update(
                """
                INSERT INTO document_tags (tenant_id, document_id, tag_id) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                tenantId, documentId, tagId
        );
    }

    public List<String> listTags(UserRecord user, UUID documentId) {
        UUID tenantId = TenantContext.requireTenantId();
        if (!documents.canRead(tenantId, documentId, user.id(), TenantContext.roleOrNull())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
        return jdbc.query(
                """
                SELECT t.name FROM tags t
                JOIN document_tags dt ON dt.tag_id = t.id
                WHERE dt.document_id = ? AND dt.tenant_id = ?
                ORDER BY t.name
                """,
                (rs, i) -> rs.getString(1),
                documentId, tenantId
        );
    }

    public Map<String, Object> getOne(UserRecord user, UUID documentId) {
        return listLibrary(user, null, null, null, null, "updated", false).stream()
                .filter(m -> documentId.toString().equals(m.get("id")))
                .findFirst()
                .orElseGet(() -> listLibrary(user, null, null, null, null, "updated", true).stream()
                        .filter(m -> documentId.toString().equals(m.get("id")))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found")));
    }

    public void assertWithinQuota(UUID tenantId) {
        var s = settings.getOrDefaults(tenantId);
        if (s.maxDocuments() != null) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM documents WHERE tenant_id = ? AND deleted_at IS NULL",
                    Integer.class, tenantId
            );
            if (count != null && count >= s.maxDocuments()) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Document quota exceeded");
            }
        }
    }

    private void requireWrite(UserRecord user, UUID documentId) {
        requireAuthor();
        UUID tenantId = TenantContext.requireTenantId();
        if (!documents.canRead(tenantId, documentId, user.id(), TenantContext.roleOrNull())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
    }

    private void requireAuthor() {
        String role = TenantContext.roleOrNull();
        if (!"author".equals(role) && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Author role required");
        }
    }
}
