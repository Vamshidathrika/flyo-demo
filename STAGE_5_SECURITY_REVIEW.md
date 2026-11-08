# FLYO STAGE 5 — SECURITY AND PRIVACY REVIEW

**Document ID:** `FLYO-STG5-SEC-010`  
**Classification:** Enterprise Security Architecture Assessment  
**Application:** Flyo Rescue System (`flyo-rescue-demo`)  
**Date:** November 2026  
**Auditor:** Principal Security & Reliability Architect  

---

## 1. Executive Summary

This document details an exhaustive, evidence-based security and privacy audit of the Flyo passenger rescue platform. Every inbound, outbound, persistence, and inter-service integration surface added or hardened in Stage 5 was audited against the **OWASP API Security Top 10 (2023)**, **NIST SP 800-53 Rev. 5**, and industry-standard data privacy regulations (GDPR / CCPA / PCI DSS data boundaries).

### Security Posture Summary
- **Authentication:** Fail-closed JWT Bearer token authentication with HMAC-SHA256 (minimum 256-bit entropy enforced at startup).
- **Tenant Isolation:** Cryptographically enforced multi-tenancy via [TenantFilter.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/security/TenantFilter.java) with complete rejection of cross-tenant privilege elevation.
- **Webhook Integrity:** Constant-time cryptographic HMAC verification on incoming webhooks (Meta WhatsApp X-Hub-Signature-256 and Stripe-Signature v1 with 300-second replay window).
- **Injection Prevention:** 100% Spring Data JPA / Hibernate parameterized queries; zero string-concatenated SQL across all repositories.
- **SSRF Hardening:** Outbound integration clients restricted to strict whitelist of provider domains with DNS resolution checks.
- **Payment & PII Boundary:** Zero CVV retention; primary account numbers (PAN) masked to last 4 digits; BCrypt-12 password hashing; sanitized API error disclosure.

---

## 2. Inbound & Outbound Authentication Handling

### Inbound API Authentication
- **Mechanism:** HTTP Authorization header with `Bearer <token>` scheme.
- **Implementation:** [JwtAuthenticationFilter.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/security/JwtAuthenticationFilter.java) and [JwtTokenProvider.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/security/JwtTokenProvider.java).
- **Cryptographic Scheme:** HMAC-SHA256 (`HS256`) signed with a 256-bit minimum key. Algorithm agility is strictly constrained to prevent `none` algorithm or key confusion vulnerabilities.
- **Issuer & Audience Validation:**
  - Token issuer must strictly match `flyo-auth`.
  - Audience claim must include `flyo-rescue-api`.
  - Expiration timestamp (`exp`) and not-before (`nbf`) claims strictly validated with a 2-second skew window.
- **Production Startup Gate:** [ProductionSecurityValidator.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/ProductionSecurityValidator.java) terminates the JVM on boot in `prod` profile if `JWT_SECRET` is missing, contains dummy/test keywords, or provides fewer than 32 bytes (256 bits) of entropy.

### Outbound Provider Authentication
- **Amadeus GDS REST Client:** Implements standard OAuth2 Client Credentials grant (`https://test.api.amadeus.com/v1/security/oauth2/token`). Access tokens are cached in-memory with a 60-second proactive expiration buffer. Credentials (`apiKey`, `apiSecret`) are injected via environment variables and never logged or exposed via API.
- **Meta WhatsApp Cloud API:** Bearer token authorization via `Authorization: Bearer ${WHATSAPP_ACCESS_TOKEN}`.
- **Salesforce Service Cloud:** OAuth2 Client Credentials grant requesting standard bearer tokens.
- **Stripe Treasury/Issuing:** Secret key bearer authorization via `Authorization: Bearer ${STRIPE_SECRET_KEY}`.

---

## 3. Object-Level Authorization & Tenant Boundary Enforcement (BOLA / IDOR)

### Threat Model: Broken Object Level Authorization (OWASP API1:2023)
In a multi-tenant airline rescue platform, an operator from Airline A (e.g., `tenant_indigo`) must never view, approve, rebook, or tamper with cases, bookings, or payments belonging to Airline B (e.g., `tenant_delta`).

### Mitigation Architecture
1. **Authoritative Context Binding:**
   The authenticated user principal ([FlyoUserPrincipal.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/security/FlyoUserPrincipal.java)) contains the cryptographically verified `tenantId` and the set of `authorizedTenants`.
2. **Untrusted Header Validation:**
   The `X-Tenant-ID` HTTP header is treated as an **untrusted selection request**. In [TenantFilter.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/security/TenantFilter.java), if `X-Tenant-ID` is passed, the filter checks `principal.isTenantAuthorized(requestedTenant)`. If unauthorized, the request is immediately aborted with HTTP 403 Forbidden before reaching controllers or data stores.
3. **Database Scoping:**
   All JPA queries on `DisruptionCaseRepository`, `BookingReservationRepository`, and `PaymentTransactionRepository` enforce `tenant_id` predicate binding.
4. **Thread-Safe Cleanup:**
   `TenantContext.clear()` is executed inside a mandatory `finally` block in `TenantFilter.java`, guaranteeing zero thread-local context leakage across Tomcat worker threads.

---

## 4. Webhook Cryptographic Verification & Replay Prevention

### Meta WhatsApp Webhook Security
- **Header:** `X-Hub-Signature-256: sha256={hex_digest}`
- **Algorithm:** HMAC-SHA256 computed over raw UTF-8 request payload using Meta App Secret.
- **Implementation:** [MetaWhatsAppSignatureValidator.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/webhook/signature/MetaWhatsAppSignatureValidator.java).
- **Constant-Time Comparison:** Uses `MessageDigest.isEqual()` to prevent timing side-channel attacks.

### Stripe Webhook Security & Replay Prevention
- **Header:** `Stripe-Signature: t={timestamp},v1={hex_signature}`
- **Implementation:** [StripeSignatureValidator.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/webhook/signature/StripeSignatureValidator.java).
- **Replay Window:** Strictly enforces `|Instant.now().getEpochSecond() - t| <= 300 seconds` (5 minutes). Timestamps older than 5 minutes or in the future are rejected.
- **Signed Payload:** Computed over `${timestamp}.${rawPayload}` using `HmacSHA256`.

---

## 5. SSRF (Server-Side Request Forgery) & Outbound HTTP Security

### Threat Model (OWASP API7:2023)
Outbound webhooks or external provider dispatch could be manipulated to query internal cloud metadata services (`http://169.254.169.254/latest/meta-data/`) or private VPC infrastructure (`http://10.0.0.0/8`, `http://192.168.0.0/16`).

### Mitigations
1. **Strict Endpoint Allowlisting:**
   External endpoints for GDS, WhatsApp, Salesforce, and Open-Meteo are strictly bound to configurable base URLs with compile-time or deployment-time allowlist patterns:
   - `test.api.amadeus.com` / `api.amadeus.com`
   - `graph.facebook.com`
   - `login.salesforce.com` / `*.my.salesforce.com`
   - `api.open-meteo.com`
   - `api.stripe.com`
2. **Private Address Blocking:**
   Outbound HTTP clients block connection attempts resolving to `127.0.0.1`, `localhost`, `169.254.169.254`, or RFC 1918 private IP subnets in non-dev environments.
3. **Connection & Read Timeouts:**
   All outbound `RestTemplate` and HTTP clients enforce explicit 5000ms connection timeouts and 10000ms socket read timeouts to prevent slowloris starvation.

---

## 6. SQL Injection Prevention & Data Access

- **Audit Finding:** Audited all database access layers:
  - `DisruptionCaseRepository.java`
  - `BookingReservationRepository.java`
  - `PaymentTransactionRepository.java`
  - `OperatorAccountRepository.java`
  - `FlightRadarStateRepository.java`
  - `WebhookEventRepository.java`
- **Result:** Zero occurrences of raw string concatenation (`"SELECT ... WHERE id = '" + id + "'"`). All dynamic queries utilize Spring Data JPA derived queries, `@Query` annotations with named parameters (`:caseId`, `:tenantId`), or Criteria API with strict typing.

---

## 7. CORS Configuration & Security Headers

### CORS Policy
- Configured in [SecurityConfig.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/SecurityConfig.java#L184-L199):
  - Strict origin pattern restriction (`http://localhost:[*]`, `http://127.0.0.1:[*]`). Wildcard `*` origins with credentials are explicitly prohibited.
  - Allowed headers: `Authorization`, `Content-Type`, `Accept`, `X-Requested-With`, `X-Flyo-Monitoring-Key`.
  - Exposed headers: `Authorization`.
  - Pre-flight max age: 3600 seconds.

### HTTP Response Security Headers
- **Content-Security-Policy (CSP):** `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'`
- **Frame Options:** `DENY` in production (prevents clickjacking attacks); `SAMEORIGIN` only in `dev`/`test` for H2 console.
- **Referrer Policy:** `STRICT_ORIGIN_WHEN_CROSS_ORIGIN`.
- **Content Type Options:** `nosniff`.

---

## 8. Sensitive Data Handling & Privacy (PII / PCI DSS Boundaries)

### PII Protection
- **Passenger PII:** Names, email addresses, phone numbers, and passport numbers are collected only for booking re-issuance and WhatsApp notifications.
- **Logging Sanitation:**
  - Phone numbers are masked in application logs (`+1 ***-***-1234`).
  - Credit card numbers are never logged in full. Only `last4` and card brand are retained in [PaymentTransaction.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/model/PaymentTransaction.java).
  - CVV codes are **never** persisted to database tables, logs, or cache.
- **Retention Policy:**
  - Disruption case logs and telemetry are archived after 90 days.
  - Outbox dispatch events and webhook idempotency events are purged after 30 days.

---

## 9. Error Disclosure & Information Leakage

- **REST API Error Responses:**
  Configured exception handlers translate Java exceptions (`ProviderAuthenticationException`, `ProviderTimeoutException`, `EntityNotFoundException`) into sanitized JSON error payloads:
  ```json
  {
    "status": 401,
    "error": "Unauthorized",
    "message": "Full authentication is required to access this resource.",
    "timestamp": 1730913500000
  }
  ```
- **Information Leakage Prevention:** Stack traces, internal class names, SQL error messages, and host system paths are completely suppressed from client-facing responses in production.

---

## 10. Prioritized Security Remediation Register

| ID | Finding | Surface | Severity | Status | Remediation / Verification |
|---|---|---|---|---|---|
| **SEC-01** | Weak/Default JWT Secret in production | `JwtTokenProvider` | **CRITICAL** | **RESOLVED** | `ProductionSecurityValidator` enforces 256-bit entropy and blocks default keys at startup. |
| **SEC-02** | Cross-tenant privilege escalation (BOLA) | `TenantFilter` | **HIGH** | **RESOLVED** | Enforced strict `FlyoUserPrincipal.isTenantAuthorized()` validation before request execution. |
| **SEC-03** | Webhook signature forging & replay | `WhatsAppWebhookController` | **HIGH** | **RESOLVED** | Added `MetaWhatsAppSignatureValidator` and `StripeSignatureValidator` with 300s timestamp window. |
| **SEC-04** | CVV persistence risk | `PaymentSettlementService` | **MEDIUM** | **RESOLVED** | CVV field eliminated from data models and persistence schemas. |
| **SEC-05** | Clickjacking vulnerability | `SecurityConfig` | **MEDIUM** | **RESOLVED** | `X-Frame-Options: DENY` enforced in non-dev environments. |
| **SEC-06** | Outbound SSRF risks | Provider Adapters | **MEDIUM** | **RESOLVED** | Target domains constrained to strict allowlist with explicit socket timeouts. |
| **SEC-07** | Information leakage in error traces | Exception Handlers | **LOW** | **RESOLVED** | Sanitized uniform error JSON format with suppressed stack traces. |

---

*Report signed by Principal Security & Reliability Architect, November 2026.*
