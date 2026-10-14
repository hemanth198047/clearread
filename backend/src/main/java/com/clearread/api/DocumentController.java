package com.clearread.api;

import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentService;
import com.clearread.export.ExportService;
import com.clearread.identity.UserRecord;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final ExportService exportService;

    public DocumentController(DocumentService documentService, ExportService exportService) {
        this.documentService = documentService;
        this.exportService = exportService;
    }

    public record PasteRequest(String title, @NotBlank String formatId, @NotBlank String content, Boolean forceNew) {
    }

    public record UrlIngestRequest(String title, @NotBlank String url) {
    }

    @GetMapping
    List<Map<String, Object>> list(HttpServletRequest request) {
        UserRecord user = MeController.requireUser(request);
        return documentService.list(user).stream().map(documentService::toMeta).toList();
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> upload(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "title", required = false) String title,
            @RequestPart(value = "forceNew", required = false) String forceNew
    ) throws IOException {
        UserRecord user = MeController.requireUser(request);
        boolean force = parseForceNew(forceNew);
        DocumentService.IngestResult result = documentService.ingestUpload(
                user,
                title,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes(),
                force
        );
        return documentService.toMeta(result.document(), result.duplicateOfExisting(), true);
    }

    @PostMapping(path = "/paste", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> paste(HttpServletRequest request, @RequestBody PasteRequest body) {
        UserRecord user = MeController.requireUser(request);
        DocumentService.IngestResult result = documentService.ingestPaste(
                user, body.title(), body.formatId(), body.content(), Boolean.TRUE.equals(body.forceNew())
        );
        return documentService.toMeta(result.document(), result.duplicateOfExisting(), true);
    }

    @PostMapping(path = "/from-url", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> fromUrl(
            HttpServletRequest request,
            @RequestBody UrlIngestRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        UserRecord user = MeController.requireUser(request);
        return documentService.ingestUrl(user, body.title(), body.url(), idempotencyKey);
    }

    @PostMapping("/{id}/reprocess")
    Map<String, Object> reprocess(HttpServletRequest request, @PathVariable("id") UUID id) {
        UserRecord user = MeController.requireUser(request);
        return documentService.reprocess(user, id);
    }

    @GetMapping("/{id}/diagnostics")
    Map<String, Object> diagnostics(HttpServletRequest request, @PathVariable("id") UUID id) {
        UserRecord user = MeController.requireUser(request);
        return documentService.diagnostics(user, id);
    }

    @GetMapping("/{id}")
    Map<String, Object> get(HttpServletRequest request, @PathVariable("id") UUID id) {
        UserRecord user = MeController.requireUser(request);
        return documentService.getMeta(user, id);
    }

    @GetMapping("/{id}/original")
    ResponseEntity<byte[]> original(HttpServletRequest request, @PathVariable("id") UUID id) {
        UserRecord user = MeController.requireUser(request);
        DocumentRecord doc = documentService.getForRead(user, id);
        byte[] bytes = documentService.downloadOriginal(user, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName(doc.originalFilename()) + "\"")
                .header("X-Content-SHA256", doc.originalSha256() == null ? "" : doc.originalSha256())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    @GetMapping("/{id}/cleardoc")
    ClearDoc cleardoc(HttpServletRequest request, @PathVariable("id") UUID id) {
        UserRecord user = MeController.requireUser(request);
        return documentService.getCleardoc(user, id);
    }

    @GetMapping("/{id}/revisions")
    List<Map<String, Object>> revisions(HttpServletRequest request, @PathVariable("id") UUID id) {
        return documentService.listRevisions(MeController.requireUser(request), id);
    }

    @GetMapping("/{id}/revisions/{revId}/cleardoc")
    ClearDoc revisionCleardoc(
            HttpServletRequest request,
            @PathVariable("id") UUID id,
            @PathVariable("revId") UUID revId
    ) {
        return documentService.getCleardocRevision(MeController.requireUser(request), id, revId);
    }

    @DeleteMapping("/{id}")
    Map<String, String> delete(HttpServletRequest request, @PathVariable("id") UUID id) {
        documentService.softDelete(MeController.requireUser(request), id);
        return Map.of("status", "deleted");
    }

    @PostMapping("/{id}/restore")
    Map<String, String> restore(HttpServletRequest request, @PathVariable("id") UUID id) {
        documentService.restore(MeController.requireUser(request), id);
        return Map.of("status", "restored");
    }

    @PostMapping("/{id}/purge")
    Map<String, String> purge(HttpServletRequest request, @PathVariable("id") UUID id) {
        documentService.permanentDelete(MeController.requireUser(request), id);
        return Map.of("status", "purged");
    }

    @GetMapping("/{id}/exports")
    List<Map<String, Object>> exports(HttpServletRequest request, @PathVariable("id") UUID id) {
        return exportService.listExports(MeController.requireUser(request), id);
    }

    public record ExportRequest(@NotBlank String targetFormat) {
    }

    @PostMapping("/{id}/exports")
    Map<String, Object> createExport(HttpServletRequest request, @PathVariable("id") UUID id, @RequestBody ExportRequest body) {
        return exportService.requestExport(MeController.requireUser(request), id, body.targetFormat());
    }

    @GetMapping("/{id}/exports/{exportId}/content")
    ResponseEntity<byte[]> exportContent(
            HttpServletRequest request,
            @PathVariable("id") UUID id,
            @PathVariable("exportId") UUID exportId
    ) {
        byte[] bytes = exportService.downloadExport(MeController.requireUser(request), id, exportId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"export-" + exportId + "\"")
                .header("X-ClearRead-Artifact-Kind", "generated")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    private static boolean parseForceNew(String forceNew) {
        if (forceNew == null || forceNew.isBlank()) {
            return false;
        }
        String v = forceNew.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "original.bin";
        }
        return name.replace("\"", "");
    }
}
