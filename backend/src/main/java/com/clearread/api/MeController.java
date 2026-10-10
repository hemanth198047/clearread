package com.clearread.api;

import com.clearread.identity.ReaderPreferencesRepository;
import com.clearread.identity.UserProvisioningFilter;
import com.clearread.identity.UserRecord;
import com.clearread.tenancy.MembershipRecord;
import com.clearread.tenancy.MembershipRepository;
import com.clearread.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class MeController {

    private final MembershipRepository membershipRepository;
    private final ReaderPreferencesRepository readerPreferencesRepository;
    private final ObjectMapper objectMapper;

    public MeController(
            MembershipRepository membershipRepository,
            ReaderPreferencesRepository readerPreferencesRepository,
            ObjectMapper objectMapper
    ) {
        this.membershipRepository = membershipRepository;
        this.readerPreferencesRepository = readerPreferencesRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest request) {
        UserRecord user = requireUser(request);
        return Map.of(
                "id", user.id().toString(),
                "email", user.email() == null ? "" : user.email(),
                "displayName", user.displayName(),
                "activeTenantId", TenantContext.requireTenantId().toString(),
                "activeRole", TenantContext.roleOrNull()
        );
    }

    @GetMapping("/me/tenants")
    List<Map<String, String>> tenants(HttpServletRequest request) {
        UserRecord user = requireUser(request);
        return membershipRepository.findByUserId(user.id()).stream()
                .map(this::toMap)
                .toList();
    }

    @GetMapping(value = "/me/reader-preferences", produces = MediaType.APPLICATION_JSON_VALUE)
    JsonNode getReaderPreferences(HttpServletRequest request) {
        UserRecord user = requireUser(request);
        String json = readerPreferencesRepository.findJson(user.id()).orElse("{}");
        return objectMapper.readTree(json);
    }

    @PutMapping(value = "/me/reader-preferences", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    JsonNode putReaderPreferences(HttpServletRequest request, @RequestBody JsonNode body) {
        UserRecord user = requireUser(request);
        String json = objectMapper.writeValueAsString(body);
        readerPreferencesRepository.upsert(user.id(), json);
        return body;
    }

    private Map<String, String> toMap(MembershipRecord m) {
        return Map.of(
                "tenantId", m.tenantId().toString(),
                "slug", m.tenantSlug(),
                "name", m.tenantName(),
                "role", m.role()
        );
    }

    static UserRecord requireUser(HttpServletRequest request) {
        Object attr = request.getAttribute(UserProvisioningFilter.ATTR_USER);
        if (!(attr instanceof UserRecord user)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return user;
    }
}
