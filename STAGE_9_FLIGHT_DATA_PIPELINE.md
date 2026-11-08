# FLYO RESCUE — STAGE 9: FLIGHT & DISRUPTION DATA PIPELINE

## 1. Overview
The Flyo Rescue flight data pipeline ingests, reconciles, and normalizes operational flight state across multi-source aviation feeds without creating synthetic flights.

---

## 2. Ingestion Channels & Contracts

### 2.1 External Radar Telemetry (Aviationstack)
- **Source**: `AviationstackStatusAdapter` / `AviationRadarClient`.
- **Payload Schema**:
  ```json
  {
    "flight_date": "2026-11-08",
    "flight_status": "cancelled",
    "departure": { "airport": "Indira Gandhi International", "iata": "DEL", "delay": 135 },
    "arrival": { "airport": "Chhatrapati Shivaji Maharaj", "iata": "BOM" },
    "airline": { "name": "IndiGo", "iata": "6E" },
    "flight": { "iata": "6E501" }
  }
  ```
- **Update Frequency**: Poll interval 60s (configurable).
- **Freshness Policy**: Discard records older than 2 hours.

### 2.2 PNR & E-Ticket Ingestion
- **Source**: `PnrIngestionService`.
- **Validation**:
  - PNR must match 6-character alphanumeric pattern `^[A-Z0-9]{6}$`.
  - Flight number must match IATA carrier code + digits (e.g., `AI-839`).
  - Departure and arrival airports must be valid 3-letter IATA codes.
  - Rejection of unextractable OCR records with explicit `IllegalArgumentException`.

### 2.3 Atmospheric Weather Fusion
- **Source**: `OpenMeteoWeatherService`.
- **Metrics Extracted**:
  - Visibility (km)
  - Temperature (°C)
  - Wind speed (km/h) & gusts
  - Thunderstorm / lightning risk flags

---

## 3. Disruption Evidence Reconciliation Engine
`FlightEvidenceReconciliationService` correlates multi-source signals:
1. **Radar Ping**: Confirmed radar cancellation or delay >= 90 mins.
2. **Weather Hazard**: CAT-III fog, monsoon winds, or severe convective storms.
3. **News Intelligence**: Verified ATC ground stop or airport closure notices.

### Confidence Scoring
- **HIGH (>= 85%)**: Radar status matches airline notification and adverse weather conditions. Triggers automated rescue workflow proposal.
- **MEDIUM (60% - 84%)**: Single source disruption detected. Triggers connection risk evaluation and operator alert.
- **LOW (< 60%)**: Unverified rumor or contradictory telemetry. Requires human verification before proposal generation.
