# SECURITY FINDINGS & MITIGATION AUDIT — FLYO RESCUE AGENT

**Audit Date:** November 5, 2026  
**Target:** Flyo Rescue Agent (`com.flyo.rescue`)  
**Standard:** OWASP Top 10 API Security / PCI-DSS Virtual Card Safeguards  

---

## 1. Summary of Findings

| ID | Title | Severity | Status | Area |
| :--- | :--- | :---: | :---: | :--- |
| **SEC-01** | Unmasked Meta Webhook Verification Token in Logs | **HIGH** | **RESOLVED** | Logging / Credential Disclosure |
| **SEC-02** | Unsanitized Airport Code Parameter in REST Ingress | **MEDIUM** | **RESOLVED** | Input Validation / Injection Guard |
| **SEC-03** | Public REST API Endpoints in Development Profile | **MEDIUM** | **DOCUMENTED** | Authorization & Access Control |
| **SEC-04** | PCI-DSS VCC Card Data Masking Verification | **LOW** | **PASS / COMPLIANT** | Data at Rest & In-Flight |

---

## 2. Detailed Findings & Remediations

### Finding SEC-01: Unmasked Meta Webhook Verification Token in Logs
- **Severity:** **HIGH** (CVSS: 7.5)
- **File Reference:** [WhatsAppWebhookController.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/controller/WhatsAppWebhookController.java#L173-L180)
- **Reproduction Steps:**
  1. Trigger GET request: `curl "http://localhost:8080/api/whatsapp/webhook?hub.mode=subscribe&hub.challenge=12345&hub.verify_token=MY_SECRET_TOKEN"`
  2. Inspect server application logs.
- **Actual Behavior:** The `verifyToken` query parameter was logged in cleartext:  
  `[Meta Webhook Verification] Mode: subscribe, Token: MY_SECRET_TOKEN, Challenge: 12345`
- **Expected Behavior:** Secret tokens must be masked or omitted from application logs to prevent credential leakage to log aggregators.
- **Remediation Applied:** Introduced `maskToken(String token)` helper which masks sensitive tokens (e.g. `MY****EN`), protecting credentials from log disclosure.

---

### Finding SEC-02: Unsanitized Airport Code Parameter in Weather Endpoint
- **Severity:** **MEDIUM** (CVSS: 5.3)
- **File Reference:** [OpenMeteoWeatherService.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/OpenMeteoWeatherService.java#L80-L88)
- **Reproduction Steps:**
  1. Trigger GET request with special characters: `GET /api/weather/<script>alert(1)</script>`
- **Actual Behavior:** Arbitrary raw strings were accepted and propagated to internal coordinates map lookup.
- **Expected Behavior:** Input should be strictly sanitized to alphanumeric 3–4 letter IATA/ICAO airport codes.
- **Remediation Applied:** Added regex filtering `rawAirportCode.trim().toUpperCase().replaceAll("[^A-Z0-9]", "")` with fallback to default airport if malformed.

---

### Finding SEC-03: Public REST API Endpoints in Development Profile
- **Severity:** **MEDIUM** (CVSS: 6.0)
- **File Reference:** [SecurityConfig.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/SecurityConfig.java#L52-L62)
- **Description:** For interactive demonstration and testing, endpoints under `/api/cases/**` and `/api/bookings/**` permit unauthenticated access.
- **Production Recommendation:** For production multi-tenant deployment:
  1. Restrict `/api/cases/**` to authenticated airline operations agents using Spring Security `@PreAuthorize("hasRole('OPERATOR')")`.
  2. Maintain HMAC-SHA256 signature validation on webhook ingress (`/api/whatsapp/webhook`).

---

### Finding SEC-04: PCI-DSS Virtual Credit Card (VCC) Protection Verification
- **Severity:** **LOW / Informational** (PCI-DSS Scope)
- **File Reference:** [SandboxPaymentAdapter.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/payment/SandboxPaymentAdapter.java#L85-L126)
- **Verification:** Audited all payment logging and model serialization. VCC card numbers (PAN) and CVVs are strictly held in transient memory during Playwright execution and are **never** logged to disk. Log output records only opaque tokens (e.g. `VCC-2646DBE0`) and merchant category code locks (`MCC 3000`).
- **Status:** Fully compliant with single-use corporate issuing standards.
