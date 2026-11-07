# STAGE 6 — MULTI-TENANT SECURITY & ADVERSARIAL VERIFICATION

## 1. Executive Summary
Flyo is designed as a multi-tenant enterprise disruption-management platform where different airlines, corporate desks, or ground handlers operate concurrently. Stage 6 subjected the application to rigorous adversarial negative security testing covering Broken Object Level Authorization (OWASP BOLA/IDOR), cryptographic webhook tampering, tenant header spoofing, SQL injection, JWT manipulation, and sensitive information exposure.

## 2. Multi-Tenant Isolation Architecture
- **Tenant Context (`TenantContext`)**: Extracted from validated JWT claims (`tenant_id`) and anchored to thread-local execution context.
- **Header Spoofing Defense**: Unauthenticated or untrusted callers attempting to pass `X-Tenant-ID` headers that do not match their cryptographically signed JWT are rejected immediately with HTTP 403 Forbidden.
- **OWASP BOLA Defense (Fail-Closed HTTP 404)**: When a caller from Tenant Alpha queries an object (Rescue Case, Payment Ledger, Booking) belonging to Tenant Beta, the controller returns `HTTP 404 Not Found` rather than `403 Forbidden`. This eliminates ID enumeration vulnerabilities and prevents cross-tenant entity existence probing.

## 3. Cryptographic Signature & Webhook Verification
- **Meta WhatsApp Webhook (`X-Hub-Signature-256`)**: Validates HMAC-SHA256 signatures against the raw webhook body using the tenant's app secret. Forged, altered, or unsigned payloads are rejected immediately with HTTP 403 Forbidden.
- **JWT Integrity**: Rejects expired tokens, tokens with tampered signatures, or tokens where issuer and audience claims do not match Flyo's enterprise trust policy.

## 4. Adversarial Test Verification Evidence (`Stage6EnterpriseSecurityAdversarialTest.java`)
- `testCrossTenantCaseAccessBlocked()`: An operator from Tenant Alpha attempted to access Tenant Beta's rescue case via `/api/cases/{caseId}`. Result: Blocked with HTTP 404.
- `testCrossTenantPaymentLedgerAccessBlocked()`: An admin from Tenant Alpha attempted to read Tenant Beta's payment ledger. Result: Blocked with HTTP 404.
- `testUnauthenticatedTenantHeaderSpoofingRejected()`: Request with forged `X-Tenant-ID: tenant_victim` without valid auth. Result: Blocked with HTTP 403.
- `testForgedWebhookSignatureRejected()`: Meta WhatsApp webhook submitted with fake `sha256=bad_sig`. Result: Rejected with HTTP 403.
- `testSqlInjectionOnCaseFilterResistant()`: Attempted `' OR 1=1 --` and `UNION SELECT` injection via search parameters. Result: Safely parameterized via Hibernate JPA, returning 0 unescaped rows.
- `testExpiredJwtTokenRejected()`: Submitted expired JWT token. Result: Rejected with HTTP 401 Unauthorized.
- `testTamperedJwtSignatureRejected()`: Altered payload byte in cryptographically signed JWT. Result: Signature validation failed, HTTP 401 Unauthorized.
- `testRoleBasedAuthorizationDeniesOperatorFromPaymentAdminEndpoints()`: Verified standard `ROLE_OPERATOR` cannot access `/api/payments/**`. Result: HTTP 403 Forbidden.

**Result: 8/8 Adversarial Tests Passed Cleanly.**
