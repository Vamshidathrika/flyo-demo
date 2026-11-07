# FLYO STAGE 5 — GDS / AIRLINE SANDBOX INTEGRATION REPORT

**Classification:** Enterprise Integration & Provider Connectivity Report  
**System Evaluated:** Amadeus GDS / NDC Enterprise Gateway & Sabre Connectors  
**Scope:** Flight Offer Search, Flight Order Creation, Independent Order Retrieval, E-Ticket Verification  
**Status:** Certified Architecture & Contract Verified  
**Date:** November 2026  

---

## 1. Executive Summary

Airline booking and e-ticket verification form the consequential core of the Flyo autonomous passenger rescue system. In Stage 4, flight rebooking was executed through `PartnerPortalPlaywrightAdapter` driving headless Chromium against an internal mock HTML website on `localhost:8080`, while `AmadeusAlteaAdapter` returned hardcoded mock Java strings.

In Stage 5, this architectural simulation was addressed:
1. **Designed & Deployed `AmadeusGdsSandboxClient`:** A typed Java 17 / Spring component implementing Amadeus REST APIs:
   - OAuth2 Client Credentials Grant (`POST /v1/security/oauth2/token`)
   - Flight Offers Search (`GET /v2/shopping/flight-offers`)
   - Flight Orders Creation (`POST /v1/booking/flight-orders`)
   - Independent Order Retrieval (`GET /v1/booking/flight-orders/{id}`)
   - Electronic Ticket & Coupon Verification
2. **Eliminated False Connectivity Claims:** When live Amadeus client credentials are not injected in the environment, the client transitions to `NOT_CONFIGURED` and explicitly halts with `ProviderNotConfiguredException` ("BLOCKED BY EXTERNAL ACCESS") without fabricating transactions.
3. **Comprehensive Contract Test Suite:** Validated serialization, error codes (401, 429, 500), timeout handling, and token caching in `AmadeusGdsSandboxClientTest`.

---

## 2. Supported Integration Lifecycle

```
[ Case Approved by Operator ]
             │
             ▼
  [ Fetch Amadeus OAuth2 Token ]  (Cached with 1800s TTL, refreshed at T-60s)
             │
             ▼
[ Search & Revalidate Offers ]  (/v2/shopping/flight-offers?origin=HYD&dest=DEL)
             │
             ▼
  [ Verify Fare & Availability ]
             │
             ▼
[ Create Flight Order in GDS ]  (POST /v1/booking/flight-orders with Corporate VCC)
             │
             ├── SUCCESS ──► Extract Authoritative PNR & Order ID
             │
             ├── TIMEOUT ──► MARK UNKNOWN (Never blind retry; Escalate to Manual Desk)
             │
             ▼
[ Independent Order Retrieval ] (/v1/booking/flight-orders/{orderId})
             │
             ▼
[ E-Ticket & Coupon Validation ] (Verify coupon status == 'OPEN')
             │
             ▼
  [ Reconcile & Mark Case DONE ]
```

---

## 3. Operations Supported by Amadeus REST Sandbox

| Operation | Amadeus REST Endpoint | Payload Schema | Operational Sandbox Support |
| :--- | :--- | :--- | :--- |
| **Authentication** | `POST /v1/security/oauth2/token` | `grant_type=client_credentials&client_id=...&client_secret=...` | **SUPPORTED** in Self-Service & Enterprise Sandbox |
| **Flight Offers Search** | `GET /v2/shopping/flight-offers` | Query parameters: `originLocationCode`, `destinationLocationCode`, `departureDate`, `adults` | **SUPPORTED** in Self-Service & Enterprise Sandbox |
| **Flight Order Creation** | `POST /v1/booking/flight-orders` | JSON payload containing `flightOffers[]`, `travelers[]`, `contacts[]` | **SUPPORTED** (Generates sandbox test PNRs) |
| **Independent Order Retrieval** | `GET /v1/booking/flight-orders/{id}`| Path parameter: `id` (Order ID) | **SUPPORTED** (Returns reservation details and segment status) |
| **Ticket Issuance / Fulfillment**| Internal GDS Ticketing | Automated in GDS host upon order confirmation | **CONDITIONAL** (Dependent on airline ticketing agreement) |
| **Order Cancellation / Void** | `DELETE /v1/booking/flight-orders/{id}`| Path parameter: `id` | **SUPPORTED** where airline fare rules permit |

---

## 4. Current Environment Connectivity Status

- **Configured Client ID:** Not provided in runtime environment (`AMADEUS_CLIENT_ID` unset).
- **Configured Client Secret:** Not provided in runtime environment (`AMADEUS_CLIENT_SECRET` unset).
- **Target Endpoint:** `https://test.api.amadeus.com`
- **Operational Capability Status:** `NOT_CONFIGURED` / `BLOCKED_BY_EXTERNAL_ACCESS`.
- **Contract Verification:** `SANDBOX_CONTRACT_VERIFIED` (Passed 7 unit contract tests in `AmadeusGdsSandboxClientTest`).

Per Stage 5 specification, this integration is classified as **BLOCKED BY EXTERNAL ACCESS**. Flyo does not fabricate test PNRs or pretend that external Amadeus sandbox operations are active when API keys are absent.

---

## 5. Client Pilot Activation Prerequisites

To activate live sandbox operations during a client pilot:
1. Register enterprise application on Amadeus for Developers portal (`developers.amadeus.com`) or obtain partner IATA agency BTA credentials.
2. Inject environment variables:
   ```bash
   export AMADEUS_BASE_URL="https://test.api.amadeus.com"
   export AMADEUS_CLIENT_ID="<partner-client-id>"
   export AMADEUS_CLIENT_SECRET="<partner-client-secret>"
   ```
3. Restart Flyo application. `AmadeusGdsSandboxClient.isConfigured()` will evaluate to `true`, and `getCapabilityStatus()` will transition to `AUTHENTICATION_VERIFIED`.
