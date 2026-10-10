package com.clearread.api;

import com.clearread.identity.UserRecord;
import com.clearread.sharing.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ShareController {

    private final ShareService shares;

    public ShareController(ShareService shares) {
        this.shares = shares;
    }

    public record ShareLinkRequest(
            String expiresAt,
            Boolean requireAuth,
            Boolean allowDownload,
            Boolean allowExport,
            String label
    ) {
    }

    public record PreviewRequest(String expiresAt, Integer maxViews, Boolean allowOriginalDownload) {
    }

    @GetMapping("/documents/{id}/share-links")
    List<Map<String, Object>> list(HttpServletRequest request, @PathVariable("id") UUID id) {
        return shares.listShareLinks(MeController.requireUser(request), id);
    }

    @PostMapping("/documents/{id}/share-links")
    Map<String, Object> create(HttpServletRequest request, @PathVariable("id") UUID id, @RequestBody ShareLinkRequest body) {
        UserRecord user = MeController.requireUser(request);
        OffsetDateTime exp = body.expiresAt() == null || body.expiresAt().isBlank()
                ? null : OffsetDateTime.parse(body.expiresAt());
        return shares.createShareLink(
                user, id, exp,
                body.requireAuth() == null || body.requireAuth(),
                Boolean.TRUE.equals(body.allowDownload()),
                Boolean.TRUE.equals(body.allowExport()),
                body.label()
        );
    }

    @DeleteMapping("/share-links/{linkId}")
    Map<String, String> revoke(HttpServletRequest request, @PathVariable("linkId") UUID linkId) {
        shares.revokeShareLink(MeController.requireUser(request), linkId);
        return Map.of("status", "revoked");
    }

    @PostMapping("/documents/{id}/preview-tokens")
    Map<String, Object> preview(HttpServletRequest request, @PathVariable("id") UUID id, @RequestBody PreviewRequest body) {
        UserRecord user = MeController.requireUser(request);
        OffsetDateTime exp = OffsetDateTime.parse(body.expiresAt());
        return shares.createPreviewToken(user, id, exp, body.maxViews(), Boolean.TRUE.equals(body.allowOriginalDownload()));
    }

    @DeleteMapping("/preview-tokens/{previewId}")
    Map<String, String> revokePreview(HttpServletRequest request, @PathVariable("previewId") UUID previewId) {
        shares.revokePreview(MeController.requireUser(request), previewId);
        return Map.of("status", "revoked");
    }
}
