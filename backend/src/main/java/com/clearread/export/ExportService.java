package com.clearread.export;

import com.clearread.audit.AuditService;
import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentRepository;
import com.clearread.documents.DocumentService;
import com.clearread.identity.UserRecord;
import com.clearread.storage.ObjectStorageService;
import com.clearread.tenancy.TenantContext;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExportService {

    private final Map<String, ExportConverter> converters;
    private final DocumentService documentService;
    private final DocumentRepository documents;
    private final ObjectStorageService storage;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public ExportService(
            List<ExportConverter> converterList,
            DocumentService documentService,
            DocumentRepository documents,
            ObjectStorageService storage,
            JdbcTemplate jdbc,
            AuditService audit,
            ObjectMapper objectMapper
    ) {
        this.converters = converterList.stream()
                .collect(Collectors.toMap(ExportConverter::targetFormatId, Function.identity()));
        this.documentService = documentService;
        this.documents = documents;
        this.storage = storage;
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> availableFormats() {
        return converters.values().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetFormat", c.targetFormatId());
            m.put("converterId", c.converterId());
            m.put("converterVersion", c.converterVersion());
            m.put("artifactKind", "generated");
            m.put("note", "Generated from ClearDoc; not the immutable original");
            return m;
        }).toList();
    }

    public List<Map<String, Object>> listExports(UserRecord user, UUID documentId) {
        DocumentRecord doc = documentService.getForRead(user, documentId);
        return jdbc.query(
                """
                SELECT id, target_format, status, converter_id, converter_version, artifact_kind,
                       content_sha256, byte_length, created_at, last_error
                FROM document_exports
                WHERE tenant_id = ? AND document_id = ?
                ORDER BY created_at DESC
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("targetFormat", rs.getString("target_format"));
                    m.put("status", rs.getString("status"));
                    m.put("converterId", rs.getString("converter_id"));
                    m.put("converterVersion", rs.getString("converter_version"));
                    m.put("artifactKind", rs.getString("artifact_kind"));
                    m.put("contentSha256", rs.getString("content_sha256") == null ? "" : rs.getString("content_sha256"));
                    m.put("byteLength", rs.getObject("byte_length") == null ? 0 : rs.getLong("byte_length"));
                    m.put("createdAt", rs.getObject("created_at").toString());
                    m.put("lastError", rs.getString("last_error") == null ? "" : rs.getString("last_error"));
                    return m;
                },
                doc.tenantId(), documentId
        );
    }

    @Transactional
    public Map<String, Object> requestExport(UserRecord user, UUID documentId, String targetFormat) {
        DocumentRecord doc = documentService.getForRead(user, documentId);
        requireAuthor();
        ExportConverter converter = converters.get(targetFormat);
        if (converter == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported export format");
        }
        ClearDoc cleardoc = documentService.getCleardoc(user, documentId);
        UUID exportId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO document_exports (
                  id, tenant_id, document_id, revision_id, target_format, status,
                  converter_id, converter_version, artifact_kind, created_by
                ) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, 'generated', ?)
                """,
                exportId, doc.tenantId(), documentId, doc.currentRevisionId(), targetFormat,
                converter.converterId(), converter.converterVersion(), user.id()
        );
        try {
            ExportConverter.ExportResult result = converter.convert(cleardoc);
            String key = "tenants/" + doc.tenantId() + "/documents/" + documentId + "/exports/" + exportId + result.filenameSuffix();
            var stored = storage.putBytes(key, result.bytes(), result.contentType());
            jdbc.update(
                    """
                    UPDATE document_exports
                    SET status = 'ready', object_key = ?, content_sha256 = ?, byte_length = ?, updated_at = now()
                    WHERE id = ?
                    """,
                    stored.key(), stored.sha256(), result.bytes().length, exportId
            );
            audit.record(user.id(), "export.create", "document_export", exportId.toString(),
                    Map.of("documentId", documentId.toString(), "targetFormat", targetFormat, "artifactKind", "generated"));
        } catch (Exception e) {
            jdbc.update(
                    "UPDATE document_exports SET status = 'failed', last_error = ?, updated_at = now() WHERE id = ?",
                    e.getMessage(), exportId
            );
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Export failed: " + e.getMessage());
        }
        return listExports(user, documentId).stream()
                .filter(m -> exportId.toString().equals(m.get("id")))
                .findFirst()
                .orElseThrow();
    }

    public byte[] downloadExport(UserRecord user, UUID documentId, UUID exportId) {
        DocumentRecord doc = documentService.getForRead(user, documentId);
        var row = jdbc.query(
                """
                SELECT object_key, status FROM document_exports
                WHERE tenant_id = ? AND document_id = ? AND id = ?
                """,
                (rs, i) -> new String[]{
                        rs.getString("object_key") == null ? "" : rs.getString("object_key"),
                        rs.getString("status")
                },
                doc.tenantId(), documentId, exportId
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Export not found"));
        if (!"ready".equals(row[1]) || row[0].isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Export not ready");
        }
        audit.record(user.id(), "export.download", "document_export", exportId.toString(),
                Map.of("documentId", documentId.toString(), "artifactKind", "generated"));
        return storage.getBytes(row[0]);
    }

    private void requireAuthor() {
        String role = TenantContext.roleOrNull();
        if (!"author".equals(role) && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Author role required");
        }
    }
}
