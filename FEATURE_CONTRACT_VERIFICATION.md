# Flyo Phase 3.1 — Feature Contract & Leakage Verification Report

**Specification Reference**: `MODEL_FEATURE_CONTRACT.md` (Version 2.0.0)  
**Schema Artifact**: `ml/artifacts/feature_schema_v2.json`  
**Preprocessor Artifact**: `ml/artifacts/preprocessor_v2.joblib`  
**Runtime Transformer**: `ml/src/leakage_free_preprocessor.py` (`LeakageFreePreprocessor`)  
**Audit Status**: 100% Contract Compliance Confirmed  

---

## 1. Feature Dimension & Ordering Verification

The live FastAPI inference service executes `LeakageFreePreprocessor.transform_features(df_input)`. The output feature matrix was systematically validated against `feature_schema_v2.json`:

```text
Feature Count in Preprocessor Artifact: 79
Feature Count in Feature Schema JSON:  79
Symmetric Difference:                  {} (Empty Set)
Exact Name & Ordering Match:          TRUE (100% Identical)
```

### Full 79-Feature Catalog & Runtime Verification

| Index Range | Feature Category | Features Included | Verification & Imputation Rule |
| :---: | :--- | :--- | :--- |
| **0 – 2** | Calendar Date | `day_of_week`, `day_of_month`, `month` | Derived from `departure_date` or defaults to UTC now. Integer $[1-7]$, $[1-31]$, $[1-12]$. |
| **3 – 4** | Diurnal Timing | `scheduled_dep_hour`, `scheduled_arr_hour` | Parsed from `HH:MM` scheduled times. Integer $[0-23]$. |
| **5 – 6** | Route Geometry | `scheduled_elapsed_minutes`, `distance_miles` | Training medians: $120.0\text{m}$ duration, $750.0\text{ miles}$ distance. Zero artificial inflation. |
| **7 – 11** | Operational Flags | `is_dep_morning_peak`, `is_dep_evening_peak`, `is_dep_night`, `is_transcon`, `is_short_hop` | Strictly deterministic binary indicators based on departure hour and statute distance. |
| **12 – 14**| Carrier Priors | `carrier_prior_cancel_rate`, `carrier_prior_delay120_rate`, `carrier_prior_mean_delay` | Empirical priors calculated strictly on the training partition across the 15 US reporting carriers. |
| **15 – 18**| Station Priors | `origin_prior_cancel_rate`, `origin_prior_delay120_rate`, `dest_prior_cancel_rate`, `dest_prior_delay120_rate` | Empirical priors calculated strictly on the training partition across 334 US domestic airports. |
| **19 – 20**| Route Priors | `route_prior_cancel_rate`, `route_prior_delay120_rate` | Historical rate for route corridor ($\ge 10$ flights). Falls back cleanly to global baseline if unobserved. |
| **21 – 36**| Airline One-Hot (16) | `carrier_WN`, `carrier_AA`, `carrier_DL`, `carrier_UA`, `carrier_OO`, `carrier_YX`, `carrier_MQ`, `carrier_NK`, `carrier_B6`, `carrier_AS`, `carrier_9E`, `carrier_OH`, `carrier_F9`, `carrier_G4`, `carrier_HA`, `carrier_other` | Exactly 15 US reporting carriers + 1 catch-all other indicator. |
| **37 – 57**| Origin Hubs (21) | `origin_ATL` $\dots$ `origin_SLC`, `origin_other` | Top 20 US high-volume air hubs + 1 other indicator. |
| **58 – 78**| Destination Hubs (21)| `dest_ATL` $\dots$ `dest_SLC`, `dest_other` | Top 20 US high-volume air hubs + 1 other indicator. |

---

## 2. Leakage Absence & Horizon Audit

A critical finding from Phase 2 was that historical observed weather reanalysis (e.g. actual hourly temperature, wind gust, visibility recorded at arrival time) introduces **lookahead leakage** because that data cannot exist at prediction time.

### Audit Findings on Inference Inputs:
1. **Zero Observed Weather Inputs**: The 79 features contain **0 weather parameters**. Neither precipitation, visibility, ceiling, temperature, nor barometric pressure are passed into the v2 models.
2. **Zero Post-Departure Variables**:
   - `DepDelay`: **EXCLUDED** (observable only after gate pushback).
   - `TaxiOutMinutes`: **EXCLUDED** (observable only after wheels off).
   - `AirTime`: **EXCLUDED** (observable only after touchdown).
   - `WheelsOffTime`: **EXCLUDED** (observable only in flight).
   - `Diverted`: **EXCLUDED** (observable only post-flight).
3. **Strict Prediction Horizon ($T_{\text{predict}} = \text{Departure} - 2\text{h}$)**:
   All features represent state available 2 hours before scheduled departure: published airline timetable, flight distance, carrier historical reliability prior, and airport congestion priors.

---

## 3. Missing-Value Handling & Defensive Validation

FastAPI enforces strict Pydantic v2 input validation before features reach the preprocessor:

1. **Mandatory Fields**: If `flight_number`, `carrier_code`, `origin_airport`, `dest_airport`, or `scheduled_dep_time` are omitted, FastAPI aborts immediately with `HTTP 422 Unprocessable Content`. No fabricated defaults are used for mandatory routing data.
2. **Missing Duration or Distance**: Imputed using historical training set medians (`scheduled_elapsed_minutes`: 120.0m, `distance_miles`: 750.0m) as specified in `MODEL_FEATURE_CONTRACT.md`.
3. **Missing Departure Date**: Fallback to current UTC calendar date ($T_{\text{now}}$) to compute valid day of week, day of month, and month.
4. **Missing Route Prior Corridor**: If a route corridor has fewer than 10 flights in the training database, the preprocessor defaults `route_prior_cancel_rate` to `global_cancel_rate` (`0.0371`) and `route_prior_delay120_rate` to `global_delay120_rate` (`0.0364`), while flagging `INSUFFICIENT_ROUTE_FREQUENCY` in the response risk factors.
