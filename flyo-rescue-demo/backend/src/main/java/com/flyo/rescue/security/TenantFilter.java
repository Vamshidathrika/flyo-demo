package com.flyo.rescue.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Enterprise Multi-Tenant Boundary Isolation Filter.
 *
 * Enforces strict zero-trust tenant boundary rules:
 * 1. An authenticated caller's identity is authoritative.
 * 2. Client-supplied X-Tenant-ID header is treated as an UNTRUSTED tenant-selection request.
 * 3. If X-Tenant-ID is supplied, it is validated against the caller's authorizedTenants list.
 * 4. Unauthorized tenant selection is REJECTED IMMEDIATELY with HTTP 403 Forbidden.
 * 5. If no X-Tenant-ID is supplied, tenant defaults to the principal's home tenantId.
 * 6. Public/webhook endpoints use provider-mapped tenant configurations or fail closed.
 * 7. TenantContext is strictly cleared in a finally block to prevent thread-pool leakage.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);
    public static final String TENANT_HEADER = "X-Tenant-ID";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestedTenant = request.getHeader(TENANT_HEADER);
        if (requestedTenant != null) {
            requestedTenant = requestedTenant.trim();
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        try {
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                Object principalObj = auth.getPrincipal();
                if (principalObj instanceof FlyoUserPrincipal principal) {
                    if (requestedTenant != null && !requestedTenant.isBlank()) {
                        // Validate whether caller is authorized to switch/operate in requested tenant
                        if (!principal.isTenantAuthorized(requestedTenant)) {
                            log.warn("[Tenant Security] Blocked unauthorized tenant selection by user '{}': requested '{}', authorized: {}",
                                    principal.getUsername(), requestedTenant, principal.getAuthorizedTenants());
                            sendForbidden(response, "Access denied: Unauthorized tenant selection for tenant '" + requestedTenant + "'.");
                            return;
                        }
                        TenantContext.setTenantId(requestedTenant);
                    } else {
                        TenantContext.setTenantId(principal.getTenantId());
                    }
                } else {
                    // Non-FlyoUserPrincipal authenticated user (e.g. standard User in basic auth)
                    if (requestedTenant != null && !requestedTenant.isBlank()) {
                        // Check if admin authority present
                        boolean isAdmin = auth.getAuthorities().stream()
                                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
                        if (!isAdmin && !TenantContext.DEFAULT_TENANT_ID.equals(requestedTenant)) {
                            log.warn("[Tenant Security] Blocked non-admin user '{}' from selecting tenant '{}'", auth.getName(), requestedTenant);
                            sendForbidden(response, "Access denied: Caller not authorized for tenant '" + requestedTenant + "'.");
                            return;
                        }
                        TenantContext.setTenantId(requestedTenant);
                    } else {
                        TenantContext.setTenantId(TenantContext.DEFAULT_TENANT_ID);
                    }
                }
            } else {
                // Unauthenticated / public endpoint:
                // An unauthenticated request must NEVER be permitted to claim a tenant via header!
                if (requestedTenant != null && !requestedTenant.isBlank() && !TenantContext.DEFAULT_TENANT_ID.equals(requestedTenant)) {
                    // Check if it's an unauthenticated attempt to spoof tenant
                    String path = request.getRequestURI();
                    if (!path.startsWith("/api/webhooks/") && !path.startsWith("/api/whatsapp/webhook")) {
                        log.warn("[Tenant Security] Unauthenticated request attempted to spoof tenant header '{}' on path '{}'", requestedTenant, path);
                        sendForbidden(response, "Anonymous tenant selection header is strictly forbidden.");
                        return;
                    }
                }
                TenantContext.setTenantId(TenantContext.DEFAULT_TENANT_ID);
            }

            response.setHeader(TENANT_HEADER, TenantContext.getTenantId());
            org.slf4j.MDC.put(CorrelationMdcFilter.MDC_TENANT_ID, TenantContext.getTenantId());
            filterChain.doFilter(request, response);

        } finally {
            org.slf4j.MDC.remove(CorrelationMdcFilter.MDC_TENANT_ID);
            TenantContext.clear();
        }
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "error", "Forbidden",
                "message", message,
                "status", 403
        )));
    }
}
