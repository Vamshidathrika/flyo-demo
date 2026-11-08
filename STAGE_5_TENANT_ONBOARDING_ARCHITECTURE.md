# FLYO STAGE 5 — MULTI-TENANT CLIENT ONBOARDING & ISOLATION ARCHITECTURE

**Classification:** Enterprise Multi-Tenant Architecture & Client Onboarding Runbook  
**Target:** Client Provisioning, Credential Isolation, Policy Customization, and Adversarial Verification  
**Scope:** `TenantContext`, `TenantFilter`, `RescueCaseRepository`, `DistributedRateLimiter`  
**Status:** Certified Architecture & Adversarially Verified  
**Date:** November 2026  

---

## 1. Multi-Tenant Architectural Model

Flyo employs a **Shared Process / Shared Database with Column-Level Discriminator and Context-Enforced Isolation** architecture. Every tenant's operational data (`bookings`, `rescue_cases`, `virtual_cards`, `payment_transactions`, `outbox_events`, `saga_executions`, `audit_events`) is partitioned by `tenant_id`.

To prevent cross-tenant data leakage:
1. **ThreadLocal Tenant Context:** `TenantContext.getTenantId()` holds the active tenant identity, populated by `TenantFilter` from validated JWT claims (`claims.get("tenantId")`).
2. **Fail-Closed Unauthenticated Access:** If an unauthenticated request attempts to inject or spoof the `X-Tenant-ID` header, `TenantFilter` logs a security alert and rejects the request with HTTP 401/403.
3. **Repository-Level Tenant Isolation:** Every query method enforces `tenant_id = :tenantId`. Searching for a non-existent or foreign tenant ID returns `Optional.empty()` resulting in HTTP 404 (Fail Closed).
4. **Secret Isolation via Vault References:** Tenant configurations store references to secret vault keys (e.g. `vault:secret/tenants/acme_corp/amadeus_key`), never raw plaintext API keys.

---

## 2. Tenant Provisioning & Operational Lifecycle

```
[ 1. PROVISIONING ] ──► Create Tenant Profile, assign Tenant ID, seed Admin User
           │
           ▼
[ 2. CREDENTIALS ]  ──► Inject GDS, CRM, & Messaging Vault References
           │
           ▼
[ 3. VERIFICATION ] ──► Execute Probe Checks (OAuth Token Exchange, Ping Test)
           │
           ▼
[ 4. TEST INGEST ]  ──► Ingest Synthetic Disruption Event (Validate Routing)
           │
           ▼
[ 5. PILOT ACTIVE ] ──► Route Real Flight Radar Telemetry (Human-in-the-loop Gate)
           │
           ├── SUSPENSION ──► Administratively mute tenant dispatches
           │
           ▼
[ 6. DEACTIVATION ] ──► Expire JWT tokens, archive cases, purge PII per retention
```

### Lifecycle State Machine
- `PROVISIONING_PENDING`: Tenant created, awaiting credential configuration.
- `CONFIGURED_UNVERIFIED`: Credentials injected into vault; awaiting connection handshake.
- `SANDBOX_VALIDATED`: Synthetic test disruption executed through full workflow.
- `PILOT_ACTIVE`: Operating under human approval supervision in partner pilot.
- `PRODUCTION_ACTIVE`: Fully certified for autonomous rescue operations.
- `SUSPENDED`: Temporarily halted due to SLA breaches, rate limits, or billing hold.
- `DEACTIVATED`: Offboarded with data retention purge schedule initiated.

---

## 3. Per-Tenant Configurable Governance

Each enterprise client controls an isolated policy profile:

| Governance Policy | Configuration Scope | Default Value | Pilot Customization |
| :--- | :--- | :--- | :--- |
| **Spending Cap per Disruption** | Corporate Treasury limit per passenger | ₹50,000 INR | Client corporate travel budget |
| **MCT Safety Threshold** | Domestic $\to$ International transfer buffer | 120 minutes | Configurable (90–180m) |
| **Supported Carriers** | Approved rebooking airline codes | `["AI", "6E", "UK"]` | Client preferred carrier list |
| **Max Auto-Retry Limit** | Saga retry attempts on transient error | 3 attempts | 1–5 attempts |
| **Passenger Notification Channel** | WhatsApp vs Email vs SMS | WhatsApp Meta Cloud | Configurable by employee profile |
| **Rate Limit Quota** | Webhook & API ingress requests per min | 100 req/min | Tiered commercial quota |

---

## 4. Adversarial Cross-Tenant Security Verification

Flyo's multi-tenant isolation was validated through 9 adversarial attack scenarios in `Stage3EnterpriseSecurityTest`:

1. **Cross-Tenant Case Reading Blocked:** Operator Alpha attempting `GET /api/cases/{caseId_Beta}` receives `404 Not Found`.
2. **Cross-Tenant Booking Access Blocked:** Operator Alpha attempting `GET /api/bookings/{bookingId_Beta}` receives `404 Not Found`.
3. **Cross-Tenant Payment / VCC Blocked:** Admin Alpha attempting `GET /api/payments/case/{caseId_Beta}` receives `404 Not Found`.
4. **Cross-Tenant Saga Inspection Blocked:** Operator Alpha attempting `GET /api/admin/sagas/{sagaId_Beta}` receives `404 Not Found`.
5. **Cross-Tenant Outbox Isolation:** Querying `/api/admin/outbox/events` strictly filters events matching caller's authenticated `tenant_id`.
6. **Tenant Header Spoofing Blocked:** Unauthenticated requests supplying `X-Tenant-ID: tenant_victim` are blocked at the filter level.
7. **Unauthorized Tenant Selection Blocked:** A user possessing tokens for `tenant_alpha` attempting to switch header to `tenant_beta` is rejected.
