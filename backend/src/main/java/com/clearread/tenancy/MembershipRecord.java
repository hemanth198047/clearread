package com.clearread.tenancy;

import java.util.UUID;

public record MembershipRecord(UUID id, UUID tenantId, UUID userId, String role, String tenantSlug, String tenantName) {
}
