# FLYO RESCUE — STAGE 9: COMPREHENSIVE FORENSIC AUDIT
## Phase 1 Forensic Inspection Report: Seed Data, Synthetic Fixtures & Mock Dependency Inventory

**Audit Date**: November 8, 2026  
**Auditor**: Antigravity Autonomous Systems Engineering (Stage 9 DevFleet Engine)  
**Target Repository**: Flyo Airline Disruption Recovery Platform (`flyo-rescue`)  
**Scope**: Backend (Java/Spring Boot), Frontend (Vanilla JS/CSS/HTML), ML Microservice (FastAPI/Python), Database Migrations (Flyway SQL), Agent Workers (TypeScript/Playwright), Configuration & CI/CD Pipelines.

---

### Executive Forensic Summary

A forensic code audit was conducted across 100% of the repository's production code paths, configuration matrices, and test suites. The investigation revealed that while Stage 6 and Stage 7 introduced enterprise-grade PostgreSQL schemas, transactional outboxes, and Amadeus GDS REST clients, the application runtime continued to harbor legacy demonstration artifacts, hardcoded mock fallbacks, automatic startup seeder routines, and synthetic fallback responses.

Specifically:
1. **Startup Seeder Execution**: `FlyoDataSeeder.java` ran unconditionally on startup in any environment when `count() == 0`, injecting 5 fake bookings (`B1` Ananya Sharma to `B5` Sneha Patel) and 3 default operator accounts with hardcoded passwords.
2. **Hardcoded Sort Biases**: Multiple core services (`BookingService`, `FlightAlertService`) had explicit sort bias prioritizing fake passenger `B1` and fake case `C1`.
3. **Simulated Option Generation**: `FlightSearchService.java` fabricated fake recovery flights (`FL201`, `FL301`, `FL401`) instead of executing live flight offer searches via Amadeus.
4. **Mock Airline Web Portal**: An in-process mock airline portal (`MockAirlineController.java` + static HTML) and Playwright browser scraping worker (`PartnerPortalPlaywrightAdapter.java`) were marked `@Primary` for rebooking execution.
5. **Silent Fallback to Synthetic Data**: `AviationRadarClient` silently generated fake radar cancellation alerts for `AI-839` and `6E-402` when API keys were missing. `OpenMeteoWeatherService` and `AviationNewsIntelligenceService` returned hardcoded bulletins/weather when upstream network calls failed.
6. **Authentication & Webhook Bypasses**: `MetaSignatureVerifier.java` returned `true` if `appSecret` was blank, permitting arbitrary unverified webhook replay.
7. **In-Memory ML Sample Data**: In the ML service (`ml/service/main.py`), the `/monitoring/leaderboard` endpoint fabricated 5 synthetic flights in memory on every invocation instead of querying persisted records.

Below is the complete itemized forensic inventory.

---

### Itemized Forensic Findings Register

| ID | File & Exact Location | Type of Fake / Mock / Seed Dependency | Scope Affected | Business & Operational Impact | Required Production Replacement | Status |
|---|---|---|---|---|---|---|
| **F-01** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/FlyoDataSeeder.java` (lines 13-110) | Unconditional DB Seeding on Startup | Production Runtime, Dev | Injects 5 fake bookings (`B1`–`B5`) and 3 demo RBAC accounts with static passwords into any empty database. | Restrict to `@Profile({"dev", "test"})` guarded by `flyo.seeding.enabled=true`. Production must boot with 0 records. | REMEDIATED |
| **F-02** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/BookingService.java` (lines 45-50) | In-Memory Priority Sorting for `B1` | Production Runtime | Corrupts operational queue ordering by hardcoding preference for synthetic passenger "Ananya Sharma". | Sort by natural business ordering (`createdAt DESC` / `id ASC`). Remove synthetic ID biases. | REMEDIATED |
| **F-03** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/BookingService.java` (lines 112-193) | Hardcoded Seed Reset Method (`resetSeedBookings`) | Production Runtime API | Exposes an unauthenticated endpoint (`POST /api/bookings/reset`) that wipes tenant data and inserts 5 fake demo bookings. | Restrict to `@Profile({"dev", "test"})` or return `403 Forbidden` in production. | REMEDIATED |
| **F-04** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/FlightSearchService.java` (lines 38-81, 89-140) | Hardcoded Candidate Flights (`FL201`, `FL301`, `FL401`) | Production Runtime | Fabricates flight numbers, schedule times, and buffers. Does not reflect actual airline seat availability or GDS inventory. | Delegate to `AmadeusGdsSandboxClient.searchFlightOffers()`. Return empty or fail closed if unconfigured. | REMEDIATED |
| **F-05** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/CaseService.java` (lines 228-232) | Fallback Static Options in `generateAndRankOptions` | Production Runtime | Silently returns fake flights when `flightSearchService` returns no booking. | Remove fallback list. Fail with explicit error or empty state. | REMEDIATED |
| **F-06** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/rebook/PartnerPortalPlaywrightAdapter.java` (lines 26-60) | Headless Browser Automation against Mock Airline Portal | Production Runtime (`@Primary`) | Primary rebooking mechanism was driving a headless browser to click through local mock HTML pages instead of GDS API orders. | Remove `@Primary`. Restrict to dev/test. Production primary must be `AmadeusNdcBookingAdapter`. | REMEDIATED |
| **F-07** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/controller/MockAirlineController.java` (lines 1-100) | Local In-Memory Mock Airline Web Portal | Production Runtime | Serves `/mock-airline/*` HTML pages and failure injection toggles (`toggle-break`) inside the production JAR. | Guard with `@Profile({"dev", "test"})`. Exclude from production runtime. | REMEDIATED |
| **F-08** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/AviationRadarClient.java` (lines 46-48, 102-123) | Silent Radar Mock Telemetry Provider | Production Runtime | Automatically fabricates severe cancellation/delay alerts for `AI-839` and `6E-402` whenever API key is missing. | Eliminate silent mock fallback. Reject mock mode in production. Return `Optional.empty()` or throw `ProviderNotConfiguredException`. | REMEDIATED |
| **F-09** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/MetaSignatureVerifier.java` (lines 14-16) | Dev/Mock Mode Authentication Bypass | Production Runtime Security | Silently bypasses HMAC-SHA256 signature verification if `appSecret` is empty, allowing forged webhook payloads. | Fail closed: return `false` if `appSecret` is missing in production. | REMEDIATED |
| **F-10** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/MetaWhatsAppClient.java` (lines 47-50, 74-77) | Silent Delivery Simulation on Missing Token | Production Runtime | Returns `true` (success) when access token is missing, giving false confirmation that WhatsApp passenger messages were sent. | Return `false` / throw `ProviderNotConfiguredException`. Never claim delivery without upstream Meta acknowledgment. | REMEDIATED |
| **F-11** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/PassengerOtpService.java` (lines 177-183) | In-Memory Raw Code Getter (`getEphemeralActiveCode`) | Production Runtime Security | Retains raw unhashed OTP codes in memory map and exposes them via public method for demo/testing convenience. | Restrict to test harness only. In production, OTPs must never be readable via runtime API. | REMEDIATED |
| **F-12** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/OpenMeteoWeatherService.java` (lines 181-196) | Fabricated Baseline Weather Fallback | Production Runtime | Returns synthetic 24.0°C / 6000m visibility when Open-Meteo API is unreachable, hiding weather telemetry failures. | Return explicit `WEATHER_TELEMETRY_UNAVAILABLE` state with zero confidence. | REMEDIATED |
| **F-13** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/AviationNewsIntelligenceService.java` (lines 94-98, 154-165) | Curated Bulletin Fallback | Production Runtime | Hardcodes 4 static Indian aviation news bulletins when RSS feed fails. | Return empty list on network failure. Never manufacture news headlines. | REMEDIATED |
| **F-14** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/PnrIngestionService.java` (lines 45, 48, 52) | Synthetic Defaults in PNR Parsing | Production Runtime | Defaults unparsed fields to `"AI-101"`, `"HYD → DEL"`, `"Tonight 21:00"` instead of rejecting invalid payloads. | Reject invalid itineraries with strict `IllegalArgumentException`. Never invent flight data. | REMEDIATED |
| **F-15** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/adapter/AmadeusAlteaAdapter.java` (lines 63-74, 98-109) | Synthetic PNR & Ticket Verification | Production Runtime | Returns hardcoded PNR `"AI-839"`, ticket `"098-2451928410"`, and simulated active coupon state when unconfigured. | Throw `ProviderNotConfiguredException` or return `BLOCKED_BY_EXTERNAL_ACCESS`. | REMEDIATED |
| **F-16** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/adapter/EmergencyHotelVoucherAdapter.java` (lines 35-42) | Fabricated Hotel Voucher Generator | Production Runtime | Invents fake voucher codes and Radisson Blu hotel names when called. | Report `BLOCKED_BY_EXTERNAL_ACCESS` / `CREDENTIALS_REQUIRED` unless real partner API is active. | REMEDIATED |
| **F-17** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/adapter/AviationstackStatusAdapter.java` (lines 57-60) | Fabricated "ON_TIME" Status | Production Runtime | When radar provider has no flight record, claims flight is `ON_TIME` between HYD and DEL. | Return `UNKNOWN` or throw `ProviderNotFoundException`. Never fabricate status. | REMEDIATED |
| **F-18** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/adapter/SimulatedWhatsAppAdapter.java` (lines 1-51) | Unconditional In-Memory WhatsApp Adapter | Production Runtime | Registers an in-memory simulated messenger into Spring context in production. | Restrict to `@Profile({"dev", "test"})`. | REMEDIATED |
| **F-19** | `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/adapter/FlyoSandboxPaymentProviderAdapter.java` (lines 1-60) | Unconditional Sandbox Payment Adapter | Production Runtime | Registers local surrogate VCC authorizer in production context. | Restrict to `@Profile({"dev", "test"})`. | REMEDIATED |
| **F-20** | `ml/service/main.py` (lines 169-183) | Hardcoded Leaderboard Sample Flights | Production Runtime ML | Injects 5 hardcoded flights (`6E-501`, `AI-839`, `UK-927`, `6E-204`, `UA-240`) into every `/monitoring/leaderboard` response. | Read live prediction records from `monitoring_db`. Return `[]` on empty database. | REMEDIATED |
| **F-21** | `flyo-rescue-demo/backend/src/main/resources/static/app.js` & `index.html` | Hardcoded Mock Airline UI Controls & Sample Ingest | Production Dashboard UI | Displays "Mock Airline: OPERATIONAL", toggle buttons, "Reset Sample" button, and hardcoded sample ingestion scripts. | Remove mock controls. Implement clean empty states for empty database. | REMEDIATED |

---

### Audit Separation Verification

Legitimate test fixtures in `src/test/` (e.g. `BookingApiTest`, `OutboxAndSagaIntegrationTest`, `WhatsAppPassengerRescueWorkflowTest`, `test_models.py`) are strictly preserved. Test fixtures are isolated from the production runtime classpath, allowing comprehensive regression and unit verification without compromising production database purification.
