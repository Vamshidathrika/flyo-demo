# FLYO — SECURITY, PRIVACY & COMPLIANCE AUDIT

**Date:** November 6, 2026  
**Auditor:** Principal Security Architect, Compliance Specialist & Penetration Tester  
**Repository:** `/Users/nani/Downloads/flyo`  
**Operating Mode:** STRICT AUDIT ONLY — Zero code or configuration modifications performed.

---

## 1. Executive Security Verdict

### **VERDICT: CRITICAL SECURITY & REGULATORY FAILURE**

Flyo currently exhibits a **dangerously porous security posture**. While it possesses the outward appearance of an enterprise Spring Boot application with Spring Security dependencies included in `pom.xml`, the internal configuration systematically disables standard defenses.

Any deployment of this repository to a public IP, cloud virtual machine, or shared enterprise network would represent an **immediate, catastrophic breach risk**:
- **Zero Authentication:** Every administrative and financial endpoint is publicly accessible without login.
- **Unencrypted Payment SAD:** Card verification values (CVVs) are written to persistent relational tables.
- **Open Administrative Ports:** Embedded database consoles (`/h2-console`) and actuator endpoints are exposed to remote traffic.
- **Default Hardcoded Secrets:** Production configuration files fall back to predictable hardcoded tokens.

---

## 2. Security Vulnerability Matrix

| ID | Vulnerability Description | File / Location | Severity | CVSS v3.1 | Status |
| :-: | :--- | :--- | :-: | :-: | :-: |
| **SEC-01** | **Universal `permitAll()` Access on All Endpoints** | `SecurityConfig.java:35-64` | **CRITICAL** | **9.8** | **ACTIVE** |
| **SEC-02** | **Plaintext Storage of CVV & PAN (PCI-DSS 3.2 Violation)** | `V1__initial_production_schema.sql:80-84` | **CRITICAL** | **9.1** | **ACTIVE** |
| **SEC-03** | **Unauthenticated Remote H2 Console Enabled** | `application.properties:21-23` | **CRITICAL** | **9.8** | **ACTIVE** |
| **SEC-04** | **Permissive Wildcard CORS with Credentials Enabled** | `ml/service/main.py:80-86` | **HIGH** | **8.1** | **ACTIVE** |
| **SEC-05** | **Hardcoded Webhook Secrets & API Keys** | `application.properties:38-39` | **HIGH** | **7.5** | **ACTIVE** |
| **SEC-06** | **Disabled CSRF & XSS Header Protections** | `SecurityConfig.java:27, 31` | **HIGH** | **7.4** | **ACTIVE** |
| **SEC-07** | **Unprotected Sensitive Actuator Probes** | `application.properties:26` | **MEDIUM** | **6.5** | **ACTIVE** |
| **SEC-08** | **Unencrypted Passenger PII Storage** | `bookings`, `rescue_cases` tables | **HIGH** | **7.5** | **ACTIVE** |

---

## 3. Detailed Technical Breakdown of Vulnerabilities

### SEC-01: Universal `permitAll()` on All REST Endpoints (CRITICAL)
In `SecurityConfig.java`:
```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers(
        "/", "/index.html", "/style.css", "/app.js", "/favicon.ico",
        "/screenshots/**", "/mock-airline/**", "/h2-console/**",
        "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    ).permitAll()
    .requestMatchers(
        "/api/whatsapp/**", "/api/alerts/**", "/api/ingest/**",
        "/api/bookings/**", "/api/cases/**", "/api/payments/**",
        "/api/settlement/**", "/api/weather/**", "/api/intelligence/**"
    ).permitAll()
    .anyRequest().authenticated()
);
```
- **Finding:** Every URL pattern in the application is explicitly listed under `.permitAll()`. The final `.anyRequest().authenticated()` rule is completely unreachable.
- **Exploit Path:** An unauthenticated remote attacker can trigger flight disruptions (`POST /api/cases/disrupt`), approve rebookings (`POST /api/cases/{id}/approve`), issue virtual credit cards (`POST /api/payments/vcc/issue`), and clear audit logs (`POST /api/whatsapp/clear`).

### SEC-02: Plaintext Storage of CVV and Card PAN (CRITICAL)
In `V1__initial_production_schema.sql`:
```sql
CREATE TABLE IF NOT EXISTS virtual_cards (
    ...
    card_number VARCHAR(32) NOT NULL,
    expiry VARCHAR(16) NOT NULL,
    cvv VARCHAR(32) NOT NULL,
    ...
);
```
- **Finding:** Card Verification Values (CVV) are persisted in the database table `virtual_cards`.
- **Regulatory Impact:** Direct violation of **PCI-DSS Requirement 3.2** (Prohibition against storing Sensitive Authentication Data post-authorization). This renders any organization operating Flyo ineligible for merchant processing and subject to severe card brand fines ($5,000–$100,000/month).

### SEC-03: Unauthenticated Remote H2 Console (CRITICAL)
In `application.properties`:
```properties
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console
spring.h2.console.settings.web-allow-others=true
```
- **Finding:** `web-allow-others=true` instructs H2 to accept database connections from remote IP addresses. Coupled with `SecurityConfig`'s `.permitAll()`, any network user can access `/h2-console` and execute arbitrary SQL against `jdbc:h2:mem:flyodb`.

### SEC-04: Permissive Wildcard CORS with Credentials (HIGH)
In `ml/service/main.py`:
```python
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
```
- **Finding:** In FastAPI / Starlette, combining `allow_origins=["*"]` with `allow_credentials=True` allows malicious third-party websites visited by an operator to issue authenticated cross-origin requests to the inference microservice.

### SEC-05: Hardcoded Default Secrets (HIGH)
In `application.properties`:
```properties
whatsapp.meta.verify-token=${WHATSAPP_VERIFY_TOKEN:flyo_whatsapp_verify_token_2026}
whatsapp.meta.app-secret=${WHATSAPP_APP_SECRET:flyo_meta_app_secret_test_key_2026}
flyo.ml.monitoring.api-key=${FLYO_MONITORING_API_KEY:flyo-monitoring-secret-key-2026}
```
- **Finding:** If environment variables are omitted in production, the application silently boots using public default keys present in GitHub source code. Any attacker can forge Meta webhook payloads using the known HMAC secret.

### SEC-06: Disabled CSRF and XSS Protections (HIGH)
In `SecurityConfig.java`:
```java
http.csrf(csrf -> csrf.disable())
    .headers(headers -> headers
        .xssProtection(xss -> xss.disable())
        ...
    );
```
- **Finding:** Disabling CSRF is only acceptable when all endpoints require cryptographic Bearer tokens (JWT). Because Flyo's endpoints require no tokens and permit session cookies, the application is vulnerable to Cross-Site Request Forgery.

---

## 4. Privacy, PII & Regulatory Compliance Audit

### 4.1 Passenger PII Protection (GDPR / India Digital Personal Data Protection Act 2023)
- **Data Elements Stored:** Full passenger legal names, direct mobile numbers, flight itineraries, downstream connecting flights, and hotel reservation notes.
- **Audit Deficiencies:**
  1. No column-level encryption for phone numbers or passenger names in PostgreSQL.
  2. No role-based data masking (a frontline agent sees the same raw phone numbers as an administrator).
  3. No Consent Ledger recording passenger opt-in for automated WhatsApp notifications.
  4. No Data Subject Access Request (DSAR) or Right-to-Erasure API endpoints.

### 4.2 Applicable Aviation & Compliance Frameworks Requiring Specialist Review
1. **PCI-DSS 4.0:** Mandatory audit before processing any virtual commercial cards.
2. **India DPDP Act 2023 / EU GDPR:** Mandatory Data Protection Impact Assessment (DPIA) for processing passenger tracking data.
3. **DGCA Passenger Charter (India) / US DOT 14 CFR Part 259:** Regulatory compliance review for automated compensation calculations, refund rules, and rebooking fare caps.
