package com.clearread.tenancy;

import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId, String role) {
        TENANT.set(tenantId);
        ROLE.set(role);
    }

    public static UUID requireTenantId() {
        UUID id = TENANT.get();
        if (id == null) {
            throw new IllegalStateException("Tenant context is not set");
        }
        return id;
    }

    public static UUID tenantIdOrNull() {
        return TENANT.get();
    }

    public static String roleOrNull() {
        return ROLE.get();
    }

    public static void clear() {
        TENANT.remove();
        ROLE.remove();
    }
}
