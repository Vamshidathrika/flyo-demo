# Flyo ML Phase 2.5 — Model Feature Contract & Schema Specification

> **Specification Version:** 2.0.0 (`preprocessor_v2.joblib` / `feature_schema_v2.json`)  
> **Input Dimension:** Exactly 79 numerical/binary features  
> **Prediction Horizon:** $T_{\text{predict}} = \text{Scheduled Departure } - 2\text{ hours}$  
> **Status:** Production-Ready Contract

---

## 1. Feature Catalog & Data Contract

| Index | Feature Name | Data Type | Permitted Range | Imputation / Default Rule | Description & Operational Source |
| :---: | :--- | :---: | :---: | :--- | :--- |
| **0** | `day_of_week` | int | 1 – 7 | None (Mandatory) | Day of week (1=Monday, 7=Sunday) |
| **1** | `day_of_month` | int | 1 – 31 | None (Mandatory) | Calendar day of month |
| **2** | `month` | int | 1 – 12 | None (Mandatory) | Calendar month |
| **3** | `scheduled_dep_hour` | int | 0 – 23 | None (Parsed from HH:MM) | Scheduled departure hour in local time |
| **4** | `scheduled_arr_hour` | int | 0 – 23 | None (Parsed from HH:MM) | Scheduled arrival hour in local time |
| **5** | `scheduled_elapsed_minutes` | float | 20.0 – 900.0 | Training Median (120.0m) | Scheduled flight duration |
| **6** | `distance_miles` | float | 30.0 – 6000.0 | Training Median (750.0m) | Great circle flight distance |
| **7** | `is_dep_morning_peak` | int | 0 or 1 | 0 | Binary flag: Departure between 06:00 and 09:59 |
| **8** | `is_dep_evening_peak` | int | 0 or 1 | 0 | Binary flag: Departure between 16:00 and 19:59 |
| **9** | `is_dep_night` | int | 0 or 1 | 0 | Binary flag: Departure between 21:00 and 05:59 |
| **10** | `is_transcon` | int | 0 or 1 | 0 | Binary flag: Distance $\ge 2000$ miles |
| **11** | `is_short_hop` | int | 0 or 1 | 0 | Binary flag: Distance $\le 400$ miles |
| **12** | `carrier_prior_cancel_rate` | float | 0.0 – 1.0 | Global Prior (0.0371) | Historical airline cancellation rate on training partition |
| **13** | `carrier_prior_delay120_rate`| float | 0.0 – 1.0 | Global Prior (0.0364) | Historical airline $\ge 120$m delay rate |
| **14** | `carrier_prior_mean_delay` | float | -30.0 – 180.0 | Global Prior (8.83m) | Historical airline mean arrival delay |
| **15** | `origin_prior_cancel_rate` | float | 0.0 – 1.0 | Global Prior (0.0371) | Historical origin station cancellation rate |
| **16** | `origin_prior_delay120_rate`| float | 0.0 – 1.0 | Global Prior (0.0364) | Historical origin station $\ge 120$m delay rate |
| **17** | `dest_prior_cancel_rate` | float | 0.0 – 1.0 | Global Prior (0.0371) | Historical destination cancellation rate |
| **18** | `dest_prior_delay120_rate` | float | 0.0 – 1.0 | Global Prior (0.0364) | Historical destination $\ge 120$m delay rate |
| **19** | `route_prior_cancel_rate` | float | 0.0 – 1.0 | Global Prior (0.0371) | Historical origin-destination corridor cancellation rate |
| **20** | `route_prior_delay120_rate` | float | 0.0 – 1.0 | Global Prior (0.0364) | Historical origin-destination corridor $\ge 120$m delay rate |
| **21–36**| `carrier_*` (16 cols) | int | 0 or 1 | 0 | One-hot encodings for 15 BTS airlines + `carrier_other` |
| **37–57**| `origin_*` (21 cols) | int | 0 or 1 | 0 | One-hot encodings for top 20 hubs + `origin_other` |
| **58–78**| `dest_*` (21 cols) | int | 0 or 1 | 0 | One-hot encodings for top 20 hubs + `dest_other` |

**Total Feature Columns:** Exactly 79 columns. Zero missing values permitted.

---

## 2. API Request Transformation Pipeline

When the future FastAPI service receives a `FlightPredictionRequest`:

```python
# Raw API Request JSON
{
  "flight_number": "UA240",
  "carrier_code": "UA",
  "origin_airport": "ORD",
  "dest_airport": "SFO",
  "flight_date": "2024-07-15",
  "scheduled_dep_time": "18:45",
  "scheduled_arr_time": "21:30",
  "distance_miles": 1846.0,
  "scheduled_elapsed_minutes": 285.0
}
```

### Deterministic Transformation Steps:
1. **Date & Time Deconstruction:**
   - `flight_date` $\to$ `month=7`, `day_of_month=15`, `day_of_week=1` (Monday).
   - `scheduled_dep_time` $\to$ `scheduled_dep_hour=18`, `is_dep_evening_peak=1`.
   - `scheduled_arr_time` $\to$ `scheduled_arr_hour=21`.
2. **Prior Lookups:**
   - Look up `UA` in `preprocessor.training_priors["carrier_stats"]` $\to$ `carrier_prior_cancel_rate=0.0395`.
   - Look up `ORD` in `origin_stats` $\to$ `origin_prior_cancel_rate=0.0410`.
   - Look up `SFO` in `dest_stats` $\to$ `dest_prior_cancel_rate=0.0320`.
   - Look up `ORD_SFO` in `route_stats` $\to$ corridor historical disruption priors.
3. **One-Hot Vectorization:**
   - Set `carrier_UA = 1`, all other `carrier_* = 0`.
   - Set `origin_ORD = 1`, all other `origin_* = 0`.
   - Set `dest_SFO = 1`, all other `dest_* = 0`.
4. **Validation Guard:**
   - Non-US routes (e.g. `DEL -> BOM`) trigger immediate rejection with `UNSUPPORTED_AIRSPACE`.
   - Missing required date/time strings trigger HTTP 422 Unprocessable Entity.
