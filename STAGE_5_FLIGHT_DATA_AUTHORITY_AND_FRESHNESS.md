# FLYO STAGE 5 — FLIGHT DATA AUTHORITY, FRESHNESS & RECONCILIATION

**Classification:** Operational Telemetry & Aviation Data Governance Document  
**Target:** Flight Ingestion, Evidence Provenance, Multi-Source Reconciliation, and MCT Feasibility  
**Scope:** `AviationRadarClient`, `OpenMeteoWeatherService`, `FlightEvidenceReconciliationService`, `MctRulesService`  
**Status:** Certified Architecture & Contract Verified  
**Date:** November 2026  

---

## 1. Flight Data Source Authority Matrix

Flyo ingests telemetry from multiple independent channels. No single external provider is treated as infallible. Every fact maintains explicit source attribution, update cadence, and verification status:

| Provider / Channel | Ingestion Endpoint | Authentication | Geographic Coverage | Ingestion Frequency | Rate Limits | Data Authority Rank | Actual Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`Open-Meteo Airport Telemetry`** | `https://api.open-meteo.com/v1/forecast` | Public API (No Auth) | Global (WMO coordinates for 10 major hubs: DEL, BOM, BLR, HYD, MAA, CCU, YYZ, LHR, FRA, DXB) | 5-minute TTL cache | 10,000 req/day | Rank 1 (Primary Ground Truth for Weather & Visibility) | **VERIFIED_REAL** (Live HTTP queries returning ambient temp & visibility) |
| **`AviationStack Radar Feed`** | `https://api.aviationstack.com/v1/flights` | Query Parameter (`access_key`) | Global scheduled & tracked commercial flights | 60-second polling window | 100 req/month (Free Tier) | Rank 2 (Independent Surveillance Radar Telemetry) | **CONFIGURED_SANDBOX** / Mock Fallback |
| **`FlightAware AeroAPI v3`** | `https://aeroapi.flightaware.com/aeroapi/flights` | Header (`x-apikey`) | Global ADS-B radar, surface tracking, and airspace delays | Event webhook & 30s poll | Tiered commercial quota | Rank 2 (Surveillance Radar Alternative) | **CONFIGURED_NOT_VERIFIED** |
| **`Airline PSS Direct Webhook`** | `/api/v1/webhooks/airline/schedule-change` | HMAC-SHA256 Signature Header | Operating carrier specific | Instant Push (Webhook) | Ingestion capacity 100/sec | Rank 1 (Carrier Authoritative Intent) | **SANDBOX_CONTRACT_VERIFIED** |
| **`BTS ML Inference Service`** | `http://localhost:8000/predict/flight` | Internal Service Bearer Token | 334 US Domestic BTS Airports | Sub-millisecond local inference | 2,000 req/sec | Rank 3 (Probabilistic Disruption Risk Scoring) | **SANDBOX_OPERATION_VERIFIED** |

---

## 2. Taxonomy of Flight Status Concepts

Stage 5 strictly isolates operational flight states to prevent deceptive state unification:

1. **Scheduled Flight (`SCHEDULED`):** Published timetable in PNR or carrier schedule. Zero telemetry implied.
2. **Estimated Flight (`ESTIMATED`):** Target takeoff/landing time calculated by airline dispatch based on turnaround.
3. **Observed Flight (`TRACKED_AIRBORNE`):** Confirmed by ADS-B transponder or ACARS transmission.
4. **Delayed Flight (`DELAYED`):** Operating flight with estimated departure $\ge 15$ minutes past scheduled time.
5. **Cancelled Flight (`CANCELLED`):** Operating flight formally withdrawn from schedule by carrier or ATC.
6. **Diverted Flight (`DIVERTED`):** Airborne flight redirected to an alternate destination.
7. **Airline-Confirmed Status (`CARRIER_OFFICIAL`):** Status published directly by carrier PSS/GDS.
8. **Provider-Reported Status (`RADAR_OBSERVED`):** Status reported by third-party surveillance networks (AviationStack/FlightAware).

**Rule of Disagreement:** When Carrier Schedule claims `ON_TIME` but Radar Telemetry reports `CANCELLED` or delay $\ge 120$m, the disagreement is preserved as a `DiscrepancyRecord` in the database. The system never merges contradictory feeds into a falsely unified state.

---

## 3. Evidence Model & Confidence Scoring Calculation

Every reconciled flight fact ingested by `FlightEvidenceReconciliationService` records:
- **Value:** Ingested status (e.g. `CANCELLED`, `DELAYED_180M`).
- **Source:** Channel identity (e.g. `AviationStack`, `Open-Meteo DEL`).
- **Observed Timestamp:** Exact time observed at the source.
- **Ingestion Timestamp:** Wall-clock time recorded in Flyo database.
- **Freshness Window:** 
  - Radar Telemetry: Valid for 10 minutes.
  - Weather Telemetry: Valid for 30 minutes.
  - PSS Schedule: Valid for 2 hours.
- **Contradiction Status:** `CONCORDANT`, `PARTIAL_CORROBORATION`, or `CONTRADICTORY`.

### Documented Confidence Scoring Calculation
Confidence is computed through deterministic, additive rules—never arbitrary numbers:

$$\text{Confidence} = \text{Base} + \Delta_{\text{corroboration}} - \Delta_{\text{latency}} - \Delta_{\text{contradiction}}$$

1. **Base Score:**
   - Single unverified radar alert: 70%
   - Carrier official schedule change webhook: 85%
2. **Corroborating Evidence Adjustments ($+\Delta$):**
   - Adverse destination weather confirmed by Open-Meteo (visibility $< 1,000$m or CAT III fog): $+15\%$
   - Active terminal congestion / ATC ground stop bulletin: $+10\%$
   - Maximum capped confidence: 98%
3. **Penalties ($-\Delta$):**
   - Telemetry older than freshness window: $-20\%$
   - Single-source uncorroborated cancellation during clear weather: $-10\%$
   - Direct contradiction between carrier schedule and radar: $-25\%$ (Triggers `MANUAL_REVIEW_REQUIRED`).

---

## 4. Minimum Connection Time (MCT) Hardening

A connection is not feasible merely because the scheduled buffer between flights is positive ($t_{\text{arr2}} - t_{\text{dep1}} > 0$).

In Stage 5, `MctRulesService` enforces:
1. **Published Airport Matrix:** Encapsulates published IATA MCT standards for major transfer hubs:
   - **Delhi (DEL T3):** Domestic $\to$ International published MCT is 90 minutes.
   - **Mumbai (BOM T2):** Domestic $\to$ International published MCT is 90 minutes.
   - **London Heathrow (LHR):** Inter-terminal published MCT is 105 minutes.
   - **Frankfurt (FRA):** International $\to$ International published MCT is 60 minutes.
2. **Flyo Conservative Protection Policy:**
   - Domestic $\to$ Domestic: 75 minutes.
   - Domestic $\to$ International: 120 minutes (safeguards onward international connections against transit terminal security delays).
   - Inter-Terminal Transfers: 150 minutes.
3. **Fail-Closed Handling of Unknown Airports:**
   - If an airport is not in the certified published matrix, `MctRulesService.evaluate()` marks:
     `isKnownAirportMct = false`, `requiresManualReview = true`.
   - The connection is flagged for operator inspection:
     > *"Airport [CODE] MCT rules not officially published in matrix. Default conservative buffer applied. Manual connection feasibility review required."*
   - Verified by `Stage5EnterpriseVerificationAndProviderIntegrationTest.testMctHardeningForUnknownAirports`.
