# FLYO — STAGE 4 PROVIDER INTEGRATION EVIDENCE
## Empirical Provider Status, Sandbox Telemetry & Contract Verifications

**Classification:** Flyo Integration & Verification Document  
**Version:** 4.0.0-PROD-STAGE4  
**Date:** November 2026  
**Audited Location:** `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/`

---

## 1. Provider Capabilities & Connectivity Inventory

Flyo maintains 10 dedicated provider adapters registered in the `ProviderCapabilityRegistry`. Every external interaction is strictly classified into `LIVE_PRODUCTION`, `CONFIGURED_SANDBOX`, or `NOT_CONNECTED_BLOCKED`. Under no circumstances is simulated mock data labeled as live production data.

| Provider Code | Service Category | Implementation Class | Operational Mode | Verified Endpoints | Authentication Contract |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`aviationstack`** | Aviation Radar | `AviationStackRadarAdapter` | `CONFIGURED_SANDBOX` | `api.aviationstack.com/v1/flights` | API Access Key Header |
| **`flightaware`** | Aviation Radar | `FlightAwareRadarAdapter` | `CONFIGURED_SANDBOX` | `aeroapi.flightaware.com/aeroapi/flights` | `x-apikey` Header |
| **`openmeteo`** | Meteorological | `OpenMeteoWeatherService` | `LIVE_PRODUCTION` | `api.open-meteo.com/v1/forecast` | Public Open Telemetry (No Auth) |
| **`playwright_airline`**| Carrier Portal | `PlaywrightMockAirlineAdapter` | `CONFIGURED_SANDBOX` | `localhost:3000/airline-portal` | Headless Chromium + Session Token |
| **`amadeus_altea`** | PSS / E-Ticketing | `AmadeusAlteaAdapter` | `CONFIGURED_SANDBOX` | `test.api.amadeus.com/v1/booking/ticket` | OAuth2 Bearer Token |
| **`sabre_sonic`** | GDS / PSS | `SabreSonicAdapter` | `NOT_CONNECTED_BLOCKED` | `api.sabre.com/v1/trip/orders` | OAuth2 Client Credentials |
| **`stripe_issuing`** | Treasury VCC | `StripeIssuingPaymentAdapter` | `CONFIGURED_SANDBOX` | `api.stripe.com/v1/issuing/cards` | Secret API Key (Bearer) |
| **`whatsapp_cloud`** | Passenger Comms | `PassengerWhatsAppMessagingService`| `CONFIGURED_SANDBOX` | `graph.facebook.com/v21.0/{id}/messages` | System User Access Token |
| **`salesforce_crm`** | Enterprise CRM | `SalesforceServiceCloudAdapter` | `CONFIGURED_SANDBOX` | `{instance}.salesforce.com/services/data/v59.0` | OAuth2 JWT Bearer Token |
| **`fastapi_bts_ml`** | Predictive ML | `PredictionServiceClient` | `CONFIGURED_SANDBOX` | `localhost:8000/predict/flight` | Internal Service Token |

---

## 2. Verified Provider Evidence & Contract Enforcements

### 2.1. Aviation Radar & Weather Telemetry
- **Open-Meteo Integration:** Tested live against real meteorological coordinates (`DEL` lat: 28.556, lon: 77.100; `SFO` lat: 37.621, lon: -122.379). Live HTTP responses consistently return ambient temperature, surface visibility, and weather codes.
- **Evidence Reconciliation:** `FlightEvidenceReconciliationService` combines radar cancellation status with weather data. If radar indicates cancellation and weather indicates low visibility or storm conditions, reconciled confidence rises to $\ge 93\%$.

### 2.2. Airline Booking Execution (Playwright Worker Sandbox)
- **Adapter:** `PlaywrightMockAirlineAdapter` connects to local Node.js mock airline portal (`flyo-rescue-demo/airline-portal/`).
- **Idempotent Dispatch:** Dispatches rebooking via structured JSON command carrying `caseId`, `flightNumber`, `passengerName`, and tokenized `vccToken`.
- **4-Step Headless Automation:** Playwright executes flight search $\to$ seat selection $\to$ tokenized card entry $\to$ confirmation extraction, returning confirmed 6-character PNR.

### 2.3. Independent Ticket Verification (Amadeus Altea Adapter)
- **Adapter:** `AmadeusAlteaAdapter` implements `TicketVerificationProvider`.
- **Contract Verification:**
  - Evaluates PNR and 13-digit e-ticket number (`098-XXXXXXXXXX`).
  - Queries coupon status: returns `isValid = true` only when coupon status is `OPEN` or `CHECKED_IN`.
  - Returns `isValid = false` with detailed error remarks if coupon is `VOID`, `REFUNDED`, `EXCHANGED`, or `SUSPENDED`.
  - **Empirical Evidence:** In `Stage4EndToEndEnterpriseWorkflowTest.testTicketVerificationFailurePreventsCompletion`, an invalid ticket coupon immediately halted saga completion, transitioning the case to `MANUAL_INTERVENTION_REQUIRED` without charging the passenger.

### 2.4. Passenger WhatsApp Messaging
- **Adapter:** `PassengerWhatsAppMessagingService` dispatches formatted interactive messages with template parameters (Passenger Name, Old Flight, New Flight, New PNR, Gate, Boarding Time).
- **Graceful Fault Tolerance:** In `Stage4EndToEndEnterpriseWorkflowTest.testMessagingFailureDoesNotRollBackBooking`, a simulated network timeout during passenger messaging recorded `notification_status = FAILED_PENDING_RETRY` but kept the confirmed booking intact (`state = DONE`).

### 2.5. Enterprise CRM Synchronization (Salesforce Service Cloud)
- **Adapter:** `SalesforceServiceCloudAdapter` implements `CrmSynchronizationProvider`.
- **Contract Verification:**
  - Synchronizes case metadata (`case_id`, `pnr`, `flight`, `disruption_reason`, `rescue_state`) to Salesforce Case object.
  - Returns sync reference ID (e.g. `500962674017`).
  - Recorded in `rescue_cases.crm_sync_status = 'SYNCED'`.
