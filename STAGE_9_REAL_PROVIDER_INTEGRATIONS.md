# FLYO RESCUE — STAGE 9: REAL PROVIDER INTEGRATIONS & GDS ADAPTERS

## 1. Overview
In Stage 9, all fake rebooking engines, browser automation mocks, and placeholder payment adapters were deprecated from production runtime. Flyo Rescue interfaces directly with production-grade protocols and third-party APIs.

---

## 2. Airline & GDS Distribution (Amadeus NDC & REST)

### 2.1 Architecture
The primary flight booking adapter is `AmadeusNdcBookingAdapter`, annotated with `@Primary` and delegating to `AmadeusGdsSandboxClient`.

- **Authentication**: OAuth 2.0 Client Credentials flow against `https://api.amadeus.com/v1/security/oauth2/token` (or test sandbox endpoint).
- **Flight Offer Search**: `GET /v2/shopping/flight-offers` with origin, destination, departure date, and adult passenger count.
- **Flight Order Creation (NDC 21.3)**: `POST /v1/booking/flight-orders` sending passenger name, contact details, and selected offer ID.
- **Order Retrieval & PNR Extraction**: `GET /v1/booking/flight-orders/{id}` parses confirmed reservation, airline record locator (PNR), and electronic ticket status.

### 2.2 Strict Fail-Closed Rule
Under no circumstances does Flyo Rescue fabricate a PNR:
- If API credentials are not supplied in the environment, `isConfigured()` returns `false`.
- Calling `executeRebook()` throws `ProviderNotConfiguredException` with reason:
  `"Missing production Amadeus API credentials. Real GDS rebooking is blocked."`
- The rescue case state transitions to `FAILED_ESCALATED` or `MANUAL_INTERVENTION_REQUIRED`, recording a permanent audit trail.

---

## 3. Passenger Messaging (Meta WhatsApp Cloud API)

### 3.1 Architecture
`MetaWhatsAppClient` communicates with passengers via the official Graph API:
- **Base Endpoint**: `https://graph.facebook.com/v19.0/{phone_number_id}/messages`
- **Authentication**: Bearer token via `System.getenv("WHATSAPP_API_TOKEN")`.
- **Inbound Verification**: `MetaSignatureVerifier` validates SHA-256 HMAC signatures (`X-Hub-Signature-256`) against `WHATSAPP_APP_SECRET`. Fails closed if the secret is unconfigured.
- **Dynamic Targeting**: Dispatches messages to the passenger's validated phone number extracted from the booking record (removing previous hardcoded phone fallbacks).

---

## 4. Aviation Radar & Flight Telemetry

### 4.1 Architecture
`AviationRadarClient` interfaces with Aviationstack REST endpoints (`https://api.aviationstack.com/v1/flights`):
- **Access Key**: Configured via `aviation.radar.api-key` or `AVIATIONSTACK_API_KEY`.
- **Data Normalization**: Translates raw radar pings into normalized `FlightStatusAlert` domain objects.
- **Staleness Bounds**: Discards radar telemetry older than 120 minutes.
- **Fail-Closed Strategy**: When unconfigured, returns `Optional.empty()` rather than synthesizing artificial delays.

---

## 5. Weather Telemetry (Open-Meteo Aviation Service)

### 5.1 Architecture
`OpenMeteoWeatherService` queries WMO-compliant METAR and atmospheric forecasts:
- **Endpoint**: `https://api.open-meteo.com/v1/forecast` with airport latitude, longitude, hourly visibility, precipitation, and wind gust models.
- **Degraded Handling**: When network or airport lookup fails, returns `WEATHER_TELEMETRY_UNAVAILABLE` with status `UNAVAILABLE`.
