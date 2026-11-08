# FLYO — STAGE 3 PROVIDER SANDBOX CONNECTIVITY REPORT
## Controlled Sandbox Integration Boundaries & Real Provider Protocol Verification

**Date:** November 2026  
**Forward Deployment Lead:** Principal FDE & Airline Systems Architect  
**Target:** External Provider Sandboxes & Protocol Verification  
**Status:** VALIDATED & PILOT-CONSTRAINED

---

## 1. Controlled Integration Scope & Safety Guardrails

Stage 3 prepares Flyo for a controlled client pilot using real, authorized provider sandbox environments. Under strict non-negotiable project rules:
- **Zero Real Financial Exposure**: No real credit cards, bank accounts, or corporate funds are touched.
- **Zero Real Passenger Impact**: No real passenger PNRs are altered in live global airline inventory.
- **Zero Invented Credentials**: No fake live tokens or fabricated production endpoints are configured.

---

## 2. Deep-Dive Sandbox Connectivity Evaluation

### A. Flight Data Feed: Aviationstack
- **Endpoint**: `https://api.aviationstack.com/v1/flights`
- **Authentication**: Query parameter `access_key`
- **Supported Operations**: `checkFlightStatus(flightNumber, date)`
- **Parsing Verification**:
  - Normalizes carrier codes (`AI-839`, `6E-402`, `UA-240`).
  - Converts UTC/local timestamps to Java `Instant`.
  - Maps external states (`cancelled`, `delayed`, `active`, `scheduled`) into canonical Flyo states.
- **Known Limitations**:
  - Free/sandbox tier does not support real-time historical lookups or sub-minute polling.
  - Rate limited to 100 requests per month on basic tier.
  - Crucially: Aviationstack provides flight-level operational data only. It **never** provides passenger names, PNR records, or ticket coupon validity.

### B. Global Distribution Systems: Amadeus Altéa & Sabre GDS
- **Amadeus Altéa 1A**:
  - Target Endpoint: `https://test.api.amadeus.com/v2/shopping/flight-offers` & `/v1/booking/flight-orders`
  - Authentication: OAuth 2.0 Client Credentials (`client_id`, `client_secret`) returning 30-minute bearer tokens.
  - Protocol: NDC 21.3 XML / JSON.
  - Pilot Prerequisites: Airline client must issue Office ID authorization (OID) and security profile allowing Flyo to access PNRs under their airline code.
- **Sabre GDS 1S**:
  - Target Endpoint: `https://api.cert.platform.sabre.com`
  - Authentication: Sabre Session Pooling via `SessionCreateRQ` / BinarySecurityToken.
  - Pilot Prerequisites: Pseudo City Code (PCC), Employee Profile Record (EPR), and Travel Journal Record (TJR) authorization.

### C. Payment Authorization & Issuance: Stripe Issuing
- **Endpoint**: `https://api.stripe.com/v1/issuing/cards`
- **Authentication**: HTTP Bearer Header with secret key (`sk_test_...`)
- **Protocol**: REST over HTTPS with strict `Idempotency-Key` headers.
- **Tokenization Contract**:
  - Flyo generates a single-use virtual card bounded by amount (fare difference limit) and expiration.
  - Stripe returns a surrogate card token `ic_...`, masked number `**** 4242`, and expiry date.
  - **Zero Cardholder Data (CHD)**: Flyo never receives, stores, or logs the primary account number (PAN) or CVV, strictly fulfilling PCI DSS scope reduction.

### D. Passenger Communication: Meta WhatsApp Cloud API
- **Endpoint**: `https://graph.facebook.com/v19.0/{Phone-Number-ID}/messages`
- **Authentication**: System User Permanent Access Token with `whatsapp_business_messaging` scope.
- **Inbound Webhook Verification**:
  - Verification challenge handled via `GET /api/whatsapp/webhook` (`hub.challenge`, `hub.verify_token`).
  - Notification events handled via `POST /api/whatsapp/webhook` with `X-Hub-Signature-256` HMAC-SHA256 verification.
- **Delivery Confirmation Lifecycle**:
  - Flyo distinguishes: `SENT` (accepted by Meta) -> `DELIVERED` (received on handset) -> `READ` (opened by passenger).
  - Webhooks update message delivery status in `simulated_messages` and audit logs.

### E. CRM Integration: Salesforce Service Cloud
- **Endpoint**: `https://{client_instance}.my.salesforce.com/services/data/v59.0/sobjects/Case`
- **Authentication**: OAuth 2.0 JWT Bearer Token flow using client private key.
- **Field Mapping**:
  - Flyo `RescueCase.id` -> Salesforce `Flyo_Case_ID__c`
  - Disruption Details -> Salesforce `Subject`, `Description`
  - State Machine State -> Salesforce `Status`
  - PNR & Passenger Name -> Salesforce `Contact`, `PNR__c`
- **Synchronization**: Asynchronous, non-blocking outbox worker. A CRM outage never halts emergency passenger rebooking.
