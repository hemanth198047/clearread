package com.clearread.api;

import com.clearread.documents.ClearDocArtifactService;
import com.clearread.documents.DocumentRepository;
import com.clearread.sharing.ShareService;
import com.clearread.storage.ObjectStorageService;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/preview")
public class PreviewController {

    private final ShareService shares;
    private final DocumentRepository documents;
    private final ObjectStorageService storage;
    private final ClearDocArtifactService clearDocArtifacts;

    public PreviewController(
            ShareService shares,
            DocumentRepository documents,
            ObjectStorageService storage,
            ClearDocArtifactService clearDocArtifacts
    ) {
        this.shares = shares;
        this.documents = documents;
        this.storage = storage;
        this.clearDocArtifacts = clearDocArtifacts;
    }

    @GetMapping("/{token}")
    Map<String, Object> meta(HttpServletRequest request, @PathVariable("token") String token) {
        var access = shares.resolvePreview(token, clientKey(request));
        var doc = documents.findByTenantAndId(access.tenantId(), access.documentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentId", doc.id().toString());
        m.put("title", doc.title());
        m.put("formatId", doc.formatId());
        m.put("status", doc.status());
        m.put("revisionId", access.revisionId() == null ? "" : access.revisionId().toString());
        m.put("allowOriginalDownload", access.allowOriginalDownload());
        m.put("artifactNote", "Anonymous preview — isolated TTL token");
        return m;
    }

    @GetMapping(value = "/{token}/cleardoc", produces = MediaType.APPLICATION_JSON_VALUE)
    ClearDoc cleardoc(HttpServletRequest request, @PathVariable("token") String token) {
        var access = shares.resolvePreview(token, clientKey(request));
        if (access.revisionId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Preview revision unavailable");
        }
        String key = documents.findCleardocKey(access.tenantId(), access.documentId(), access.revisionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ClearDoc missing"));
        return clearDocArtifacts.read(key, access.documentId());
    }

    @GetMapping("/{token}/original")
    ResponseEntity<byte[]> original(HttpServletRequest request, @PathVariable("token") String token) {
        var access = shares.resolvePreview(token, clientKey(request));
        if (!access.allowOriginalDownload()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Original download not allowed for this preview");
        }
        var doc = documents.findByTenantAndId(access.tenantId(), access.documentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        if (doc.originalObjectKey() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Original not available");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(storage.getBytes(doc.originalObjectKey()));
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
