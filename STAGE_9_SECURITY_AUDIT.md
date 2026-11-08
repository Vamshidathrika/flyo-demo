# FLYO RESCUE — STAGE 9: PRODUCTION SECURITY AUDIT & ENVIRONMENT SEPARATION

## 1. Overview
The security architecture enforces strict three-environment separation: **Development**, **Testing**, and **Production**.

---

## 2. Production Security Validator (`ProductionSecurityValidator.java`)
Runs immediately upon Spring Boot startup under the `prod` profile:
1. **Seeding Lockout**: Asserts `flyo.seeding.enabled=false`.
2. **Radar Mock Lockout**: Asserts `aviation.radar.mock-mode=false`.
3. **Provider Rejection**: Rejects `airline.booking.provider` values of `MOCK`, `PLAYWRIGHT`, or `SIMULATED`.
4. **Payment Gateway Rejection**: Rejects `payment.vcc.provider` values of `MOCK` or `FAKE`.
5. **Secret Hygiene**: Rejects default JWT secrets (`secret`, `password`, `changeme`) and requires min 32-byte signing keys.

---

## 3. First-Time Admin Bootstrap Security (`ProductionAdminBootstrap.java`)
- **Policy**: In production, static demo accounts (e.g., `admin/admin123`) are forbidden.
- **Bootstrap Mechanism**: Admin accounts can only be provisioned at boot via explicit environment variables:
  - `FLYO_INITIAL_ADMIN_USERNAME`
  - `FLYO_INITIAL_ADMIN_PASSWORD`
- **Validation Rules**:
  - Rejects default passwords: `"admin"`, `"password"`, `"admin123"`, `"secret"`, `"flyo123"`.
  - Requires minimum length of 12 characters.
  - If environment variables are absent, the platform boots with 0 accounts, forcing operator provisioning via secure CLI or SSO identity federation.

---

## 4. Multi-Tenant Isolation & Rate Limiting
- **Tenant Filter**: Enforces tenant boundary checks on every REST request. Cross-tenant reads and mutations are denied with `403 Forbidden`.
- **Distributed Sliding-Window Rate Limiting**: Protects rebooking endpoints and WhatsApp webhook ingestion from volumetric DDoS and brute-force attacks.
