# FLYO — STAGE 3 TENANT SECURITY REMEDIATION
## Complete Security Overhaul of Multi-Tenant Boundary Isolation

**Date:** November 2026  
**Security Lead:** Principal Application Security Architect & Enterprise Integration Specialist  
**Target:** Flyo Application (`flyo-rescue-demo/backend`)  
**Status:** REMEDIATED & INDEPENDENTLY VERIFIED

---

## 1. Executive Summary: The Defect and The Architectural Fix

### The Root Cause Vulnerability (Stage 2 Defect)
In Stage 2, `TenantFilter.java` was configured to inspect the `X-Tenant-ID` header directly:
```java
// VULNERABLE STAGE 2 CODE:
String tenantHeader = request.getHeader("X-Tenant-ID");
if (tenantHeader != null && !tenantHeader.isBlank()) {
    TenantContext.setTenantId(tenantHeader);
}
```
Moreover, `TenantFilter` was installed before `SecurityContextHolderFilter`, executing prior to authentication. Consequently:
1. Any unauthenticated caller could assert any tenant ID by specifying `X-Tenant-ID: victim_tenant`.
2. Any authenticated operator belonging to Tenant A could read, approve, or cancel records belonging to Tenant B simply by sending `X-Tenant-ID: tenant_B`.
3. Outbox workers and saga coordinator background loops did not propagate or clear tenant context.

---

## 2. Implemented Defense-in-Depth Architecture

### A. Authenticated Identity as Sole Source of Truth
Tenant identity is now strictly derived from the authenticated principal (`FlyoUserPrincipal`) or cryptographic claims inside the validated JWT Bearer token:
```java
// SECURE STAGE 3 ARCHITECTURE:
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
    if (auth.getPrincipal() instanceof FlyoUserPrincipal principal) {
        if (requestedTenant != null && !requestedTenant.isBlank()) {
            if (!principal.isTenantAuthorized(requestedTenant)) {
                sendForbidden(response, "Access denied: Unauthorized tenant selection.");
                return;
            }
            TenantContext.setTenantId(requestedTenant);
        } else {
            TenantContext.setTenantId(principal.getTenantId());
        }
    }
}
```

### B. Untrusted Header Treated as Restricted Selection Request
`X-Tenant-ID` is **never** trusted as proof of authorization. When an HTTP request includes `X-Tenant-ID`:
1. The filter verifies whether `principal.isTenantAuthorized(requestedTenant)` is true.
2. If the user only has access to `tenant_tmc_1` and requests `X-Tenant-ID: tenant_airline_2`, the filter immediately rejects the request with **HTTP 403 Forbidden**.
3. Only authorized multi-tenant operators or global system administrators (`authorizedTenants = "*"`) can switch tenant views.
4. Unauthenticated callers sending non-default `X-Tenant-ID` headers are immediately rejected with **HTTP 403 Forbidden**.

### C. Filter Ordering in Spring Security
In `SecurityConfig.java`:
```java
// JWT Authentication executes FIRST and populates SecurityContextHolder
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
// TenantFilter executes AFTER authentication and validates identity against X-Tenant-ID
.addFilterAfter(tenantFilter, UsernamePasswordAuthenticationFilter.class);
```

### D. Strict ThreadLocal Lifecycle Management
`TenantFilter.java` wraps the entire execution in a `try ... finally` block:
```java
try {
    response.setHeader(TENANT_HEADER, TenantContext.getTenantId());
    filterChain.doFilter(request, response);
} finally {
    TenantContext.clear(); // Guaranteed cleanup: prevents thread-pool connection leakage
}
```

### E. Propagation Through Asynchronous Workers & Sagas
1. **Transactional Outbox (`OutboxDispatcherWorker.java`)**:
   - Each `OutboxEvent` persists `tenant_id` at insertion.
   - When a worker claims an event, it sets `TenantContext.setTenantId(event.getTenantId())` before consumer invocation, and clears it in `finally`.
2. **Distributed Saga (`RebookingSagaCoordinator.java`)**:
   - Each `SagaExecution` and `SagaStepLog` persists `tenant_id` from the parent `RescueCase`.
   - Each step execution sets and clears `TenantContext` safely.

### F. Repository & Service Layer Fail-Closed Scoping
- `RescueCaseRepository.java`: Added `findByTenantId`, `findByIdAndTenantId`, `findByTenantIdAndState`.
- `CaseService.java`: `getAllCases()` and `getCaseById(id)` verify tenant match; cross-tenant lookups fail closed (return `Optional.empty()` -> 404).
- `BookingService.java`: `getAllBookings()` and `getBookingById(id)` enforce tenant boundaries.
- `PaymentController.java`: Verifies case ownership before returning virtual card and transaction ledger.
- `SagaMonitoringController.java`: Outbox events, sagas, and retry operations are strictly scoped to the caller's active authorized tenant.

---

## 3. Verification & Evidence Matrix

| Vulnerability Scenario | Attack Vector | Stage 2 Behavior | Stage 3 Defense | Result |
|------------------------|---------------|------------------|-----------------|--------|
| **Anonymous Tenant Spoofing** | Public request with `X-Tenant-ID: tenant_victim` | Permitted access to victim records | Filter rejects with HTTP 403 Forbidden | **BLOCKED** |
| **Cross-Tenant Header Switching** | Authenticated Tenant A sends `X-Tenant-ID: tenant_B` | Permitted switching | Filter validates `authorizedTenants`; rejects with HTTP 403 | **BLOCKED** |
| **Direct Cross-Tenant Case Lookup** | Tenant A queries `/api/cases/CASE-B2` belonging to Tenant B | Returned case details | `CaseService` checks tenant ID; returns HTTP 404/Empty | **BLOCKED** |
| **Direct Cross-Tenant Booking Lookup** | Tenant A queries `/api/bookings/B2` belonging to Tenant B | Returned booking details | `BookingService` checks tenant ID; returns HTTP 404/Empty | **BLOCKED** |
| **Direct Cross-Tenant Payment Lookup** | Tenant A queries `/api/payments/case/CASE-B2` | Returned VCC token and amounts | `PaymentController` checks case ownership; returns HTTP 404 | **BLOCKED** |
| **Admin Outbox Event Sniffing** | Tenant A admin queries `/api/admin/outbox/events` | Returned all tenant events | Scoped via `findByTenantId` | **BLOCKED** |
| **Admin Saga Sniffing** | Tenant A admin queries `/api/admin/sagas` | Returned all tenant sagas | Scoped via `findByTenantId` | **BLOCKED** |
| **Thread Context Leakage** | Worker thread reused across multiple requests | Leaked previous tenant ID | `try ... finally { TenantContext.clear(); }` cleans ThreadLocal | **PREVENTED** |
