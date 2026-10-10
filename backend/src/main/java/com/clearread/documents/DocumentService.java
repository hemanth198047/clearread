package com.clearread.documents;

import com.clearread.capabilities.CapabilityService;
import com.clearread.config.ClearReadProperties;
import com.clearread.identity.UserRecord;
import com.clearread.jobs.JobRepository;
import com.clearread.storage.ObjectStorageService;
import com.clearread.tenancy.TenantContext;
import com.clearread.transform.TransformService;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private final DocumentRepository documents;
    private final ObjectStorageService storage;
    private final TransformService transformService;
    private final CapabilityService capabilities;
    private final JobRepository jobs;
    private final ClearReadProperties properties;
    private final ObjectMapper objectMapper;

    public DocumentService(
            DocumentRepository documents,
            ObjectStorageService storage,
            TransformService transformService,
            CapabilityService capabilities,
            JobRepository jobs,
            ClearReadProperties properties,
            ObjectMapper objectMapper
    ) {
        this.documents = documents;
        this.storage = storage;
        this.transformService = transformService;
        this.capabilities = capabilities;
        this.jobs = jobs;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DocumentRecord ingestUpload(UserRecord user, String title, String filename, String contentType, byte[] bytes) {
        return ingest(user, title, filename, contentType, bytes, "upload");
    }

    @Transactional
    public DocumentRecord ingestPaste(UserRecord user, String title, String formatId, String text) {
        String filename = (title == null || title.isBlank() ? "paste" : title)
                + ("markdown".equals(formatId) ? ".md" : ".txt");
        String contentType = "markdown".equals(formatId) ? "text/markdown" : "text/plain";
        return ingest(user, title, filename, contentType, text.getBytes(StandardCharsets.UTF_8), "paste");
    }

    private DocumentRecord ingest(
            UserRecord user,
            String title,
            String filename,
            String contentType,
            byte[] bytes,
            String sourceKind
    ) {
        requireAuthor();
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty content");
        }
        if (bytes.length > properties.getLimits().getMaxUploadBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds max size");
        }

        String formatId = transformService.detectFormat(filename, contentType);
        if (formatId == null || !capabilities.supportsIngest(formatId)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported format");
        }

        UUID tenantId = TenantContext.requireTenantId();
        UUID documentId = UUID.randomUUID();
        String sha = ObjectStorageService.sha256(bytes);
        String key = storage.originalKey(tenantId, documentId, sha);
        storage.putBytes(key, bytes, contentType != null ? contentType : "application/octet-stream");

        String resolvedTitle = (title != null && !title.isBlank()) ? title
                : (filename != null ? filename : "Untitled");

        DocumentRecord doc = new DocumentRecord(
                documentId, tenantId, resolvedTitle, formatId, "queued",
                key, sha, (long) bytes.length, filename, user.id(), null
        );
        documents.insert(doc);
        documents.insertSource(UUID.randomUUID(), tenantId, documentId, sourceKind, key, sha, bytes.length, contentType, filename);
        documents.insertAcl(tenantId, documentId, "user", user.id().toString(), "admin");

        Map<String, Object> payload = new HashMap<>();
        payload.put("documentId", documentId.toString());
        try {
            jobs.enqueue(
                    UUID.randomUUID(),
                    tenantId,
                    "transform.document",
                    objectMapper.writeValueAsString(payload),
                    "transform:" + documentId
            );
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to enqueue job");
        }
        return doc;
    }

    public DocumentRecord getForRead(UserRecord user, UUID documentId) {
        UUID tenantId = TenantContext.requireTenantId();
        DocumentRecord doc = documents.findByTenantAndId(tenantId, documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        if (!documents.canRead(tenantId, documentId, user.id(), TenantContext.roleOrNull())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
        return doc;
    }

    /** Cross-tenant probe used by isolation tests — always 404 when wrong tenant. */
    public void assertNotVisibleAcrossTenant(UUID documentId) {
        UUID tenantId = TenantContext.requireTenantId();
        documents.findByTenantAndId(tenantId, documentId)
                .ifPresentOrElse(
                        d -> {
                        },
                        () -> {
                            if (documents.findByIdAnyTenant(documentId).isPresent()) {
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
                            }
                            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
                        }
                );
    }

    public List<DocumentRecord> list(UserRecord user) {
        UUID tenantId = TenantContext.requireTenantId();
        return documents.listByTenant(tenantId, 50).stream()
                .filter(d -> documents.canRead(tenantId, d.id(), user.id(), TenantContext.roleOrNull()))
                .toList();
    }

    public byte[] downloadOriginal(UserRecord user, UUID documentId) {
        DocumentRecord doc = getForRead(user, documentId);
        if (doc.originalObjectKey() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Original not available");
        }
        return storage.getBytes(doc.originalObjectKey());
    }

    public ClearDoc getCleardoc(UserRecord user, UUID documentId) {
        DocumentRecord doc = getForRead(user, documentId);
        if (doc.currentRevisionId() == null || !"ready".equals(doc.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Document not ready");
        }
        String key = documents.findCleardocKey(doc.tenantId(), doc.id(), doc.currentRevisionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ClearDoc missing"));
        try {
            return objectMapper.readValue(storage.getBytes(key), ClearDoc.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read ClearDoc");
        }
    }

    private void requireAuthor() {
        String role = TenantContext.roleOrNull();
        if (!"author".equals(role) && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Author role required");
        }
    }
}
