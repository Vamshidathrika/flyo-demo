# FLYO — EXTERNAL API & REAL DATA INTEGRATION AUDIT

**Date:** November 6, 2026  
**Auditor:** Principal Integration Architect & Senior Aviation Systems Engineer  
**Repository:** `/Users/nani/Downloads/flyo`  
**Operating Mode:** STRICT AUDIT ONLY — Zero live paid transactions, external messages, or code changes executed.

---

## 1. Executive Summary & Integration Inventory

This audit evaluates every external data provider, airline channel, financial gateway, and communication integration referenced or planned in the Flyo codebase.

### **INTEGRATION VERDICT: OVERWHELMINGLY MOCKED OR PLACEHOLDER**

Out of 10 external integration surfaces evaluated across Flyo, **only 2 connect to live external servers** (Open-Meteo and SimpleFlying RSS), both of which are free consumer feeds unsuited for mission-critical flight rescue. All commercial airline reservation, payment issuing, and passenger communication adapters are either stubs or internal mocks.

---

## 2. Comprehensive Provider Classification Table

| Provider / Channel | Integration Type | Implementation Class / File | Status Classification | Primary Operational Deficiency |
| :--- | :--- | :--- | :--- | :--- |
| **Aviation Radar** | Flight Telemetry | `AviationRadarClient.java` | **MOCKED / PARTIAL** | Defaults to hardcoded mock for `AI839` and `6E402`. Live mode queries free AviationStack API with no real-time ADS-B guarantee. |
| **Open-Meteo** | Weather Telemetry | `OpenMeteoWeatherService.java` | **REAL BUT UNLICENSED** | Live HTTP client for free public API. Coordinates hardcoded for only 10 airports. Lacks certified METAR/TAF aviation feeds. |
| **SimpleFlying RSS** | Aviation News | `AviationNewsIntelligenceService.java` | **REAL BUT FRAGILE** | Live HTTP client scraping public RSS feed via regex. Falls back to static Delhi fog bulletin on failure. |
| **Sabre GDS** | Flight Booking (GDS) | `SabreGdsBookingAdapter.java` | **PLACEHOLDER (STUB)** | Throws `UnsupportedOperationException("requires signed EPR credentials")`. Zero live GDS booking capability. |
| **Amadeus NDC** | Flight Booking (NDC) | `AmadeusNdcBookingAdapter.java` | **PLACEHOLDER (STUB)** | Throws `UnsupportedOperationException("requires approved IATA agency certification")`. Zero live NDC booking capability. |
| **Playwright Portal** | Flight Booking (Web) | `PartnerPortalPlaywrightAdapter.java` | **MOCKED (LOCAL)** | `@Primary` booking adapter. Spawns Playwright to automate an internal mock HTML website on `localhost:8080`. |
| **Stripe Issuing** | Treasury / Virtual Card | `StripeIssuingPaymentAdapter.java` | **PLACEHOLDER (STUB)** | Throws `UnsupportedOperationException("Stripe Issuing live credentials pending activation")`. |
| **Sandbox Treasury** | Treasury / Virtual Card | `SandboxPaymentAdapter.java` | **MOCKED (SIMULATED)** | `@Primary` payment adapter. Generates pseudo-random PANs (`Math.random()`) and stores plaintext CVVs in PostgreSQL. |
| **Meta WhatsApp API** | Passenger Messaging | `MetaWhatsAppClient.java` / `MetaCloudWhatsAppAdapter.java` | **CONFIGURED NOT LIVE-VERIFIED** | Code supports Meta Graph API v21.0, but defaults to local in-memory chat logging because live credentials are missing. |
| **BTS Ingestion** | Historical Data | `ml/src/bts_downloader.py`, `bts_parser.py` | **REAL HISTORICAL ONLY** | Successfully downloads and parses US DOT BTS historical CSVs (Jan, Feb, Jul 2024). Not a live operational data feed. |

---

## 3. In-Depth Provider Audits

### 3.1 Aviation Radar: AviationStack (`AviationRadarClient.java`)
- **Configured Base URL:** `https://api.aviationstack.com/v1`
- **Authentication:** `access_key` query parameter (`AviationRadarConfig.java`).
- **Inspection Findings:**
  - `AviationRadarConfig.isMockMode()` defaults to `true`.
  - In mock mode, only `AI839` and `6E402` return data; all other flight queries return `Optional.empty()`.
  - In live mode, AviationStack is polled via basic HTTP GET with a 10-second timeout. AviationStack's free tier updates flights on a 30-to-60 minute delay, making it unusable for detecting cancellations in real-time before passengers miss connections.
- **Production Recommendation:** Replace with **FlightAware Firehose / AeroAPI** or **Cirium FlightStats Flex API** providing real-time ADS-B positional updates and airline flight plan amendments.

### 3.2 Aviation Weather: Open-Meteo (`OpenMeteoWeatherService.java`)
- **Configured Endpoint:** `https://api.open-meteo.com/v1/forecast`
- **Inspection Findings:**
  - Connects to a free, public, non-commercial weather service.
  - Hardcodes geographic coordinates for exactly 10 airports (`DEL`, `BOM`, `BLR`, `HYD`, `MAA`, `CCU`, `YYZ`, `LHR`, `DXB`, `SIN`). Any other airport queries fall back to a generic baseline.
  - Implements an in-memory 5-minute TTL cache (`ConcurrentHashMap`).
  - Maps WMO standard weather codes (e.g., 45/48 = Dense Fog).
- **Production Deficiencies:**
  - Open-Meteo terms of service require paid commercial agreements for high-volume enterprise operations.
  - Lacks aviation-specific NOTAMs, Runway Visual Range (RVR), cloud base ceiling, and CAT I/II/III Instrument Landing System (ILS) status.
- **Production Recommendation:** Integrate with **NOAA / FAA Aviation Weather Center (AWC) METAR/TAF API** or commercial aviation weather providers (The Weather Company / DTN).

### 3.3 Aviation Intelligence: SimpleFlying RSS (`AviationNewsIntelligenceService.java`)
- **Configured Endpoint:** `https://simpleflying.com/feed/`
- **Inspection Findings:**
  - Scrapes public WordPress RSS XML using fragile Java regular expressions (`Pattern.compile("<item>[\\s\\S]*?</item>")`).
  - Matches headlines against airport codes and airline names using simple string containment.
  - If the HTTP request fails or times out (4-second timeout), falls back to four hardcoded Indian fog and runway maintenance bulletins.
- **Production Deficiencies:**
  - High risk of parsing failure if RSS formatting changes.
  - Scraping consumer news blogs provides macro context but zero tactical operational value (e.g., ground stop ground delay programs).
- **Production Recommendation:** Connect to **FAA System Command Center (ATCSCC) Operational Information System (OIS)** and **Eurocontrol Network Manager / DGCA ATFM bulletins**.

### 3.4 Airline Reservation & Booking: Sabre GDS & Amadeus NDC
- **Sabre Adapter:** `SabreGdsBookingAdapter.java`
  - Reads `SABRE_PCC` and `SABRE_USERNAME`.
  - `executeRebook` contains:
    ```java
    throw new UnsupportedOperationException("Sabre live GDS channel requires signed EPR credentials.");
    ```
- **Amadeus Adapter:** `AmadeusNdcBookingAdapter.java`
  - Reads `AMADEUS_CLIENT_ID` and `AMADEUS_CLIENT_SECRET`.
  - `executeRebook` contains:
    ```java
    throw new UnsupportedOperationException("Amadeus live NDC channel requires approved IATA agency certification.");
    ```
- **Audit Finding:** These classes are non-functional stubs created to pass interface contract checks. They cannot book flights, modify PNRs, or query inventory.

### 3.5 Airline Rebooking: Partner Portal Playwright (`PartnerPortalPlaywrightAdapter.java`)
- **Inspection Findings:**
  - Designated as `@Primary` bean for `FlightBookingProvider`.
  - Executes a Node.js child process running Playwright (`rebook.ts`).
  - Hardcoded to automate `http://localhost:8080/mock-airline/search`.
  - Extracts `#new-pnr` from a mock Spring MVC web page.
- **Audit Finding:** This is a simulated test fixture. It provides zero real-world airline integration.

### 3.6 Corporate Payments & Treasury: Stripe Issuing vs Sandbox
- **Stripe Issuing:** `StripeIssuingPaymentAdapter.java`
  - Marked with `@Component`.
  - `issueVirtualCard` contains:
    ```java
    throw new UnsupportedOperationException("Stripe Issuing live credentials pending activation.");
    ```
- **Sandbox Adapter:** `SandboxPaymentAdapter.java`
  - Designated as `@Primary` bean for `PaymentGatewayAdapter`.
  - Generates fake PANs using `Math.random()` and saves them into the `virtual_cards` table.
- **Audit Finding:** Flyo has zero live payment settlement capability.

### 3.7 Passenger Communication: Meta Cloud WhatsApp API
- **Endpoint:** `https://graph.facebook.com/v21.0`
- **Inspection Findings:**
  - `MetaWhatsAppClient.java` contains fully implemented HTTP logic for sending text messages and interactive reply buttons.
  - However, in `application.properties`:
    ```properties
    whatsapp.meta.access-token=mock_access_token
    whatsapp.meta.phone-number-id=mock_phone_number_id
    ```
  - When credentials equal `"mock_access_token"`, `isConfigured()` returns `false`, causing outbound messages to be routed strictly to `chatLogService` (in-memory database).
- **Audit Finding:** The code is structurally capable of Meta Graph API communication, but operates exclusively in a simulated sandbox in the existing repository.

---

## 4. Integration Failure & Fallback Behavior

| Integration | Primary Failure Mode | Current Flyo Fallback Behavior | Production Risk |
| :--- | :--- | :--- | :--- |
| **Radar Feed** | HTTP Timeout / 429 Rate Limit | Returns `Optional.empty()` or mock `AI839` | Flight cancellations go unnoticed. |
| **Open-Meteo** | HTTP 500 / Network Dropped | Returns `getFallbackWeather()` (24°C, Clear) | False sense of clear weather during severe storms. |
| **News RSS** | XML Format Change / Timeout | Returns 4 static Indian fog bulletins | Operators see outdated bulletins from months ago. |
| **Airline Booking**| GDS Host Timeout / Card Decline | Playwright retries 3x, then escalates to manual desk | Operator desk is flooded with unhandled cases. |
| **WhatsApp API** | Meta Rate Limit (80007) / Token Expired | Logs error; UI chat feed stalls | Passenger is stranded without notification. |
