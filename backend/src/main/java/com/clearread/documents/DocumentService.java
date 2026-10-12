package com.clearread.documents;

import com.clearread.audit.AuditService;
import com.clearread.capabilities.CapabilityService;
import com.clearread.config.ClearReadProperties;
import com.clearread.identity.UserRecord;
import com.clearread.jobs.JobRepository;
import com.clearread.library.LibraryService;
import com.clearread.net.UrlAccessPolicy;
import com.clearread.net.UrlLogRedactor;
import com.clearread.net.SafeHttpFetcher;
import com.clearread.storage.ObjectStorageService;
import com.clearread.tenancy.TenantContext;
import com.clearread.tenancy.WorkspaceSettingsRepository;
import com.clearread.transform.TransformService;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final SafeHttpFetcher fetcher;
    private final WorkspaceSettingsRepository workspaceSettings;
    private final LibraryService libraryService;
    private final AuditService audit;

    public DocumentService(
            DocumentRepository documents,
            ObjectStorageService storage,
            TransformService transformService,
            CapabilityService capabilities,
            JobRepository jobs,
            ClearReadProperties properties,
            ObjectMapper objectMapper,
            SafeHttpFetcher fetcher,
            WorkspaceSettingsRepository workspaceSettings,
            LibraryService libraryService,
            AuditService audit
    ) {
        this.documents = documents;
        this.storage = storage;
        this.transformService = transformService;
        this.capabilities = capabilities;
        this.jobs = jobs;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.fetcher = fetcher;
        this.workspaceSettings = workspaceSettings;
        this.libraryService = libraryService;
        this.audit = audit;
    }

    public record IngestResult(DocumentRecord document, boolean duplicateOfExisting) {
    }

    @Transactional
    public IngestResult ingestUpload(
            UserRecord user, String title, String filename, String contentType, byte[] bytes, boolean forceNew
    ) {
        return ingestBytes(user, title, filename, contentType, bytes, "upload", null, forceNew);
    }

    @Transactional
    public IngestResult ingestPaste(UserRecord user, String title, String formatId, String text, boolean forceNew) {
        String filename = (title == null || title.isBlank() ? "paste" : title) + extensionFor(formatId);
        String contentType = contentTypeFor(formatId);
        return ingestBytes(
                user, title, filename, contentType, text.getBytes(StandardCharsets.UTF_8), "paste", formatId, forceNew
        );
    }

    /**
     * Enqueue SSRF-hardened URL fetch. Original bytes arrive asynchronously via {@code ingest.fetch} job.
     */
    @Transactional
    public Map<String, Object> ingestUrl(UserRecord user, String title, String url, String idempotencyKey) {
        requireAuthor();
        try {
            fetcher.policy().validate(url);
        } catch (UrlAccessPolicy.Denied denied) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, denied.getMessage());
        }

        UUID tenantId = TenantContext.requireTenantId();
        libraryService.assertWithinQuota(tenantId);
        UUID documentId = UUID.randomUUID();
        String resolvedTitle = (title != null && !title.isBlank()) ? title : filenameHintFromUrl(url);
        DocumentRecord doc = new DocumentRecord(
                documentId, tenantId, resolvedTitle, "unknown", "queued",
                null, null, null, null, user.id(), null
        );
        documents.insert(doc);
        documents.insertSource(
                UUID.randomUUID(), tenantId, documentId, "url",
                null, null, null, null, null,
                url, "{\"requestedUrl\":\"" + escapeJson(UrlLogRedactor.redact(url)) + "\"}"
        );
        documents.insertAcl(tenantId, documentId, "user", user.id().toString(), "admin");

        UUID jobId = UUID.randomUUID();
        Map<String, Object> payload = new HashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("url", url);
        String key = idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey
                : "fetch:" + documentId;
        try {
            jobs.enqueue(jobId, tenantId, "ingest.fetch", objectMapper.writeValueAsString(payload), key);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to enqueue fetch job");
        }

        Map<String, Object> result = new LinkedHashMap<>(toMeta(doc));
        result.put("jobId", jobId.toString());
        result.put("sourceUrl", UrlLogRedactor.redact(url));
        return result;
    }

    @Transactional
    public Map<String, Object> reprocess(UserRecord user, UUID documentId) {
        requireAuthor();
        DocumentRecord doc = getForRead(user, documentId);
        if (doc.originalObjectKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Original not available to reprocess");
        }
        UUID tenantId = TenantContext.requireTenantId();
        documents.updateStatus(tenantId, documentId, "queued");
        UUID jobId = UUID.randomUUID();
        Map<String, Object> payload = new HashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("reprocess", true);
        try {
            jobs.enqueue(
                    jobId,
                    tenantId,
                    "transform.document",
                    objectMapper.writeValueAsString(payload),
                    "reprocess:" + documentId + ":" + jobId
            );
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to enqueue reprocess job");
        }
        Map<String, Object> result = new LinkedHashMap<>(toMeta(documents.findByTenantAndId(tenantId, documentId).orElse(doc)));
        result.put("jobId", jobId.toString());
        return result;
    }

    public Map<String, Object> diagnostics(UserRecord user, UUID documentId) {
        DocumentRecord doc = getForRead(user, documentId);
        Map<String, Object> out = new LinkedHashMap<>(toMeta(doc));
        out.put("revisions", documents.listRevisions(doc.tenantId(), doc.id()));
        out.put("jobs", jobs.listForDocument(doc.tenantId(), doc.id(), 20).stream().map(j -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.id().toString());
            m.put("type", j.type());
            m.put("status", j.status());
            m.put("attempt", j.attempt());
            m.put("maxAttempts", j.maxAttempts());
            m.put("lastError", j.lastError());
            m.put("cancelRequested", j.cancelRequested());
            return m;
        }).toList());
        documents.findSourceUrl(doc.tenantId(), doc.id()).ifPresent(u -> out.put("sourceUrl", UrlLogRedactor.redact(u)));
        documents.findFetchMeta(doc.tenantId(), doc.id()).ifPresent(meta -> out.put("fetchMeta", meta));
        return out;
    }

    private IngestResult ingestBytes(
            UserRecord user,
            String title,
            String filename,
            String contentType,
            byte[] bytes,
            String sourceKind,
            String forcedFormatId,
            boolean forceNew
    ) {
        requireAuthor();
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty content");
        }
        UUID tenantId = TenantContext.requireTenantId();
        long maxBytes = Math.min(properties.getLimits().getMaxUploadBytes(),
                workspaceSettings.getOrDefaults(tenantId).maxUploadBytes());
        if (bytes.length > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds max size");
        }

        String formatId = forcedFormatId != null ? forcedFormatId : transformService.detectFormat(filename, contentType);
        if (formatId == null || !capabilities.supportsIngest(formatId)) {
            String detail = formatId == null
                    ? "Unsupported format — not recognized. See GET /v1/capabilities for ingestible formats."
                    : "Unsupported format '" + formatId
                    + "' — registered but not ingestible (or unknown). See GET /v1/capabilities.";
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, detail);
        }

        String sha = ObjectStorageService.sha256(bytes);
        if (!forceNew) {
            Optional<DocumentRecord> existing = documents.findByTenantAndOriginalSha(tenantId, sha);
            if (existing.isPresent()
                    && documents.canRead(tenantId, existing.get().id(), user.id(), TenantContext.roleOrNull())) {
                return new IngestResult(existing.get(), true);
            }
        }

        libraryService.assertWithinQuota(tenantId);

        UUID documentId = UUID.randomUUID();
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
        return new IngestResult(doc, false);
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

    public List<Map<String, Object>> listRevisions(UserRecord user, UUID documentId) {
        DocumentRecord doc = getForRead(user, documentId);
        return documents.listRevisions(doc.tenantId(), doc.id());
    }

    public ClearDoc getCleardocRevision(UserRecord user, UUID documentId, UUID revisionId) {
        DocumentRecord doc = getForRead(user, documentId);
        String key = documents.findCleardocKey(doc.tenantId(), doc.id(), revisionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Revision not found"));
        try {
            return objectMapper.readValue(storage.getBytes(key), ClearDoc.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read ClearDoc");
        }
    }

    @Transactional
    public void softDelete(UserRecord user, UUID documentId) {
        requireAuthor();
        DocumentRecord doc = getForRead(user, documentId);
        documents.softDelete(doc.tenantId(), doc.id());
        audit.record(user.id(), "document.soft_delete", "document", documentId.toString(), Map.of());
    }

    @Transactional
    public void restore(UserRecord user, UUID documentId) {
        requireAuthor();
        UUID tenantId = TenantContext.requireTenantId();
        documents.restore(tenantId, documentId);
        audit.record(user.id(), "document.restore", "document", documentId.toString(), Map.of());
    }

    @Transactional
    public void permanentDelete(UserRecord user, UUID documentId) {
        if (!"admin".equals(TenantContext.roleOrNull())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required for permanent deletion");
        }
        DocumentRecord doc = getForRead(user, documentId);
        documents.permanentDeleteMarker(doc.tenantId(), doc.id());
        audit.record(user.id(), "document.purge", "document", documentId.toString(),
                Map.of("note", "Soft-purged marker; object GC policy-controlled"));
    }

    public Map<String, Object> toMeta(DocumentRecord doc) {
        return toMeta(doc, false);
    }

    public Map<String, Object> toMeta(DocumentRecord doc, boolean duplicateOfExisting) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.id().toString());
        m.put("tenantId", doc.tenantId().toString());
        m.put("title", doc.title());
        m.put("formatId", doc.formatId());
        m.put("status", doc.status());
        m.put("originalSha256", doc.originalSha256() == null ? "" : doc.originalSha256());
        m.put("originalByteLength", doc.originalByteLength() == null ? 0 : doc.originalByteLength());
        m.put("originalFilename", doc.originalFilename() == null ? "" : doc.originalFilename());
        m.put("currentRevisionId", doc.currentRevisionId() == null ? "" : doc.currentRevisionId().toString());
        if (duplicateOfExisting) {
            m.put("duplicateOfExisting", true);
            m.put("message", "Identical content already in your library — opening existing document.");
        }
        return m;
    }

    private void requireAuthor() {
        String role = TenantContext.roleOrNull();
        if (!"author".equals(role) && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Author role required");
        }
    }

    private static String extensionFor(String formatId) {
        return switch (formatId == null ? "" : formatId) {
            case "markdown" -> ".md";
            case "json" -> ".json";
            case "yaml" -> ".yaml";
            case "xml" -> ".xml";
            case "csv" -> ".csv";
            case "tsv" -> ".tsv";
            case "html" -> ".html";
            case "log" -> ".log";
            case "config" -> ".conf";
            case "source-code" -> ".txt";
            default -> ".txt";
        };
    }

    private static String contentTypeFor(String formatId) {
        return switch (formatId == null ? "" : formatId) {
            case "markdown" -> "text/markdown";
            case "json" -> "application/json";
            case "yaml" -> "application/yaml";
            case "xml" -> "application/xml";
            case "csv" -> "text/csv";
            case "tsv" -> "text/tab-separated-values";
            case "html" -> "text/html";
            default -> "text/plain";
        };
    }

    private static String filenameHintFromUrl(String url) {
        try {
            String path = java.net.URI.create(url.trim()).getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return "Remote document";
            }
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return name.isBlank() ? "Remote document" : name;
        } catch (Exception e) {
            return "Remote document";
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
