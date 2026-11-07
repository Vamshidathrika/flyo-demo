# FLYO — STAGE 3 CLIENT ONBOARDING CHECKLIST
## End-to-End Enterprise Pilot Deployment & Integration Checklist

**Client Organization:** ________________________________________  
**Target Tenant Identifier:** `tenant_`__________________________  
**Lead Forward Deployment Engineer:** ___________________________  
**Client Technical Sponsor:** ___________________________________  

---

## 1. Identity & Multi-Tenant Partitioning

- [ ] **Tenant Provisioning**: Generate unique tenant identifier and verify compound database indexes (`V7` schema applied).
- [ ] **Identity Provider (IdP) Integration**:
  - [ ] Configure client OIDC / SAML 2.0 or provision dedicated operator credentials with salted bcrypt (cost factor 12).
  - [ ] Configure RBAC roles: `ROLE_OPERATOR`, `ROLE_SUPERVISOR`, `ROLE_ADMIN`, `ROLE_AUDITOR`.
  - [ ] Bind operator accounts strictly to `tenant_id` and define authorized tenant switching lists.
- [ ] **Zero-Trust Header Verification**: Execute adversarial test confirming that attempts to spoof `X-Tenant-ID` return HTTP 403 Forbidden.

---

## 2. External Provider Sandbox Connectivity

- [ ] **Flight Telemetry Feed**:
  - [ ] Configure client Aviationstack API key or direct AIDX / AODB broker connection.
  - [ ] Verify test flight polling and time-zone conversion.
- [ ] **GDS / PSS Airline Integration**:
  - [ ] Connect Amadeus Altéa test Office ID or Sabre PCC staging credentials.
  - [ ] Test PNR retrieval for simulated test bookings.
  - [ ] Verify that flight availability queries reflect client contracted fare classes.
- [ ] **Corporate Payment / Treasury**:
  - [ ] Configure Stripe Issuing sandbox test secret (`sk_test_...`) or client bank virtual card sandbox.
  - [ ] Validate single-use spending limit enforcement and MCC travel category restrictions.
  - [ ] Confirm zero PAN / CVV persistence in database and application logs.
- [ ] **Passenger Messaging Gateway**:
  - [ ] Register test phone numbers in Meta WhatsApp Business Account (WABA).
  - [ ] Configure inbound webhook URL and verify Meta App Secret HMAC-SHA256 signature checking.
  - [ ] Verify outbound template delivery and passenger decision card interaction.
- [ ] **CRM / Service Cloud**:
  - [ ] Configure Salesforce Connected App with scoped OAuth access.
  - [ ] Test bidirectional sync or one-way incident push from outbox worker.

---

## 3. Network, Security & Operational Reliability

- [ ] **mTLS & Egress Firewall Rules**:
  - [ ] Whitelist outbound egress IPs to provider API gateways.
  - [ ] Configure TLS 1.3 encryption on all external connections.
- [ ] **Rate Limiting Policies**:
  - [ ] Verify shared database table `shared_rate_limits` is active.
  - [ ] Confirm client IP reverse-proxy headers (`X-Forwarded-For`) are trusted only from configured ingress CIDRs.
- [ ] **Transactional Outbox & Saga Workers**:
  - [ ] Configure worker poll interval and lease recovery timeouts (default 120s).
  - [ ] Test simulated worker crash and verify lease reclamation without duplicate dispatch.
- [ ] **Audit Trail & Compliance**:
  - [ ] Confirm append-only audit logging for all consequential actions.
  - [ ] Verify human approval requirement is active and cannot be bypassed by automated bots or ML models.
