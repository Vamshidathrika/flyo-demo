package com.flyo.rescue.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Enterprise Multi-Tenant Security Principal for Flyo.
 *
 * Enforces zero-trust tenant boundaries:
 * - Identifies the operator's primary home tenant.
 * - Enforces explicit authorized tenant access allowlists.
 * - Rejects any unauthorized tenant-switching requests via X-Tenant-ID headers.
 */
public class FlyoUserPrincipal extends User {

    private final String tenantId;
    private final Set<String> authorizedTenants;

    public FlyoUserPrincipal(String username,
                             String password,
                             Collection<? extends GrantedAuthority> authorities,
                             String tenantId,
                             Set<String> authorizedTenants) {
        super(username, password, authorities);
        this.tenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId : TenantContext.DEFAULT_TENANT_ID;
        this.authorizedTenants = (authorizedTenants != null) ? Collections.unmodifiableSet(authorizedTenants) : Set.of(this.tenantId);
    }

    public String getTenantId() {
        return tenantId;
    }

    public Set<String> getAuthorizedTenants() {
        return authorizedTenants;
    }

    /**
     * Verifies whether this authenticated principal is permitted to operate within the requested tenant.
     *
     * @param requestedTenant The tenant identifier requested via API or context
     * @return true if permitted; false otherwise
     */
    public boolean isTenantAuthorized(String requestedTenant) {
        if (requestedTenant == null || requestedTenant.isBlank()) {
            return false;
        }
        if (authorizedTenants.contains("*")) {
            return true; // Global administrator privilege
        }
        return authorizedTenants.contains(requestedTenant) || requestedTenant.equals(this.tenantId);
    }
}
