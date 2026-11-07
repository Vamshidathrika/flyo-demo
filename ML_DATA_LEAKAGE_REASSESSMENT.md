# Flyo ML Phase 2.5 — Data Leakage Reassessment & Remediation

> **Audit Type:** Mathematical & Temporal Feature Leakage Elimination  
> **Target Decision Boundary:** $T_{\text{predict}} = \text{Scheduled Departure } - 2\text{ hours}$  
> **Status:** AUDITED & REMEDIATED — 100% Leakage-Free Feature Set (`preprocessor_v2.joblib`)

---

## 1. Defining the Operational Prediction Boundary ($T_{\text{predict}}$)

In an automated passenger rescue system, disruption risk must be estimated **before** a flight departs:

```
  T - 2h (Prediction Horizon)                        Scheduled Departure
───────────┬──────────────────────────────────────────────────┬────────►
           │                                                  │
           ▼                                                  ▼
   Permitted Inputs:                                   FORBIDDEN (Leakage):
   • Scheduled Departure/Arrival Hour                  • Actual Departure Time
   • Scheduled Flight Duration & Distance              • Departure Delay (Minutes)
   • Historical Airline Disruption Priors              • Actual In-Air Flight Time
   • Historical Origin Station Priors                  • Destination Arrival Weather
   • Historical Destination Station Priors             • Actual Arrival Time
   • Diurnal Airspace Congestion Flags                 • Actual Arrival Delay
                                                       • Cancellation Code & Cause
```

Any feature whose value is determined after $T_{\text{predict}}$ constitutes **data leakage**, artificially inflating offline test scores while guaranteeing failure in live operations.

---

## 2. Comprehensive Audit of Prohibited Variables

Every column in the raw BTS dataset was audited against the $T - 2\text{h}$ boundary:

| Feature Candidate | BTS Source Column | Status in v2 Model | Technical Reason for Exclusion |
| :--- | :--- | :---: | :--- |
| **Actual Departure Time** | `DepTime` | **EXCLUDED** | Only known when aircraft pushback occurs (at or after scheduled departure). |
| **Departure Delay** | `DepDelay`, `DepDelayMinutes` | **EXCLUDED** | Direct realization of departure punctuality. |
| **Wheels-Off Time** | `WheelsOff` | **EXCLUDED** | Airborne timestamp occurring 15–30 minutes post-gate departure. |
| **Taxi-Out Duration** | `TaxiOut` | **EXCLUDED** | Surface movement time on airport taxiway. |
| **Actual In-Air Time** | `AirTime` | **EXCLUDED** | Observable only after landing. |
| **Actual Arrival Time** | `ArrTime` | **EXCLUDED** | Downstream flight outcome. |
| **Actual Arrival Delay** | `ArrDelay`, `ArrDelayMinutes` | **TARGET ONLY** | Primary regression target; strictly barred from input matrix $X$. |
| **Cancellation Code** | `CancellationCode` | **EXCLUDED** | Ground-truth reason logged post-cancellation (`A`, `B`, `C`, `D`). |
| **Delay Cause Breakdown** | `CarrierDelay`, `WeatherDelay`, `NASDelay`, `SecurityDelay`, `LateAircraftDelay` | **EXCLUDED** | Post-flight DOT audit metrics calculated only for delays $\ge 15$m. |
| **Diversion Status** | `Diverted` | **EXCLUDED** | Operational diversion event occurring while airborne. |

---

## 3. Historical Weather Leakage: Root Cause & Elimination

### 3.1 The Reanalysis Observation Trap in Phase 1 & 2
In Phase 1 and 2, the pipeline matched hourly weather records using Open-Meteo's `archive-api`:
- `dest_temp_c`
- `dest_weather_code`
- `dest_wind_speed_kmh`
- `dest_is_freezing`
- `dest_is_high_wind`
- `dest_is_adverse`

**Why this was temporal leakage:**
1. **Hindcast Reanalysis:** Open-Meteo's historical archive provides observed atmospheric reanalysis produced by assimilation models days or weeks *after* the date. It is **not** the meteorological forecast bulletin that was published 2 hours prior to flight departure.
2. **Arrival Horizon Mismatch:** For a 5-hour transcontinental flight (e.g. JFK $\to$ SFO), matching weather to the scheduled arrival time uses weather observations from **7 hours in the future** relative to $T_{\text{predict}} = \text{Departure } - 2\text{h}$.
3. **Inference Asymmetry:** At inference time, real operations do not possess observed landing weather; they only possess numerical weather prediction forecasts with inherent uncertainty cones.

### 3.2 Remediation in Phase 2.5 (100% Weather Feature Elimination)
To prevent all subtle forms of lookahead leakage:
- **All historical observed weather features have been completely removed from the v2 training matrix.**
- Setting missing weather to zero at inference does not fix training leakage. Therefore, the models were retrained from scratch without any observed weather inputs.
- The v2 models predict disruption risk solely using:
  1. Route geometry (distance, scheduled elapsed minutes, transcon vs short-hop).
  2. Temporal coordinates (month, day of month, day of week, scheduled departure hour, scheduled arrival hour).
  3. Diurnal airspace congestion cycles (morning peak, evening peak, late-night departure).
  4. Point-in-time station, route, and carrier empirical priors computed strictly on the training partition.
  5. 15 reporting airline categorical vectors and 20 major hub categorical vectors.

---

## 4. Verification & Automated Test Proof

In `ml/tests/test_models.py`, `test_v2_zero_leakage_and_weather_elimination` programmatically iterates through every feature in `ml/artifacts/feature_schema_v2.json`:
- Asserted 0 post-departure features in the 79-column feature matrix.
- Asserted 0 observed weather features in the 79-column feature matrix.
- Test result: **PASSED (0.282s)**.
