# FLYO — STAGE 3 PROVIDER CREDENTIAL REQUIREMENTS
## Specifications & Security Boundaries for Client-Supplied Integration Secrets

> [!CAUTION]
> **Zero Plain-Text Credential Rule**:
> Never transmit, paste, or commit real API credentials into source code, git repositories, or markdown documents. All secrets must be injected via secure environment variables, HashiCorp Vault, AWS Secrets Manager, or Kubernetes Secret resources.

---

## 1. Provider Credential Specifications

### A. Aviation Telemetry (Aviationstack / FlightAware)
- **Required Variable**: `FLYO_INTEGRATION_AVIATIONSTACK_KEY`
- **Specification**: 32-character hexadecimal API token.
- **Scope Needed**: Real-time flight tracking, airline schedule lookups.
- **Rotation Frequency**: 90 days.

### B. Global Distribution Systems (Amadeus Altéa / Sabre GDS)
- **Amadeus 1A**:
  - Variables: `FLYO_AMADEUS_CLIENT_ID`, `FLYO_AMADEUS_CLIENT_SECRET`, `FLYO_AMADEUS_OFFICE_ID`
  - Auth Flow: OAuth 2.0 Client Credentials.
  - Required Scopes: Flight Availability (`shopping`), PNR Retrieval & Servicing (`booking`).
- **Sabre GDS 1S**:
  - Variables: `FLYO_SABRE_PCC`, `FLYO_SABRE_USERNAME`, `FLYO_SABRE_PASSWORD`, `FLYO_SABRE_CERT_DOMAIN`
  - Auth Flow: Sabre Web Services Session Pooling.

### C. Payment Gateway (Stripe Issuing / B2B VCC)
- **Required Variables**:
  - `FLYO_PAYMENT_STRIPE_SECRET_KEY`: `sk_test_...` (Sandbox) / `sk_live_...` (Production).
  - `FLYO_PAYMENT_STRIPE_WEBHOOK_SECRET`: `whsec_...`
- **Scope Needed**: `issuing:card:write`, `issuing:authorization:read`, `issuing:transaction:read`.
- **Security Constraint**: Must be restricted to virtual card creation within defined spending limits. Never grant account-level administrative or withdrawal capabilities.

### D. WhatsApp Cloud API (Meta Business Platform)
- **Required Variables**:
  - `FLYO_WHATSAPP_TOKEN`: Permanent System User Access Token.
  - `FLYO_WHATSAPP_PHONE_NUMBER_ID`: 15-digit Meta Phone Number ID.
  - `FLYO_WHATSAPP_APP_SECRET`: 32-character Meta App Secret (for raw-byte HMAC-SHA256 webhook validation).
  - `FLYO_WHATSAPP_VERIFY_TOKEN`: Random 32+ character verification token for subscription handshakes.

### E. Enterprise CRM (Salesforce Service Cloud)
- **Required Variables**:
  - `FLYO_SALESFORCE_CLIENT_ID`: Connected App Consumer Key.
  - `FLYO_SALESFORCE_PRIVATE_KEY`: RSA 2048-bit PEM private key.
  - `FLYO_SALESFORCE_USERNAME`: Integration Service User account.
  - `FLYO_SALESFORCE_LOGIN_URL`: `https://test.salesforce.com` (Sandbox) / `https://login.salesforce.com`.
