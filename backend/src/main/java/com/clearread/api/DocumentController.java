package com.clearread.api;

import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentService;
import com.clearread.identity.UserRecord;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    public record PasteRequest(String title, @NotBlank String formatId, @NotBlank String content) {
    }

    @GetMapping
    List<Map<String, Object>> list(HttpServletRequest request) {
        UserRecord user = MeController.requireUser(request);
        return documentService.list(user).stream().map(this::toMeta).toList();
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> upload(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "title", required = false) String title
    ) throws IOException {
        UserRecord user = MeController.requireUser(request);
        DocumentRecord doc = documentService.ingestUpload(
                user,
                title,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes()
        );
        return toMeta(doc);
    }

    @PostMapping(path = "/paste", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> paste(HttpServletRequest request, @RequestBody PasteRequest body) {
        UserRecord user = MeController.requireUser(request);
        DocumentRecord doc = documentService.ingestPaste(user, body.title(), body.formatId(), body.content());
        return toMeta(doc);
    }

    @GetMapping("/{id}")
    Map<String, Object> get(HttpServletRequest request, @PathVariable("id") UUID id) {
        UserRecord user = MeController.requireUser(request);
        return toMeta(documentService.getForRead(user, id));
    }

    @GetMapping("/{id}/original")
    ResponseEntity<byte[]> original(HttpServletRequest request, @PathVariable("id") UUID id) {
        UserRecord user = MeController.requireUser(request);
        DocumentRecord doc = documentService.getForRead(user, id);
        byte[] bytes = documentService.downloadOriginal(user, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName(doc.originalFilename()) + "\"")
                .header("X-Content-SHA256", doc.originalSha256())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    @GetMapping("/{id}/cleardoc")
    ClearDoc cleardoc(HttpServletRequest request, @PathVariable("id") UUID id) {
        UserRecord user = MeController.requireUser(request);
        return documentService.getCleardoc(user, id);
    }

    private Map<String, Object> toMeta(DocumentRecord doc) {
        return Map.of(
                "id", doc.id().toString(),
                "tenantId", doc.tenantId().toString(),
                "title", doc.title(),
                "formatId", doc.formatId(),
                "status", doc.status(),
                "originalSha256", doc.originalSha256() == null ? "" : doc.originalSha256(),
                "originalByteLength", doc.originalByteLength() == null ? 0 : doc.originalByteLength(),
                "originalFilename", doc.originalFilename() == null ? "" : doc.originalFilename(),
                "currentRevisionId", doc.currentRevisionId() == null ? "" : doc.currentRevisionId().toString()
        );
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "original.bin";
        }
        return name.replace("\"", "");
    }
}
