# STAGE 10: AMADEUS GDS / NDC LIVE READINESS & CONTRACT AUDIT
**Flyo Disruption Management Platform**  
**Audit Standard:** IATA NDC 21.3 / Amadeus Enterprise Web Services (1A WS)

---

## 1. Executive Summary

Amadeus is Flyo's primary Global Distribution System (GDS) and New Distribution Capability (NDC) provider. This document audits the actual operational capability, credential status, sandbox verification, and requirements for live production activation.

```
┌─────────────────────────────────┬────────────────────────────────────────────────────────┐
│ Dimension                       │ Operational Status                                     │
├─────────────────────────────────┼────────────────────────────────────────────────────────┤
│ API Version                     │ IATA NDC 21.3 / Amadeus Self-Service & Enterprise REST │
│ Sandbox Endpoint                │ https://test.api.amadeus.com                           │
│ Production Endpoint             │ https://api.amadeus.com                                │
│ Current Environment Status      │ SANDBOX VERIFIED (Contract & Mock Verified)            │
│ Live Commercial Activation      │ BLOCKED BY COMMERCIAL AGREEMENT (IATA Accreditation)   │
└─────────────────────────────────┴────────────────────────────────────────────────────────┘
```

---

## 2. Detailed Technical Audit of the 12 Amadeus Operations

### 1. OAuth2 Authentication (`/v1/security/oauth2/token`)
- **Status**: `SANDBOX_VERIFIED`
- **Method**: `POST`
- **Payload**: `grant_type=client_credentials&client_id={id}&client_secret={secret}`
- **Behavior**: Retrieves JWT bearer token with `expires_in=1799`. Caches token atomically in memory. Refreshes proactively when <60 seconds remain.

### 2. Flight Offers Search (`/v2/shopping/flight-offers`)
- **Status**: `SANDBOX_VERIFIED`
- **Method**: `GET`
- **Parameters**: `originLocationCode`, `destinationLocationCode`, `departureDate`, `adults`, `max=5`
- **Behavior**: Searches live seat inventory across operating carriers (Air India, IndiGo, Vistara, Lufthansa). Validates bookable seats.

### 3. Fare Retrieval & Price Guarantee
- **Status**: `CONTRACT_TESTED`
- **Method**: Evaluates fare breakdown (`total`, `base`, `taxes`, `currency`).
- **Behavior**: Enforces currency alignment (`INR`). Rejects zero-fare or anomalous price spikes.

### 4. Flight Offers Pricing (`/v1/shopping/flight-offers/pricing`)
- **Status**: `CONTRACT_TESTED`
- **Method**: `POST`
- **Behavior**: Confirms that fare and seat availability are locked by the airline host immediately prior to order submission.

### 5. Flight Order Creation (`/v1/booking/flight-orders`)
- **Status**: `SANDBOX_VERIFIED`
- **Method**: `POST`
- **Payload**: Encapsulates selected flight offer, passenger traveler details (DOB, passport/ID, gender, contact), and payment card.
- **Behavior**: Dispatches order creation with `X-Correlation-ID`. Captures authoritative provider order reference and initial booking state.

### 6. Order Reference Retrieval
- **Status**: `SANDBOX_VERIFIED`
- **Behavior**: Parses `data.id` (e.g. `eJzTd9f3djM0N...`). Durably persists provider order ID in database.

### 7. Authoritative GDS PNR Confirmation
- **Status**: `SANDBOX_VERIFIED`
- **Behavior**: Extracts official 6-character alphanumeric GDS record locator from `data.associatedRecords[0].reference` (e.g. `R7X2K9`).

### 8. Physical Electronic Ticket Issuance (`/issuance`)
- **Status**: `BLOCKED_BY_COMMERCIAL_AGREEMENT`
- **Behavior**: In the Amadeus Self-Service Sandbox, physical ticket issuance is not permitted because sandbox accounts lack an IATA numeric code and ARC ticketing authority. The sandbox returns an issued coupon reference (`098-2451928410`). Live ticket issuance requires Amadeus Enterprise NDC agreement.

### 9. Booking Status Retrieval (`/v1/booking/flight-orders/{id}`)
- **Status**: `CONTRACT_TESTED`
- **Method**: `GET`
- **Behavior**: Re-queries order status independently without altering state. Reconciles airline host updates.

### 10. Order Cancellation (`DELETE /v1/booking/flight-orders/{id}`)
- **Status**: `CONTRACT_TESTED`
- **Method**: `DELETE`
- **Behavior**: Cancels un-ticketed or refundable reservations within carrier grace periods.

### 11. Timeout Reconciliation & Circuit Breaking
- **Status**: `CONTRACT_TESTED`
- **Behavior**: Configured with 10s connect timeout and 30s read timeout. Trips circuit breaker to `OPEN` after 5 consecutive transport failures.

### 12. Duplicate Booking Protection
- **Status**: `CONTRACT_TESTED`
- **Behavior**: Maps idempotency key (`idem_RESCUE_CASE_{caseId}`) to prevent accidental double-booking.

---

## 3. Commercial Roadmap for Production Amadeus NDC Activation

1. **Enterprise Contract Execution**: Transition from Self-Service Developer tier to Amadeus Enterprise Flight Booking.
2. **IATA / ARC Accreditation**: Obtain IATA TIDS or ARC agency code, or partner with an accredited Travel Management Company (TMC) host.
3. **Airline Bilateral Approval**: Execute airline distribution agreements with Air India (AI), IndiGo (6E), and international partner carriers.
4. **Payment Settlement Plan (BSP/ARC)**: Establish Bank Settlement Plan clearing for ticket issuance and void settlement.
