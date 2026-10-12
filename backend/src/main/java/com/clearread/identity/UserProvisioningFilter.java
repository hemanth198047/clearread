package com.clearread.identity;

import com.clearread.tenancy.InviteRepository;
import com.clearread.tenancy.MembershipRepository;
import com.clearread.tenancy.MembershipRecord;
import com.clearread.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class UserProvisioningFilter extends OncePerRequestFilter {

    public static final String ATTR_USER = "clearread.user";
    public static final UUID DEMO_TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final InviteRepository inviteRepository;

    public UserProvisioningFilter(
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            InviteRepository inviteRepository
    ) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.inviteRepository = inviteRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                // Some local IdP access tokens omit `sub` (e.g. lightweight tokens); fall back stably.
                String subject = firstNonBlank(
                        jwt.getSubject(),
                        jwt.getClaimAsString("preferred_username"),
                        jwt.getClaimAsString("email"),
                        jwt.getClaimAsString("sid")
                );
                if (subject == null) {
                    filterChain.doFilter(request, response);
                    return;
                }
                String email = jwt.getClaimAsString("email");
                String preferredUsername = jwt.getClaimAsString("preferred_username");
                String name = firstNonBlank(jwt.getClaimAsString("name"), preferredUsername, email, subject);
                UserRecord user = userRepository.upsert(subject, email, name);
                request.setAttribute(ATTR_USER, user);

                List<MembershipRecord> memberships = membershipRepository.findByUserId(user.id());
                if (memberships.isEmpty()) {
                    var invite = inviteRepository.findPendingByEmail(email);
                    String role = DefaultMembershipRole.resolve(
                            preferredUsername,
                            invite.map(InviteRepository.InviteRecord::role).orElse(null)
                    );
                    UUID tenantId = invite.map(InviteRepository.InviteRecord::tenantId).orElse(DEMO_TENANT_ID);
                    membershipRepository.ensureMembership(tenantId, user.id(), role);
                    invite.ifPresent(inv -> inviteRepository.markAccepted(inv.id(), user.id()));
                    memberships = membershipRepository.findByUserId(user.id());
                }

                UUID requestedTenant = parseUuid(request.getHeader("X-Tenant-Id"));
                MembershipRecord active = memberships.stream()
                        .filter(m -> requestedTenant == null || m.tenantId().equals(requestedTenant))
                        .findFirst()
                        .orElse(memberships.getFirst());
                TenantContext.set(active.tenantId(), active.role());
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "user";
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
