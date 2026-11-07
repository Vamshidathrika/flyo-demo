# STAGE 7 — INDEPENDENT APPLICATION SECURITY & PENETRATION AUDIT

## 1. Executive Summary
An independent security assessment was performed against Flyo's production deployment profile, targeting the OWASP API Security Top 10, cryptographic controls, multi-tenant boundaries, and input sanitization.

## 2. Security Test Findings & Verifications

| Test Category | Target Vector | Expected Defense | Observed Behavior | Verdict |
| :--- | :--- | :--- | :--- | :---: |
| **OWASP API 1: BOLA / IDOR** | Cross-tenant access to `/api/cases/{caseId}` and `/api/payments/case/{caseId}` | Fail-closed HTTP 404 (prevent ID enumeration) | Blocked with HTTP 404 across all tested tenant combinations. | **PASS** |
| **OWASP API 2: Broken Authentication** | Expired JWT, tampered signature bytes | HTTP 401 Unauthorized | JWT signature validation failed; expired tokens rejected. | **PASS** |
| **OWASP API 3: Broken Object Property** | Tenant header spoofing (`X-Tenant-ID`) by untrusted callers | HTTP 403 Forbidden | Blocked with HTTP 403 when header does not match authenticated token. | **PASS** |
| **OWASP API 4: Rate Limiting** | Rapid booking dispatch bursts | HTTP 429 Too Many Requests | Throttled by distributed rate limiter after threshold exceeded. | **PASS** |
| **OWASP API 7: Security Misconfig** | Production profile with H2 or default secrets | Startup abortion (`IllegalStateException`) | `ProductionSecurityValidator` aborted boot when H2 URL detected. | **PASS** |
| **OWASP API 8: Injection (SQLi)** | `' OR 1=1 --` and `UNION SELECT` in search endpoints | Prepared statements via JPA/Hibernate | Query safely parameterized; 0 unescaped rows returned. | **PASS** |
| **OWASP API 9: SSRF** | User-supplied callback URLs | Pinned external client endpoints | All HTTP clients communicate exclusively with pinned host configurations. | **PASS** |
| **Webhook Signature Forgery** | Forged `X-Hub-Signature-256` on WhatsApp endpoints | HMAC-SHA256 verification | Invalid signature rejected with HTTP 403 Forbidden. | **PASS** |
| **PCI DSS Data Containment** | Storing CVV or plaintext PAN | Zero storage of raw cardholder data | Only tokenized `cardTokenId` persisted; CVV never stored. | **PASS** |

## 3. Dependency Vulnerability Status
- Core Framework: Spring Boot 3.3.4 (supported LTS baseline).
- Data Access: Hibernate 6.5.3.Final, HikariCP 5.1.0, PostgreSQL JDBC 42.7.4.
- Security: Spring Security 6.3.3, JJWT 0.12.6.
- Zero high or critical CVE vulnerabilities identified in active runtime dependency tree.
