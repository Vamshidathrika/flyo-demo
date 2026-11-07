# FLYO — STAGE 3 PROVIDER VERIFICATION MATRIX
## Comprehensive Capability Certification & Evidence Ledger for All 11 Adapters

**Date:** November 2026  
**Auditor:** Principal Integration Architect & Forward Deployment Engineer  
**Scope:** Forensic evaluation of all 11 external provider adapter boundaries in `/Users/nani/Downloads/flyo`.  
**Rules:** A sandbox test must NEVER be marked as production certified. An internal simulator must NEVER be marked as a real provider connection.

---

## 1. Authoritative 11-Provider Capability & Evidence Matrix

| # | Provider Identifier | Adapter Name & Category | Verified Status (Stage 3) | Target Environment | API Version | Contract Test | Auth Result | Production Certified | Key Limitations & Boundary Scope |
|---|---------------------|--------------------------|---------------------------|---------------------|-------------|---------------|-------------|----------------------|-----------------------------------|
| 1 | `aviationstack` | **Aviationstack Global Tracking**<br>`FLIGHT_STATUS` | `CONFIGURED_NOT_VERIFIED` | External REST API | `v1` | **PASS** | `PENDING_CREDENTIALS` | **NO** | Aggregated commercial flight status only. Does NOT provide PNR, ticket coupons, or passenger names. |
| 2 | `amadeus_altea` | **Amadeus Altéa 1A / NDC**<br>`BOOKING_RETRIEVAL`, `PNR_LOOKUP` | `CONFIGURED_NOT_VERIFIED` | Enterprise Sandbox | `Altéa 1A / NDC 21.3` | **PASS** | `PENDING_CREDENTIALS` | **NO** | Requires client airline Office ID, IATA accreditation, and Amadeus Web Services session tokens. |
| 3 | `sabre_gds` | **Sabre GDS / SWS**<br>`BOOKING_RETRIEVAL`, `PNR_LOOKUP` | `CONFIGURED_NOT_VERIFIED` | Enterprise Sandbox | `SWS v4` | **PASS** | `PENDING_CREDENTIALS` | **NO** | Requires client Sabre PCC and EPR credentials. Binary security tokens expire every 15 minutes. |
| 4 | `stripe_issuing` | **Stripe Issuing Virtual Cards**<br>`PAYMENT_AUTHORIZATION` | `SANDBOX_CONTRACT_TESTED` | Stripe Sandbox | `API 2024-04-10` | **PASS** | `VERIFIED` | **NO** | Sandbox simulates card creation without moving funds. Production requires PCI SAQ-D / Issuing commercial contract. |
| 5 | `meta_whatsapp_cloud` | **Meta WhatsApp Cloud Platform**<br>`MESSAGING` | `SANDBOX_CONTRACT_TESTED` | Meta Cloud Sandbox | `Graph API v19.0` | **PASS** | `VERIFIED` | **NO** | Sandbox limited to registered test recipient phone numbers. Production requires verified Meta WABA & approved templates. |
| 6 | `salesforce_service_cloud` | **Salesforce Service Cloud**<br>`CRM_SYNCHRONIZATION` | `CONFIGURED_NOT_VERIFIED` | Developer Sandbox | `REST v59.0` | **PASS** | `PENDING_CREDENTIALS` | **NO** | Requires client Connected App OAuth Client ID and certificate. One-way incident push from Flyo to Salesforce. |
| 7 | `flyo_sandbox_payment_auth` | **Flyo Sandbox Payment Authorizer**<br>`PAYMENT_AUTHORIZATION` | `SANDBOX_CONTRACT_TESTED` | Local Test Harness | `Flyo Treasury v1` | **PASS** | `SIMULATED` | **NO** | Internal surrogate card token generator. Cannot be used for external banking authorization. |
| 8 | `flyo_sandbox_payment_settle` | **Flyo Sandbox Payment Settler**<br>`PAYMENT_SETTLEMENT` | `SANDBOX_CONTRACT_TESTED` | Local Test Harness | `Flyo Treasury v1` | **PASS** | `SIMULATED` | **NO** | Internal database transaction ledger. Does not perform ACH, SEPA, or card network settlement. |
| 9 | `simulated_whatsapp` | **Flyo In-Memory WhatsApp**<br>`MESSAGING` | `SANDBOX_CONNECTED` | Local Simulator | `Flyo Console v1` | **PASS** | `SIMULATED` | **NO** | Local demo tool for operator UI interactive testing. Disabled in production profiles. |
| 10 | `playwright_mock_airline` | **Playwright Mock Airline Worker**<br>`FLIGHT_REBOOKING` | `SANDBOX_CONNECTED` | Local Headless Browser | `Chromium DOM v1` | **PASS** | `SIMULATED` | **NO** | Local demonstration tool driving mock HTML portal. Fragile against real airline anti-bot defenses. |
| 11 | `emergency_hotel_voucher` | **Emergency Hotel Voucher Dispatch**<br>`HOTEL_ACCOMMODATION` | `SANDBOX_CONTRACT_TESTED` | Internal Dispatch | `Flyo Hospitality v1`| **PASS** | `SIMULATED` | **NO** | Generates digital transit vouchers for stranded passengers. No live direct GDS integration with hotel chains. |

---

## 2. Reclassification Justification

1. **Aviationstack**: Downgraded from `CONFIGURED_AND_VERIFIED` (Stage 2 claim) to `CONFIGURED_NOT_VERIFIED`. Although the REST client and DTO parsing are tested, live telemetry in CI relies on cached or simulated responses without a dedicated paid client tier.
2. **Flyo Sandbox Payment Authorizer & Settler**: Downgraded from `CONFIGURED_AND_VERIFIED` (`productionCertified: true`) to `SANDBOX_CONTRACT_TESTED` (`productionCertified: false`). Calling a local test double "production verified" was a dangerous defect.
3. **Stripe Issuing & Meta WhatsApp**: Accurately designated as `SANDBOX_CONTRACT_TESTED`. Their DTOs, endpoint URLs, and signature contracts match official specifications, but production operations are intentionally blocked until client production keys and regulatory approvals are supplied.
