# FEATURE AVAILABILITY & POINT-IN-TIME LEAKAGE REPORT
## Flyo Predictive Intelligence v1 — Feature Engineering Contract

**Timestamp:** November 5, 2026  
**Target Architecture:** Flyo Predictive Inference Engine  
**Evaluation Principle:** Strict Temporal Horizon Integrity (Zero Target Leakage)  

---

## 1. The Temporal Prediction Horizon

In real-world commercial flight operations, predictions must be generated **before** disruption occurs.

```text
  T_booking            T_minus_24h           T_predict (T - 2h)         T_departure       T_arrival
──────┼─────────────────────┼────────────────────────┼───────────────────────┼─────────────────┼───►
      │                     │                        │                       │                 │
   Schedule              Initial                  OPERATIONAL             Actual            Actual
  Published              Forecast                 DECISION POINT          Departure         Arrival
                                              (Flyo Recommends Rescue)
```

At Flyo's operational decision point ($T_{\text{predict}} = \text{Scheduled Departure } - 2\text{ hours}$):
- Passenger is either commuting to the airport or already checked in.
- The Rescue Agent evaluates onward connection risk.
- **Rule:** Any feature whose value is determined *after* $T_{\text{predict}}$ is **strictly prohibited** from the model's feature vector $X$.

---

## 2. Feature Availability by Operational Milestone

| Feature Name | $T_{\text{schedule}}$ (Weeks prior) | $T_{-24\text{h}}$ (Day prior) | $T_{\text{predict}}$ ($T - 2\text{h}$) | Post-Departure | Status for XGBoost Training |
| :--- | :---: | :---: | :---: | :---: | :--- |
| `carrier_code` | **YES** | **YES** | **YES** | **YES** | **VALID FEATURE** |
| `origin_airport`, `dest_airport` | **YES** | **YES** | **YES** | **YES** | **VALID FEATURE** |
| `scheduled_dep_time` | **YES** | **YES** | **YES** | **YES** | **VALID FEATURE** |
| `scheduled_arr_time` | **YES** | **YES** | **YES** | **YES** | **VALID FEATURE** |
| `scheduled_elapsed_minutes` | **YES** | **YES** | **YES** | **YES** | **VALID FEATURE** |
| `distance_miles` | **YES** | **YES** | **YES** | **YES** | **VALID FEATURE** |
| `day_of_week`, `month` | **YES** | **YES** | **YES** | **YES** | **VALID FEATURE** |
| `origin_scheduled_weather` | NO | Forecast Available | **High Confidence Forecast** | Realized Observation | **VALID FEATURE (Open-Meteo)** |
| `dest_scheduled_weather` | NO | Forecast Available | **High Confidence Forecast** | Realized Observation | **VALID FEATURE (Open-Meteo)** |
| `tail_number` | NO | Often Assigned | **Assigned** | Fixed | **VALID (Categorical)** |
| `actual_dep_time` | NO | NO | NO | **YES** | **PROHIBITED (LEAKAGE)** |
| `dep_delay_minutes` | NO | NO | NO | **YES** | **PROHIBITED (LEAKAGE)** |
| `taxi_out_minutes` | NO | NO | NO | **YES** | **PROHIBITED (LEAKAGE)** |
| `air_time_minutes` | NO | NO | NO | **YES** | **PROHIBITED (LEAKAGE)** |
| `carrier_delay`, `weather_delay` | NO | NO | NO | **YES** | **PROHIBITED (POST-HOC CAUSAL)** |
| `actual_arr_time` | NO | NO | NO | **YES** | **PROHIBITED (TARGET)** |
| `arr_delay_minutes` | NO | NO | NO | **YES** | **TARGET VARIABLE ($y$)** |
| `is_cancelled` | NO | NO | NO | **YES** | **TARGET VARIABLE ($y$)** |

---

## 3. Meteorological Feature Availability & Coverage

### 3.1 Historical Matching vs Live Inference
- **In Training Pipeline (`weather_matcher.py`):**
  We match each flight's origin and destination airport with the corresponding hourly slice from Open-Meteo Historical Archive at scheduled departure/arrival hour.
- **In Online Inference Service (`FastAPI`):**
  The inference service calls Open-Meteo's **Forecast API** (`api.open-meteo.com/v1/forecast`) for the scheduled departure/arrival timestamp, matching the exact meteorological feature schema.

### 3.2 Weather Missingness & Regional Fallback
- Major U.S. hubs (e.g. `ATL`, `ORD`, `DFW`, `DEN`, `LAX`, `JFK`, `SFO`, `SEA`, `MIA`, `BOS`, `EWR`, `CLT`, `PHX`, `IAH`, `LGA`) represent $> 85\%$ of commercial passenger flight volume and have $100\%$ hourly weather coverage.
- Regional outstation destinations (e.g., small non-towered airfields) that are not yet in the coordinates dictionary are explicitly marked:
  `weather_coverage_status = "ORIGIN_ONLY"` or `"UNAVAILABLE"`.
- Missing numerical weather values are set to `NaN` / `None`, which XGBoost natively handles via optimal default split directions without requiring artificial mean imputation!

---

## 4. Final Validated Training Feature Set for Phase 2

```python
MODEL_FEATURE_COLUMNS = [
    # Categorical & High-Cardinality
    "carrier_code",
    "origin_airport",
    "dest_airport",

    # Temporal & Cyclic
    "month",
    "day_of_month",
    "day_of_week",
    "dep_hour",          # Extracted from scheduled_dep_time (0-23)
    "arr_hour",          # Extracted from scheduled_arr_time (0-23)
    "is_weekend",        # 1 if day_of_week in (6, 7) else 0

    # Route Dynamics
    "distance_miles",
    "scheduled_elapsed_minutes",

    # Origin Weather at Scheduled Departure
    "origin_temp_c",
    "origin_weather_code",
    "origin_wind_speed_kmh",

    # Destination Weather at Scheduled Arrival
    "dest_temp_c",
    "dest_weather_code",
    "dest_wind_speed_kmh"
]
```
