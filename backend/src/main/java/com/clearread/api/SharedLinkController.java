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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/shared")
public class SharedLinkController {

    private final ShareService shares;
    private final DocumentRepository documents;
    private final ObjectStorageService storage;
    private final ClearDocArtifactService clearDocArtifacts;

    public SharedLinkController(
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
        var access = shares.resolveShareLink(token, clientKey(request), isAuthenticated());
        var doc = documents.findByTenantAndId(access.tenantId(), access.documentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentId", doc.id().toString());
        m.put("title", doc.title());
        m.put("formatId", doc.formatId());
        m.put("status", doc.status());
        m.put("revisionId", access.revisionId() == null ? "" : access.revisionId().toString());
        m.put("allowDownload", access.allowDownload());
        m.put("allowExport", access.allowExport());
        m.put("artifactNote", "Shared link — permissions enforced at resolve time");
        return m;
    }

    @GetMapping(value = "/{token}/cleardoc", produces = MediaType.APPLICATION_JSON_VALUE)
    ClearDoc cleardoc(HttpServletRequest request, @PathVariable("token") String token) {
        var access = shares.resolveShareLink(token, clientKey(request), isAuthenticated());
        if (access.revisionId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Share revision unavailable");
        }
        String key = documents.findCleardocKey(access.tenantId(), access.documentId(), access.revisionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ClearDoc missing"));
        return clearDocArtifacts.read(key, access.documentId());
    }

    @GetMapping("/{token}/original")
    ResponseEntity<byte[]> original(HttpServletRequest request, @PathVariable("token") String token) {
        var access = shares.resolveShareLink(token, clientKey(request), isAuthenticated());
        if (!access.allowDownload()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Original download not allowed for this share link");
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

    private static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && auth.getPrincipal() != null
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
    }

    private static String clientKey(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
