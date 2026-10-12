package com.clearread.api;

import com.clearread.tenancy.InviteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/invites")
public class InviteController {

    private final InviteRepository invites;

    public InviteController(InviteRepository invites) {
        this.invites = invites;
    }

    @GetMapping("/{token}")
    Map<String, Object> getInvite(@PathVariable("token") String token) {
        var invite = invites.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found"));
        boolean expired = invite.expiresAt().isBefore(Instant.now());
        String status = expired && "pending".equals(invite.status()) ? "expired" : invite.status();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", invite.email());
        m.put("role", invite.role());
        m.put("status", status);
        m.put("tenantSlug", invite.tenantSlug());
        m.put("tenantName", invite.tenantName());
        m.put("expiresAt", invite.expiresAt().toString());
        m.put("instructions",
                "Create an account (or sign in) with email " + invite.email()
                        + ". On first login you join workspace \"" + invite.tenantName()
                        + "\" as " + invite.role() + ". Production deployments usually use org IdP SSO/invite instead of self-registration.");
        return m;
    }
}
