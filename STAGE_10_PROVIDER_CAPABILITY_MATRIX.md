# STAGE 10: REAL PROVIDER CAPABILITY & CONTRACT AUDIT MATRIX
**Flyo Disruption Management & Automated Passenger Rescue Platform**  
**Audit Date:** November 8, 2026 | **Classification Standard:** Zero-Assumption Empirical Verification

---

## 1. Classification Definitions

Every external provider and integration operation is evaluated and classified into one of the following mutually exclusive categories:
- **IMPLEMENTED**: Code exists, maps domain objects, and handles request/response parsing.
- **UNIT_TESTED**: Verified with in-memory unit tests and mocked inputs.
- **CONTRACT_TESTED**: Verified with mock HTTP servers against authoritative provider JSON/REST schemas.
- **SANDBOX_VERIFIED**: Executed and confirmed against official third-party test endpoints (e.g. `test.api.amadeus.com`).
- **LIVE_VERIFIED**: Executed and confirmed against live production commercial APIs with active credentials.
- **BLOCKED_BY_CREDENTIALS**: Implemented, but execution fails closed due to missing production API keys.
- **BLOCKED_BY_COMMERCIAL_AGREEMENT**: Requires institutional IATA/ARC accreditation, bank sponsorship, or carrier bilateral agreements.
- **NOT_IMPLEMENTED**: Operation not yet supported by current code.

---

## 2. Airline / GDS Lifecycle Capability Matrix (Amadeus NDC 21.3)

| Operation # | GDS Lifecycle Operation | Target Amadeus API Endpoint | Implementation Class & Method | Audit Classification | Provider-Confirmed Evidence / Limitations |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | **Authentication & Token Refresh** | `POST /v1/security/oauth2/token` | `AmadeusGdsSandboxClient.authenticate()` | **SANDBOX_VERIFIED** | Proactive refresh with in-memory atomic token caching; handles 1800s expiry. |
| **2** | **Flight Search** | `GET /v2/shopping/flight-offers` | `AmadeusGdsSandboxClient.searchFlightOffers(...)` | **SANDBOX_VERIFIED** | Queries origin, destination, date, passenger count. Parses segments and carriers. |
| **3** | **Fare & Availability Validation** | Client-side validation of offer payload | `AmadeusGdsSandboxClient.parseFlightOffers(...)` | **CONTRACT_TESTED** | Asserts `numberOfBookableSeats >= 1` and matches currency (`INR`). |
| **4** | **Offer Repricing (Pre-Booking)** | `POST /v1/shopping/flight-offers/pricing` | `AmadeusGdsSandboxClient.priceFlightOffer(...)` | **CONTRACT_TESTED** | Confirms fare stability immediately prior to order submission; detects price jumps. |
| **5** | **Passenger Data Validation** | Schema validation | `AmadeusGdsSandboxClient.createFlightOrder(...)` | **CONTRACT_TESTED** | Validates passenger name, contact phone, and PNR structure before dispatch. |
| **6** | **Flight Order Creation** | `POST /v1/booking/flight-orders` | `AmadeusGdsSandboxClient.createFlightOrder(...)` | **SANDBOX_VERIFIED** | Submits IATA NDC 21.3 payload with flight offer and passenger identity. |
| **7** | **Order Reference Retrieval** | Response parsing of `/flight-orders` | `AmadeusFlightOrderResponse.orderId()` | **SANDBOX_VERIFIED** | Captures provider order ID (e.g. `eJzTd9f3djM0N...`). |
| **8** | **Authoritative PNR Retrieval** | `data.associatedRecords[0].reference` | `AmadeusFlightOrderResponse.pnr()` | **SANDBOX_VERIFIED** | Extracts 6-character alphanumeric GDS record locator (e.g. `R7X2K9`). |
| **9** | **Physical Ticket Issuance** | `POST /v1/booking/flight-orders/{id}/issuance` | N/A | **BLOCKED_BY_COMMERCIAL_AGREEMENT** | Amadeus Self-Service Sandbox **does not issue live physical e-tickets**. Emits confirmed order with coupon reference (`098-2451928410`). |
| **10**| **Booking Status Retrieval** | `GET /v1/booking/flight-orders/{id}` | `AmadeusGdsSandboxClient.retrieveFlightOrder(...)` | **CONTRACT_TESTED** | Retrieves order state (`CONFIRMED`), flight segments, and passenger names. |
| **11**| **Order Cancellation** | `DELETE /v1/booking/flight-orders/{id}` | `AmadeusGdsSandboxClient.cancelFlightOrder(...)` | **CONTRACT_TESTED** | Dispatches cancel command; verifies HTTP 200/204 response. |
| **12**| **Timeout Reconciliation** | HTTP Client Timeouts + Circuit Breaker | `AmadeusGdsSandboxClient` (CircuitBreaker) | **CONTRACT_TESTED** | Enforces 10s connect / 30s read timeout; trips OPEN after 5 consecutive failures. |
| **13**| **Duplicate Order Prevention** | `X-Correlation-ID` + Idempotency Filter | `AmadeusGdsSandboxClient.createFlightOrder(...)` | **CONTRACT_TESTED** | Enforces correlation ID and deduplication key to prevent duplicate booking. |

---

## 3. Supplementary Providers Capability Matrix

| Provider Subsystem | Integration Channel | Real Provider | Operation Tested | Classification | Operational Note |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Treasury VCC** | Supplier Disbursement | Stripe Issuing | Virtual Card Issuance | **BLOCKED_BY_CREDENTIALS** | Fails closed with `ProviderNotConfiguredException` in prod; Sandbox adapter verified. |
| **Merchant Payment** | Passenger Upgrades | Stripe PaymentIntents | 3DS Authorization & Capture | **IMPLEMENTED** | Supported via PCI-DSS tokenized settlement; fails closed without `STRIPE_SECRET_KEY`. |
| **Messaging** | Passenger Communication | Meta WhatsApp Cloud API | HSM Notification & Interactive Cards | **BLOCKED_BY_CREDENTIALS** | Verified via contract test; requires production WABA registration and phone approval. |
| **Aviation Radar** | Disruption Telemetry | Aviationstack / FlightAware | Flight Cancellation & Delay Polling | **BLOCKED_BY_CREDENTIALS** | Fails closed in production without `AVIATION_RADAR_API_KEY`; Open-Meteo weather is live. |
| **CRM Integration** | Corporate Escalations | Salesforce Service Cloud | REST Case Upsert & Idempotency | **CONTRACT_TESTED** | Uses OAuth2 client credentials; verified with idempotent case sync. |
| **Emergency Hotel** | Passenger Welfare | Direct Partner Voucher API | Emergency Hotel Voucher Issuance | **CONTRACT_TESTED** | Single-night voucher issuance with room guarantee token. |
| **Alternative GDS** | Backup Flight Booking | Sabre GDS (Bargain Finder Max) | Flight Shopping & Booking | **CONTRACT_TESTED** | SOAP/REST fallback adapter configured for multi-GDS routing. |

---

## 4. Key Takeaways & Architectural Boundary Rules

1. **No Mock Masquerading in Production**: If a live provider key is omitted, the platform immediately throws `ProviderNotConfiguredException` with `BLOCKED BY EXTERNAL ACCESS`.
2. **Deterministic Dual State Machine**:
   - Platform Level: `DETECTED` -> `OPTIONS_READY` -> `APPROVED` -> `REBOOKING` -> `DONE`
   - Airline Order Level: `FLIGHT_OFFER_SELECTED` -> `ORDER_CREATED` -> `PNR_CONFIRMED` -> `TICKET_ISSUED`
3. **Traceability**: All external calls attach an authoritative `X-Correlation-ID`.
