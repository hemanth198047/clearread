package com.clearread.api;

import com.clearread.audit.AuditService;
import com.clearread.identity.UserRecord;
import com.clearread.jobs.JobRepository;
import com.clearread.tenancy.InviteRepository;
import com.clearread.tenancy.TenantContext;
import com.clearread.tenancy.WorkspaceSettingsRepository;
import com.clearread.tenancy.WorkspaceSettingsRepository.WorkspaceSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/tenants/{tenantId}")
public class TenantAdminController {

    private final JdbcTemplate jdbc;
    private final WorkspaceSettingsRepository settings;
    private final JobRepository jobs;
    private final AuditService audit;
    private final InviteRepository invites;

    public TenantAdminController(
            JdbcTemplate jdbc,
            WorkspaceSettingsRepository settings,
            JobRepository jobs,
            AuditService audit,
            InviteRepository invites
    ) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.jobs = jobs;
        this.audit = audit;
        this.invites = invites;
    }

    @GetMapping
    Map<String, Object> tenant(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        MeController.requireUser(request);
        requireActiveTenant(tenantId);
        return jdbc.query(
                "SELECT id, slug, name, status FROM tenants WHERE id = ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("slug", rs.getString("slug"));
                    m.put("name", rs.getString("name"));
                    m.put("status", rs.getString("status"));
                    return m;
                },
                tenantId
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
    }

    @GetMapping("/members")
    List<Map<String, Object>> members(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        MeController.requireUser(request);
        requireAdmin(tenantId);
        return jdbc.query(
                """
                SELECT u.id, u.email, u.display_name, m.role
                FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.tenant_id = ?
                ORDER BY u.display_name
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", rs.getObject("id", UUID.class).toString());
                    m.put("email", rs.getString("email") == null ? "" : rs.getString("email"));
                    m.put("displayName", rs.getString("display_name"));
                    m.put("role", rs.getString("role"));
                    return m;
                },
                tenantId
        );
    }

    public record MemberRoleRequest(String role) {
    }

    @PutMapping("/members/{userId}")
    Map<String, String> changeRole(
            HttpServletRequest request,
            @PathVariable("tenantId") UUID tenantId,
            @PathVariable("userId") UUID userId,
            @RequestBody MemberRoleRequest body
    ) {
        UserRecord actor = MeController.requireUser(request);
        requireAdmin(tenantId);
        if (!List.of("viewer", "author", "admin").contains(body.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role");
        }
        int n = jdbc.update("UPDATE memberships SET role = ? WHERE tenant_id = ? AND user_id = ?",
                body.role(), tenantId, userId);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found");
        }
        audit.record(actor.id(), "member.role_change", "membership", userId.toString(), Map.of("role", body.role()));
        return Map.of("status", "ok");
    }

    @GetMapping("/settings")
    Map<String, Object> getSettings(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        MeController.requireUser(request);
        requireActiveTenant(tenantId);
        return toSettingsMap(settings.getOrDefaults(tenantId));
    }

    public record SettingsRequest(
            Boolean sharingEnabled,
            Boolean anonymousPreviewEnabled,
            Integer retentionDays,
            Long maxUploadBytes,
            Integer maxDocuments,
            Boolean defaultExportDownload,
            Boolean aiEnabled,
            Boolean aiLocalOnly,
            Boolean aiAllowExternal,
            String aiProviderId,
            Integer aiDailyQuota,
            Integer aiMaxRetries
    ) {
    }

    @PutMapping("/settings")
    Map<String, Object> putSettings(
            HttpServletRequest request,
            @PathVariable("tenantId") UUID tenantId,
            @RequestBody SettingsRequest body
    ) {
        UserRecord actor = MeController.requireUser(request);
        requireAdmin(tenantId);
        WorkspaceSettings current = settings.getOrDefaults(tenantId);
        String providerId = body.aiProviderId() == null || body.aiProviderId().isBlank()
                ? current.aiProviderId()
                : body.aiProviderId().trim();
        if (!List.of("mock-local", "ollama", "external").contains(providerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid aiProviderId");
        }
        boolean aiEnabled = body.aiEnabled() == null ? current.aiEnabled() : body.aiEnabled();
        boolean aiLocalOnly = body.aiLocalOnly() == null ? current.aiLocalOnly() : body.aiLocalOnly();
        boolean aiAllowExternal = body.aiAllowExternal() == null ? current.aiAllowExternal() : body.aiAllowExternal();
        if (aiEnabled && "external".equals(providerId) && (aiLocalOnly || !aiAllowExternal)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "External provider requires aiLocalOnly=false and aiAllowExternal=true");
        }
        WorkspaceSettings next = new WorkspaceSettings(
                tenantId,
                body.sharingEnabled() == null ? current.sharingEnabled() : body.sharingEnabled(),
                body.anonymousPreviewEnabled() == null ? current.anonymousPreviewEnabled() : body.anonymousPreviewEnabled(),
                body.retentionDays() == null ? current.retentionDays() : body.retentionDays(),
                body.maxUploadBytes() == null ? current.maxUploadBytes() : body.maxUploadBytes(),
                body.maxDocuments() == null ? current.maxDocuments() : body.maxDocuments(),
                body.defaultExportDownload() == null ? current.defaultExportDownload() : body.defaultExportDownload(),
                aiEnabled,
                aiLocalOnly,
                aiAllowExternal,
                providerId,
                body.aiDailyQuota() == null ? current.aiDailyQuota() : Math.max(0, body.aiDailyQuota()),
                body.aiMaxRetries() == null ? current.aiMaxRetries() : Math.max(0, Math.min(5, body.aiMaxRetries()))
        );
        settings.upsert(next);
        audit.record(actor.id(), "workspace.settings_update", "tenant", tenantId.toString(),
                Map.of("aiEnabled", next.aiEnabled(), "aiProviderId", next.aiProviderId()));
        return toSettingsMap(next);
    }

    @GetMapping("/branding")
    Map<String, Object> branding(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        MeController.requireUser(request);
        requireActiveTenant(tenantId);
        return jdbc.query(
                "SELECT product_name, primary_color, secondary_color, logo_object_key FROM branding_settings WHERE tenant_id = ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("productName", rs.getString("product_name"));
                    m.put("primaryColor", rs.getString("primary_color"));
                    m.put("secondaryColor", rs.getString("secondary_color"));
                    m.put("logoObjectKey", rs.getString("logo_object_key") == null ? "" : rs.getString("logo_object_key"));
                    return m;
                },
                tenantId
        ).stream().findFirst().orElse(Map.of("productName", "ClearRead"));
    }

    public record BrandingRequest(String productName, String primaryColor, String secondaryColor) {
    }

    @PutMapping("/branding")
    Map<String, Object> putBranding(
            HttpServletRequest request,
            @PathVariable("tenantId") UUID tenantId,
            @RequestBody BrandingRequest body
    ) {
        UserRecord actor = MeController.requireUser(request);
        requireAdmin(tenantId);
        jdbc.update(
                """
                INSERT INTO branding_settings (tenant_id, product_name, primary_color, secondary_color, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (tenant_id) DO UPDATE SET
                  product_name = EXCLUDED.product_name,
                  primary_color = EXCLUDED.primary_color,
                  secondary_color = EXCLUDED.secondary_color,
                  updated_at = now()
                """,
                tenantId, body.productName(), body.primaryColor(), body.secondaryColor()
        );
        audit.record(actor.id(), "branding.update", "tenant", tenantId.toString(), Map.of());
        return branding(request, tenantId);
    }

    @GetMapping("/jobs")
    List<Map<String, Object>> listJobs(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        MeController.requireUser(request);
        requireAdmin(tenantId);
        return jdbc.query(
                """
                SELECT id, type, status, attempt, max_attempts, last_error, created_at, updated_at,
                       payload_json->>'documentId' AS document_id
                FROM jobs WHERE tenant_id = ?
                ORDER BY created_at DESC LIMIT 100
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("type", rs.getString("type"));
                    m.put("status", rs.getString("status"));
                    m.put("attempt", rs.getInt("attempt"));
                    m.put("maxAttempts", rs.getInt("max_attempts"));
                    m.put("lastError", rs.getString("last_error") == null ? "" : rs.getString("last_error"));
                    m.put("documentId", rs.getString("document_id") == null ? "" : rs.getString("document_id"));
                    m.put("createdAt", rs.getObject("created_at").toString());
                    m.put("updatedAt", rs.getObject("updated_at").toString());
                    return m;
                },
                tenantId
        );
    }

    @PostMapping("/jobs/{jobId}/requeue")
    Map<String, Object> requeue(
            HttpServletRequest request,
            @PathVariable("tenantId") UUID tenantId,
            @PathVariable("jobId") UUID jobId
    ) {
        UserRecord actor = MeController.requireUser(request);
        requireAdmin(tenantId);
        int n = jdbc.update(
                """
                UPDATE jobs SET status = 'pending', next_run_at = now(), cancel_requested = false,
                               lease_expires_at = NULL, updated_at = now()
                WHERE id = ? AND tenant_id = ? AND status IN ('dead', 'failed', 'cancelled')
                """,
                jobId, tenantId
        );
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job cannot be requeued");
        }
        audit.record(actor.id(), "job.requeue", "job", jobId.toString(), Map.of());
        return jobs.findByTenant(tenantId, jobId)
                .map(j -> Map.<String, Object>of("id", j.id().toString(), "status", j.status()))
                .orElseThrow();
    }

    public record InviteRequest(String email, String role, Integer expiresInDays) {
    }

    @PostMapping("/invites")
    Map<String, Object> createInvite(
            HttpServletRequest request,
            @PathVariable("tenantId") UUID tenantId,
            @RequestBody InviteRequest body
    ) {
        UserRecord actor = MeController.requireUser(request);
        requireAdmin(tenantId);
        if (body.email() == null || body.email().isBlank() || !body.email().contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid email required");
        }
        String role = body.role() == null || body.role().isBlank() ? "author" : body.role().trim();
        if (!List.of("viewer", "author", "admin").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role");
        }
        int days = body.expiresInDays() == null ? 14 : Math.max(1, Math.min(90, body.expiresInDays()));
        var invite = invites.create(tenantId, body.email(), role, actor.id(), Instant.now().plus(days, ChronoUnit.DAYS));
        audit.record(actor.id(), "member.invite", "invite", invite.id().toString(),
                Map.of("email", invite.email(), "role", invite.role()));
        return toInviteMap(invite, request);
    }

    @GetMapping("/invites")
    List<Map<String, Object>> listInvites(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        MeController.requireUser(request);
        requireAdmin(tenantId);
        return invites.listByTenant(tenantId).stream().map(i -> toInviteMap(i, request)).toList();
    }

    @GetMapping("/audit-events")
    List<Map<String, Object>> auditEvents(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        MeController.requireUser(request);
        requireAdmin(tenantId);
        return jdbc.query(
                """
                SELECT id, actor_user_id, action, resource_type, resource_id, created_at
                FROM audit_events WHERE tenant_id = ?
                ORDER BY created_at DESC LIMIT 100
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("actorUserId", rs.getObject("actor_user_id") == null ? "" : rs.getObject("actor_user_id", UUID.class).toString());
                    m.put("action", rs.getString("action"));
                    m.put("resourceType", rs.getString("resource_type"));
                    m.put("resourceId", rs.getString("resource_id"));
                    m.put("createdAt", rs.getObject("created_at").toString());
                    return m;
                },
                tenantId
        );
    }

    private void requireActiveTenant(UUID tenantId) {
        if (!tenantId.equals(TenantContext.requireTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
        }
    }

    private void requireAdmin(UUID tenantId) {
        requireActiveTenant(tenantId);
        if (!"admin".equals(TenantContext.roleOrNull())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }

    private static Map<String, Object> toSettingsMap(WorkspaceSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sharingEnabled", s.sharingEnabled());
        m.put("anonymousPreviewEnabled", s.anonymousPreviewEnabled());
        m.put("retentionDays", s.retentionDays());
        m.put("maxUploadBytes", s.maxUploadBytes());
        m.put("maxDocuments", s.maxDocuments());
        m.put("defaultExportDownload", s.defaultExportDownload());
        m.put("retentionNote", s.retentionDays() == null ? "indefinite (Q4 default)" : s.retentionDays() + " days");
        m.put("aiEnabled", s.aiEnabled());
        m.put("aiLocalOnly", s.aiLocalOnly());
        m.put("aiAllowExternal", s.aiAllowExternal());
        m.put("aiProviderId", s.aiProviderId());
        m.put("aiDailyQuota", s.aiDailyQuota());
        m.put("aiMaxRetries", s.aiMaxRetries());
        return m;
    }

    private static Map<String, Object> toInviteMap(InviteRepository.InviteRecord invite, HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            origin = "http://localhost:5173";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", invite.id().toString());
        m.put("email", invite.email());
        m.put("role", invite.role());
        m.put("status", invite.status());
        m.put("token", invite.token());
        m.put("invitePath", "/invite/" + invite.token());
        m.put("inviteUrl", origin.replaceAll("/$", "") + "/invite/" + invite.token());
        m.put("expiresAt", invite.expiresAt().toString());
        m.put("createdAt", invite.createdAt().toString());
        m.put("note", "No email is sent. Copy inviteUrl and share it with the invitee (IdP/self-registration).");
        return m;
    }
}
