# STAGE 10B: PROVIDER CAPABILITY & CONTRACT AUDIT MATRIX
**Flyo Disruption Management & Automated Passenger Rescue Platform**  
**Audit Date:** November 8, 2026 | **Classification Standard:** Zero-Assumption Verification

---

## 1. Classification Definitions

Every provider operation is evaluated and classified into one of the following mutually exclusive categories:
- **IMPLEMENTED**: Code exists and handles request/response parsing.
- **UNIT_TESTED**: Verified with in-memory unit tests and mocked inputs.
- **CONTRACT_TESTED**: Verified with mock HTTP servers against authoritative provider JSON schemas.
- **SANDBOX_VERIFIED**: Executed and confirmed against official third-party test endpoints (e.g. `test.api.amadeus.com`).
- **LIVE_VERIFIED**: Executed and confirmed against live production commercial APIs.
- **BLOCKED_BY_CREDENTIALS**: Implemented, but execution fails closed due to missing production API keys.
- **BLOCKED_BY_COMMERCIAL_AGREEMENT**: Requires institutional IATA/ARC accreditation or financial sponsorship.
- **NOT_IMPLEMENTED**: Operation not yet supported by current code.

---

## 2. Airline / GDS Lifecycle Capability Matrix (Amadeus NDC 21.3)

| Operation # | GDS Lifecycle Operation | Target Amadeus API Endpoint | Implementation Class & Method | Audit Classification | Provider-Confirmed Evidence / Limitations |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | **Authentication & Token Refresh** | `POST /v1/security/oauth2/token` | `AmadeusGdsSandboxClient.authenticate()` | **SANDBOX_VERIFIED** | Proactive refresh with in-memory atomic token caching; handles 1800s expiry. |
| **2** | **Flight Search** | `GET /v2/shopping/flight-offers` | `AmadeusGdsSandboxClient.searchFlightOffers(...)` | **SANDBOX_VERIFIED** | Queries origin, destination, date, passenger count. Parses segments and carriers. |
| **3** | **Fare & Availability Validation** | N/A (Client-side validation of offer payload) | `AmadeusGdsSandboxClient.parseFlightOffers(...)` | **CONTRACT_TESTED** | Asserts `numberOfBookableSeats >= 1` and matches currency (`INR`). |
| **4** | **Offer Repricing (Pre-Booking)** | `POST /v1/shopping/flight-offers/pricing` | `AmadeusGdsSandboxClient.priceFlightOffer(...)` | **CONTRACT_TESTED** | Confirms fare stability immediately prior to order submission; detects price jumps. |
| **5** | **Passenger Data Validation** | N/A (Schema validation) | `AmadeusGdsSandboxClient.createFlightOrder(...)` | **CONTRACT_TESTED** | Validates passenger name, contact phone, and PNR structure before dispatch. |
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
| **Treasury VCC** | Settlement / Void | Stripe Issuing | Void Authorization / Capture | **BLOCKED_BY_CREDENTIALS** | Fails closed with `ProviderNotConfiguredException` in prod. |
| **Merchant Acquiring**| Inbound Passenger Charge| Stripe Payments | Customer Card PaymentIntent | **NOT_IMPLEMENTED** | Requires separate merchant account integration. |
| **Passenger Messaging**| WhatsApp Alerts | Meta Cloud API | HSM Template Dispatch | **SANDBOX_VERIFIED** | HMAC-SHA256 signature verification and webhook reception verified. |
| **Aviation Radar** | Flight Telemetry | AviationStack | Live Radar Polling | **BLOCKED_BY_CREDENTIALS** | Fails closed returning `Optional.empty()` without synthetic pings. |
| **Weather Feed** | Meteorological Models | Open-Meteo | Live Forecast Query | **LIVE_VERIFIED** | Real-time meteorological queries operational for all Indian & US metro hubs. |
| **Disruption Engine**| AI Predictive ML | Flyo FastAPI Service| Disruption Risk & Delay P50/90| **LIVE_VERIFIED** | Calibrated XGBoost models operational with verified SHA-256 artifacts. |

---

## 4. Key Provider Contract Takeaways

1. **Distinction Between Order and Ticket**:
   - An Amadeus Flight Order confirmation creates an authoritative GDS PNR.
   - However, in the Amadeus sandbox, electronic ticket numbers are simulated coupon references because sandbox accounts lack IATA BSP/ARC settlement clearance.
   - Flyo Rescue now explicitly models this distinction:
     $$\text{Offer} \longrightarrow \text{Order} \longrightarrow \text{PNR} \longrightarrow \text{Ticket}$$
   - When operating in sandbox mode, the lifecycle terminates at **ORDER_CONFIRMED (PNR_ISSUED)**, and explicitly marks **TICKETING_BLOCKED_BY_COMMERCIAL_AGREEMENT**.
