# STAGE 13: Security & RBAC Validation Specification

**System:** Flyo Rescue Agent — Airline OCC Operations Console  
**Milestone:** Stage 13 Production OCC Transformation  
**Status:** COMPLETE & VERIFIED  

---

## 1. Enterprise Security Posture & Compliance Framework

The Flyo Rescue OCC console enforces defense-in-depth principles across authentication, authorization, cryptographic verification, financial risk isolation, and telemetry protection.

### Compliance Standards Addressed:
1. **PCI-DSS Level 1 Isolation:** Zero raw Virtual Credit Card PANs stored in plaintext or exposed to front-end browser DOM. All cards masked (`•••• •••• •••• 8821`).
2. **Airline OCC Duty-of-Care & Audit Integrity:** Every decision, ranking rationale, and operator approval is cryptographically committed with SHA-256 signatures to the immutable audit log.
3. **Statutory Non-Authoritative AI Policy:** Machine learning delay predictions cannot autonomously book tickets or modify PNRs without deterministic verification and human authorization.

---

## 2. Role-Based Access Control (RBAC) Matrix

| OCC Console Surface / Action | `ROLE_ADMIN` | `ROLE_OPERATOR` | `ROLE_AUDITOR` | Anonymous / Unauthenticated |
| :--- | :---: | :---: | :---: | :---: |
| **Trip Monitor View** (`#view-monitor`) | Read / Write | Read / Write | Read-Only | Denied (HTTP 401) |
| **Search & Filter Manifest** | Granted | Granted | Granted | Denied (HTTP 401) |
| **Inspect Incident Recovery** (`#view-ananya`) | Granted | Granted | Read-Only | Denied (HTTP 401) |
| **Approve Rebooking Option** (`/api/cases/{id}/approve`) | Granted | Granted | Denied (HTTP 403) | Denied (HTTP 401) |
| **Execute Automated Rebooking** (`/api/cases/{id}/rebook`) | Granted | Granted | Denied (HTTP 403) | Denied (HTTP 401) |
| **Reject & Escalate Incident** (`/api/cases/{id}/reject`) | Granted | Granted | Denied (HTTP 403) | Denied (HTTP 401) |
| **Operations Control View** (`#view-ops`) | Full Access | Operational Only | Read-Only | Denied (HTTP 401) |
| **Simulated Airline Breakdown Toggle** (`/mock-airline/toggle-break`)| Granted | Denied (HTTP 403) | Denied (HTTP 403) | Denied (HTTP 401) |
| **ML Inference Playground** (`/api/intelligence/predict`) | Granted | Denied (HTTP 403) | Denied (HTTP 403) | Denied (HTTP 401) |
| **Ground Truth Ingestion** (`/api/intelligence/outcomes`) | Granted | Denied (HTTP 403) | Denied (HTTP 403) | Denied (HTTP 401) |
| **Audit Trail Ledger** (`#view-logs`) | Full Access | Read-Only | Full Access | Denied (HTTP 401) |
| **Contextual 2FA Drawer** (`#operator-drawer`) | Granted | Granted | Denied (HTTP 403) | Denied (HTTP 401) |

---

## 3. Cryptographic Verification & Token Security

- **JWT Session Tokens:** Operator sessions are secured with RFC 7519 HMAC-SHA256 signed JWTs with short-lived TTLs (60 minutes).
- **Outbox Event Cryptographic Signatures:** Every state mutation produces an immutable outbox record carrying a SHA-256 payload checksum and aggregate version number.
- **Fail-Closed External Integration Security:** All external adapters (Amadeus NDC, Meta WhatsApp Cloud API, Stripe Issuing Sandbox) fail closed with explicit `ProviderNotConfiguredException` when valid credentials are absent. No unverified external requests are permitted.

---

## 4. Front-End DOM & Secret Sanitization

The production build pipeline (`test.mjs` and `build.mjs`) automatically scans all compiled static artifacts:
- **0 Hardcoded Secrets:** Confirmed 0 API keys, bearer tokens, or unencrypted secrets in `app.js` and distribution bundles.
- **XSS & Injection Protection:** All dynamic user inputs, passenger names, flight numbers, and GDS error messages are strictly escaped using `escapeHtml()` prior to DOM interpolation.
- **AST Integrity:** Full ECMAScript AST parse validation guarantees no runtime evaluation hazards (`eval` or `Function` constructors banned).
