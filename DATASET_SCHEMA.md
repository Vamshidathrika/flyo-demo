# DATASET SCHEMA SPECIFICATION
## Flyo Predictive Intelligence v1 — Flight & Weather Features

**Schema Version:** 1.0.0  
**Storage Path:** `ml/data/processed/flights_{YYYY}_{MM}.csv`  
**Enriched Sample:** `ml/data/processed/enriched_sample_{YYYY}_{MM}.csv`  
**Engine:** XGBoost Regressor (Delay) & XGBoost Classifier (Cancellation)  

---

## 1. Primary Target Labels (Outcomes)

| Column Name | Data Type | Nullable | Description & Machine Learning Purpose |
| :--- | :---: | :---: | :--- |
| `arr_delay_minutes` | `FLOAT` | Yes (Null if Cancelled) | **Primary Continuous Target for XGBRegressor.** Actual arrival delay in minutes (positive = delayed, negative = early arrival). |
| `is_cancelled` | `INT (0/1)` | No | **Primary Binary Target for XGBClassifier.** `1` if flight was cancelled before departure, `0` if flight departed. |
| `is_delayed_120m` | `INT (0/1)` | No | **Secondary Binary Threshold Target.** `1` if arrival delay $\ge 120\text{ minutes}$, `0` otherwise. Directly corresponds to Flyo Minimum Connection Time (MCT) safety threshold. |
| `is_delayed_15m` | `INT (0/1)` | No | Industry standard DOT on-time benchmark ($\ge 15\text{m}$ delay). |

---

## 2. Flight Identification & Temporal Features

| Column Name | Data Type | Nullable | Description & Leakage Prevention Rules |
| :--- | :---: | :---: | :--- |
| `flight_id` | `VARCHAR(32)` | No | Unique synthetic identifier assigned during ingestion (e.g. `BTS-202401-0000001`). |
| `flight_date` | `DATE (YYYY-MM-DD)`| No | Date of the scheduled flight. |
| `year` | `INT` | No | Flight year (e.g., 2024). Used for chronological cross-validation split. |
| `month` | `INT (1-12)` | No | Seasonal cyclic feature. |
| `day_of_month` | `INT (1-31)` | No | Day of the month. |
| `day_of_week` | `INT (1-7)` | No | Day of week (1 = Monday, 7 = Sunday). Captures weekend/weekday congestion. |
| `carrier_code` | `VARCHAR(8)` | No | Reporting carrier IATA code (e.g. `DL`, `AA`, `UA`, `WN`, `9E`). |
| `tail_number` | `VARCHAR(16)` | Yes | Aircraft tail registration number (e.g. `N485PX`). |
| `flight_number` | `VARCHAR(16)` | No | Marketing flight number (e.g. `4801`). |

---

## 3. Spatial & Route Features

| Column Name | Data Type | Nullable | Description |
| :--- | :---: | :---: | :--- |
| `origin_airport` | `VARCHAR(4)` | No | 3-letter IATA code of departure airport (e.g. `LGA`, `ATL`, `ORD`). |
| `origin_city` | `VARCHAR(64)` | No | City and State of origin (e.g. `New York, NY`). |
| `origin_state` | `VARCHAR(4)` | No | 2-letter state code (e.g. `NY`, `GA`). |
| `dest_airport` | `VARCHAR(4)` | No | 3-letter IATA code of arrival airport (e.g. `OMA`, `MIA`, `DFW`). |
| `dest_city` | `VARCHAR(64)` | No | City and State of destination. |
| `dest_state` | `VARCHAR(4)` | No | 2-letter state code of destination. |
| `distance_miles` | `FLOAT` | No | Great-circle distance between origin and destination in miles. |

---

## 4. Schedule & Duration Features (Available at $T_{\text{predict}}$)

> [!CRITICAL]
> **Data Leakage Boundary:** At prediction time ($T_{\text{predict}} \le T_{\text{scheduled\_departure}}$), actual departure time, actual departure delay, and actual elapsed flight time are **strictly unavailable**. Only scheduled timestamps are allowed into training feature vectors!

| Column Name | Data Type | Nullable | Availability at $T_{\text{predict}}$ | Description |
| :--- | :---: | :---: | :---: | :--- |
| `scheduled_dep_time` | `VARCHAR(5) HH:MM` | No | **YES (Feature)** | Official scheduled gate departure time (local). |
| `scheduled_arr_time` | `VARCHAR(5) HH:MM` | No | **YES (Feature)** | Official scheduled gate arrival time (local). |
| `scheduled_elapsed_minutes` | `FLOAT` | No | **YES (Feature)** | Planned block time duration in minutes. |
| `actual_dep_time` | `VARCHAR(5) HH:MM` | Yes | **NO (Target-Leaking)** | Post-departure outcome. Excluded from training features. |
| `dep_delay_minutes` | `FLOAT` | Yes | **NO (Target-Leaking)** | Post-departure outcome. Excluded from training features. |
| `actual_arr_time` | `VARCHAR(5) HH:MM` | Yes | **NO (Target-Leaking)** | Post-arrival outcome. Excluded from training features. |
| `actual_elapsed_minutes` | `FLOAT` | Yes | **NO (Target-Leaking)** | Post-arrival outcome. Excluded from training features. |

---

## 5. Meteorological Features (Open-Meteo Historical Archive API)

Features matched strictly using **scheduled departure hour** at origin and **scheduled arrival hour** at destination:

| Column Name | Data Type | Nullable | Description |
| :--- | :---: | :---: | :--- |
| `origin_temp_c` | `FLOAT` | Yes | Surface temperature at origin airport at scheduled departure hour. |
| `origin_weather_code` | `INT` | Yes | WMO Weather Code at origin (e.g. 0 = Clear, 45/48 = Dense Fog, 95 = Thunderstorm). |
| `origin_wind_speed_kmh` | `FLOAT` | Yes | 10-meter wind speed at origin airport. |
| `dest_temp_c` | `FLOAT` | Yes | Surface temperature at destination airport at scheduled arrival hour. |
| `dest_weather_code` | `INT` | Yes | WMO Weather Code at destination airport at scheduled arrival hour. |
| `dest_wind_speed_kmh` | `FLOAT` | Yes | 10-meter wind speed at destination airport. |
| `weather_coverage_status` | `VARCHAR(16)` | No | `FULL`, `ORIGIN_ONLY`, `DEST_ONLY`, or `UNAVAILABLE` (transparently documents weather missingness). |
