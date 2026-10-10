package com.clearread.annotations;

import com.clearread.documents.DocumentRepository;
import com.clearread.identity.UserRecord;
import com.clearread.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AnnotationService {

    private final JdbcTemplate jdbc;
    private final DocumentRepository documents;

    public AnnotationService(JdbcTemplate jdbc, DocumentRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    @Transactional
    public Map<String, Object> create(
            UserRecord user, UUID documentId, String kind, String visibility, String body,
            String color, String blockNodeId, Integer sourceStart, Integer sourceEnd, UUID revisionId
    ) {
        UUID tenantId = TenantContext.requireTenantId();
        requireRead(user, documentId);
        if ("workspace".equals(visibility) && !"comment".equals(kind) && !"highlight".equals(kind)) {
            // notes/bookmarks default private; comments/workspace highlights allowed
        }
        if ("private".equals(visibility) || "note".equals(kind) || "bookmark".equals(kind)) {
            visibility = "private".equals(visibility) || "note".equals(kind) || "bookmark".equals(kind)
                    ? ("comment".equals(kind) ? visibility : "private")
                    : visibility;
        }
        if ("note".equals(kind) || "bookmark".equals(kind)) {
            visibility = "private";
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO annotations (
                  id, tenant_id, document_id, revision_id, author_user_id, kind, visibility,
                  body_text, color, block_node_id, source_start, source_end, anchor_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'anchored')
                """,
                id, tenantId, documentId, revisionId, user.id(), kind, visibility,
                body, color, blockNodeId, sourceStart, sourceEnd
        );
        return get(tenantId, id);
    }

    public List<Map<String, Object>> list(UserRecord user, UUID documentId) {
        UUID tenantId = TenantContext.requireTenantId();
        requireRead(user, documentId);
        return jdbc.query(
                """
                SELECT * FROM annotations
                WHERE tenant_id = ? AND document_id = ? AND deleted_at IS NULL
                  AND (visibility = 'workspace' OR author_user_id = ?)
                ORDER BY created_at
                """,
                (rs, i) -> mapRow(rs),
                tenantId, documentId, user.id()
        );
    }

    @Transactional
    public Map<String, Object> resolve(UserRecord user, UUID annotationId) {
        UUID tenantId = TenantContext.requireTenantId();
        Map<String, Object> existing = get(tenantId, annotationId);
        UUID docId = UUID.fromString((String) existing.get("documentId"));
        requireRead(user, docId);
        jdbc.update(
                """
                UPDATE annotations SET resolved_at = now(), updated_at = now()
                WHERE id = ? AND tenant_id = ? AND kind = 'comment'
                """,
                annotationId, tenantId
        );
        return get(tenantId, annotationId);
    }

    @Transactional
    public void markUncertainForDocument(UUID tenantId, UUID documentId) {
        jdbc.update(
                """
                UPDATE annotations
                SET anchor_status = 'uncertain', updated_at = now()
                WHERE tenant_id = ? AND document_id = ? AND deleted_at IS NULL AND anchor_status = 'anchored'
                """,
                tenantId, documentId
        );
    }

    private Map<String, Object> get(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM annotations WHERE tenant_id = ? AND id = ?", (rs, i) -> mapRow(rs), tenantId, id)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Annotation not found"));
    }

    private Map<String, Object> mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class).toString());
        m.put("documentId", rs.getObject("document_id", UUID.class).toString());
        m.put("revisionId", rs.getObject("revision_id") == null ? "" : rs.getObject("revision_id", UUID.class).toString());
        m.put("authorUserId", rs.getObject("author_user_id", UUID.class).toString());
        m.put("kind", rs.getString("kind"));
        m.put("visibility", rs.getString("visibility"));
        m.put("body", rs.getString("body_text") == null ? "" : rs.getString("body_text"));
        m.put("color", rs.getString("color") == null ? "" : rs.getString("color"));
        m.put("blockNodeId", rs.getString("block_node_id") == null ? "" : rs.getString("block_node_id"));
        m.put("sourceStart", rs.getObject("source_start"));
        m.put("sourceEnd", rs.getObject("source_end"));
        m.put("anchorStatus", rs.getString("anchor_status"));
        m.put("resolvedAt", rs.getObject("resolved_at") == null ? "" : rs.getObject("resolved_at").toString());
        m.put("createdAt", rs.getObject("created_at").toString());
        return m;
    }

    private void requireRead(UserRecord user, UUID documentId) {
        UUID tenantId = TenantContext.requireTenantId();
        if (!documents.canRead(tenantId, documentId, user.id(), TenantContext.roleOrNull())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
    }
}
