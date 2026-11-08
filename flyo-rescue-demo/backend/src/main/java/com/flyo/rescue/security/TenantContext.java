package com.flyo.rescue.security;

/**
 * ThreadLocal-based context holder for Multi-Tenant Request Isolation.
 * Automatically populated from X-Tenant-ID HTTP headers or JWT claims.
 */
public final class TenantContext {

    public static final String DEFAULT_TENANT_ID = "tenant_default";
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String getTenantId() {
        String tenant = CURRENT_TENANT.get();
        return (tenant != null && !tenant.isBlank()) ? tenant : DEFAULT_TENANT_ID;
    }

    public static void setTenantId(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            CURRENT_TENANT.set(tenantId.trim());
        } else {
            CURRENT_TENANT.set(DEFAULT_TENANT_ID);
        }
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
