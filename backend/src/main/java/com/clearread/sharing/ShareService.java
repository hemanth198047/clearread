package com.clearread.sharing;

import com.clearread.audit.AuditService;
import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentRepository;
import com.clearread.identity.UserRecord;
import com.clearread.net.TokenHasher;
import com.clearread.tenancy.TenantContext;
import com.clearread.tenancy.WorkspaceSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareService.class);

    private final JdbcTemplate jdbc;
    private final DocumentRepository documents;
    private final WorkspaceSettingsRepository settings;
    private final AuditService audit;
    private final ConcurrentHashMap<String, RateWindow> rates = new ConcurrentHashMap<>();

    public ShareService(
            JdbcTemplate jdbc,
            DocumentRepository documents,
            WorkspaceSettingsRepository settings,
            AuditService audit
    ) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> createShareLink(
            UserRecord user, UUID documentId, OffsetDateTime expiresAt,
            boolean requireAuth, boolean allowDownload, boolean allowExport, String label
    ) {
        UUID tenantId = TenantContext.requireTenantId();
        requireShare(user, documentId);
        var ws = settings.getOrDefaults(tenantId);
        if (!ws.sharingEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sharing disabled by workspace policy");
        }
        String token = TokenHasher.generateToken();
        String hash = TokenHasher.sha256(token);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO share_links (
                  id, tenant_id, document_id, created_by, grants_permission, token_hash, label,
                  require_auth, allow_download, allow_export, expires_at
                ) VALUES (?, ?, ?, ?, 'read', ?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, documentId, user.id(), hash, label, requireAuth, allowDownload, allowExport, expiresAt
        );
        audit.record(user.id(), "share.create", "share_link", id.toString(), Map.of("documentId", documentId.toString()));
        log.info("Created share link id={} documentId={} token=REDACTED", id, documentId);
        Map<String, Object> out = meta(id, tenantId);
        out.put("token", token); // returned once
        return out;
    }

    @Transactional
    public Map<String, Object> createPreviewToken(
            UserRecord user, UUID documentId, OffsetDateTime expiresAt, Integer maxViews, boolean allowOriginal
    ) {
        UUID tenantId = TenantContext.requireTenantId();
        requireShare(user, documentId);
        var ws = settings.getOrDefaults(tenantId);
        if (!ws.sharingEnabled() || !ws.anonymousPreviewEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Anonymous preview disabled by workspace policy");
        }
        if (expiresAt == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Preview tokens require expiresAt");
        }
        DocumentRecord doc = documents.findByTenantAndId(tenantId, documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        String token = TokenHasher.generateToken();
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO preview_tokens (
                  id, tenant_id, document_id, revision_id, created_by, token_hash, expires_at,
                  max_views, allow_original_download
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, documentId, doc.currentRevisionId(), user.id(), TokenHasher.sha256(token),
                expiresAt, maxViews, allowOriginal
        );
        audit.record(user.id(), "preview.create", "preview_token", id.toString(), Map.of("documentId", documentId.toString()));
        log.info("Created preview token id={} documentId={} token=REDACTED", id, documentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("documentId", documentId.toString());
        out.put("expiresAt", expiresAt.toString());
        out.put("token", token);
        out.put("allowOriginalDownload", allowOriginal);
        return out;
    }

    public void revokeShareLink(UserRecord user, UUID linkId) {
        UUID tenantId = TenantContext.requireTenantId();
        requireAdminOrAuthor();
        int n = jdbc.update(
                "UPDATE share_links SET revoked_at = now() WHERE id = ? AND tenant_id = ? AND revoked_at IS NULL",
                linkId, tenantId
        );
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found");
        }
        audit.record(user.id(), "share.revoke", "share_link", linkId.toString(), Map.of());
    }

    public void revokePreview(UserRecord user, UUID previewId) {
        UUID tenantId = TenantContext.requireTenantId();
        requireAdminOrAuthor();
        int n = jdbc.update(
                "UPDATE preview_tokens SET revoked_at = now() WHERE id = ? AND tenant_id = ? AND revoked_at IS NULL",
                previewId, tenantId
        );
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview token not found");
        }
        audit.record(user.id(), "preview.revoke", "preview_token", previewId.toString(), Map.of());
    }

    public List<Map<String, Object>> listShareLinks(UserRecord user, UUID documentId) {
        UUID tenantId = TenantContext.requireTenantId();
        requireShare(user, documentId);
        return jdbc.query(
                """
                SELECT id, document_id, require_auth, allow_download, allow_export, expires_at, revoked_at, created_at, label
                FROM share_links WHERE tenant_id = ? AND document_id = ?
                ORDER BY created_at DESC
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("documentId", rs.getObject("document_id", UUID.class).toString());
                    m.put("requireAuth", rs.getBoolean("require_auth"));
                    m.put("allowDownload", rs.getBoolean("allow_download"));
                    m.put("allowExport", rs.getBoolean("allow_export"));
                    m.put("expiresAt", rs.getObject("expires_at") == null ? "" : rs.getObject("expires_at").toString());
                    m.put("revokedAt", rs.getObject("revoked_at") == null ? "" : rs.getObject("revoked_at").toString());
                    m.put("createdAt", rs.getObject("created_at").toString());
                    m.put("label", rs.getString("label") == null ? "" : rs.getString("label"));
                    return m;
                },
                tenantId, documentId
        );
    }

    public PreviewAccess resolvePreview(String rawToken, String clientKey) {
        checkRate(clientKey);
        if (rawToken == null || rawToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
        }
        String hash = TokenHasher.sha256(rawToken);
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT id, tenant_id, document_id, revision_id, expires_at, max_views, view_count,
                       allow_original_download, revoked_at
                FROM preview_tokens WHERE token_hash = ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("tenantId", rs.getObject("tenant_id", UUID.class));
                    m.put("documentId", rs.getObject("document_id", UUID.class));
                    m.put("revisionId", rs.getObject("revision_id", UUID.class));
                    m.put("expiresAt", rs.getObject("expires_at", OffsetDateTime.class));
                    m.put("maxViews", rs.getObject("max_views") == null ? null : rs.getInt("max_views"));
                    m.put("viewCount", rs.getInt("view_count"));
                    m.put("allowOriginal", rs.getBoolean("allow_original_download"));
                    m.put("revokedAt", rs.getObject("revoked_at"));
                    return m;
                },
                hash
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
        }
        Map<String, Object> row = rows.getFirst();
        if (row.get("revokedAt") != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Preview revoked");
        }
        OffsetDateTime exp = (OffsetDateTime) row.get("expiresAt");
        if (exp.isBefore(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Preview expired");
        }
        Integer maxViews = (Integer) row.get("maxViews");
        int viewCount = (Integer) row.get("viewCount");
        if (maxViews != null && viewCount >= maxViews) {
            throw new ResponseStatusException(HttpStatus.GONE, "Preview view limit reached");
        }
        jdbc.update("UPDATE preview_tokens SET view_count = view_count + 1 WHERE id = ?", row.get("id"));
        return new PreviewAccess(
                (UUID) row.get("tenantId"),
                (UUID) row.get("documentId"),
                (UUID) row.get("revisionId"),
                (Boolean) row.get("allowOriginal")
        );
    }

    public record PreviewAccess(UUID tenantId, UUID documentId, UUID revisionId, boolean allowOriginalDownload) {
    }

    private Map<String, Object> meta(UUID id, UUID tenantId) {
        return jdbc.query(
                """
                SELECT id, document_id, require_auth, allow_download, allow_export, expires_at, created_at
                FROM share_links WHERE id = ? AND tenant_id = ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("documentId", rs.getObject("document_id", UUID.class).toString());
                    m.put("requireAuth", rs.getBoolean("require_auth"));
                    m.put("allowDownload", rs.getBoolean("allow_download"));
                    m.put("allowExport", rs.getBoolean("allow_export"));
                    m.put("expiresAt", rs.getObject("expires_at") == null ? "" : rs.getObject("expires_at").toString());
                    m.put("createdAt", rs.getObject("created_at").toString());
                    return m;
                },
                id, tenantId
        ).stream().findFirst().orElseThrow();
    }

    private void requireShare(UserRecord user, UUID documentId) {
        requireAdminOrAuthor();
        UUID tenantId = TenantContext.requireTenantId();
        if (!documents.canRead(tenantId, documentId, user.id(), TenantContext.roleOrNull())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
    }

    private void requireAdminOrAuthor() {
        String role = TenantContext.roleOrNull();
        if (!"author".equals(role) && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Share permission requires author or admin");
        }
    }

    private void checkRate(String clientKey) {
        String key = clientKey == null ? "unknown" : clientKey;
        long now = System.currentTimeMillis();
        RateWindow w = rates.compute(key, (k, prev) -> {
            if (prev == null || now - prev.windowStartMs > 60_000) {
                return new RateWindow(now, new AtomicInteger(1));
            }
            prev.count.incrementAndGet();
            return prev;
        });
        if (w.count.get() > 60) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Preview rate limit exceeded");
        }
    }

    private record RateWindow(long windowStartMs, AtomicInteger count) {
    }
}
