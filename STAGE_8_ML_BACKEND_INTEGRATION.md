# FLYO STAGE 8 — PHASE 9: MODEL SERVING & SPRING BOOT BACKEND INTEGRATION

## 1. Architectural Overview

Phase 9 integrates the Stage 8 Advanced Predictive Intelligence Engine into the Flyo Spring Boot backend (`flyo-rescue-backend`), enabling end-to-end predictive dispatching, quantile delay intervals, MCT-based passenger connection risk evaluation, and operations leaderboard & heatmap observability.

```
+----------------------------------------------------------------------------------------------------+
|                                     Flyo Rescue Architecture                                      |
|                                                                                                    |
|  +---------------------------+        REST / JSON        +--------------------------------------+  |
|  | Operations Control Center | <=======================> | Spring Boot Backend (Port 8080)     |  |
|  | (Frontend Dashboard)      |                           | - AviationIntelligenceController    |  |
|  +---------------------------+                           | - PredictionServiceClient (Circuit)  |  |
|                                                          +-------------------+------------------+  |
|                                                                              |                     |
|                                                               HTTP (3-State) | Fail-Safe Circuit   |
|                                                                              v                     |
|                                                          +--------------------------------------+  |
|                                                          | FastAPI ML Engine (Port 8000)        |  |
|                                                          | - Disruption Classifier (Calibrated) |  |
|                                                          | - Quantile Delay Regressors (P10/50/90|  |
|                                                          | - Connection Risk Service (MCT)      |  |
|                                                          | - Passenger Impact Engine            |  |
|                                                          | - TreeSHAP Feature Attributions      |  |
|                                                          | - Live Weather Fusion (Age Penalty)  |  |
|                                                          +--------------------------------------+  |
+----------------------------------------------------------------------------------------------------+
```

---

## 2. Updated Client & Endpoints

### 2.1 Spring Boot REST Controller (`AviationIntelligenceController`)

The backend exposes the following intelligence and monitoring endpoints:

| Endpoint | HTTP Method | Description |
| :--- | :--- | :--- |
| `/api/intelligence/predict` | `GET` / `POST` | Flight disruption classification, probability calibration, quantile bounds, SHAP factor decomposition |
| `/api/intelligence/predict/delay-forecast` | `GET` / `POST` | Multi-horizon quantile delay forecast (`T-24H`, `T-6H`, `T-3H`, `T-1H`) |
| `/api/intelligence/predict/connection-risk` | `GET` / `POST` | Airport-specific MCT connection margin, failure probability, and operational recommendations |
| `/api/intelligence/predict/passenger-impact` | `GET` / `POST` | Disruption passenger volume estimation, vulnerable connections, projected rebooking demand |
| `/api/intelligence/monitoring/leaderboard` | `GET` | Ranked operational flight risk leaderboard across active flight movements |
| `/api/intelligence/monitoring/heatmap` | `GET` | Regional airport disruption & weather congestion heatmap (DEL, BOM, BLR, HYD, etc.) |
| `/api/intelligence/monitoring/summary` | `GET` | Aggregated ML telemetry (circuit state, KS/PSI drift, data quality, latency) |

### 2.2 PredictionServiceClient Resilience & Geofencing

`PredictionServiceClient.java` implements:
1. **Three-State Circuit Breaker**: `CLOSED`, `OPEN`, `HALF_OPEN` with automatic trial probe after cooldown timeout.
2. **Deterministic Fallback**: If the ML service is degraded or offline, the client returns fallback responses without interrupting core airline operations.
3. **Corridor Geofencing**: Supports the 6 Indian priority corridors (`DEL-BOM`, `HYD-DEL`, `HYD-BOM`, `HYD-BLR`, `BLR-MAA`, `BOM-BLR`) and US domestic network, while rejecting unsupported routes (`UNSUPPORTED_AIRSPACE`).
4. **Non-Blocking Timeouts**: 1,500ms connection and read timeouts protect the Spring Boot thread pool from upstream saturation.

---

## 3. Strongly Typed Data Transfer Objects (DTOs)

The following DTOs ensure schema parity between the FastAPI Python microservice and the Spring Boot Java backend:

- `FlightPredictionRequestDto` & `FlightPredictionResponseDto` (includes `top_contributing_factors`, `delay_p10_minutes`, `delay_p90_minutes`, `weather_hazards`)
- `ShapFactorDto` (`feature_name`, `display_name`, `feature_value`, `shap_value`, `impact_direction`, `absolute_magnitude`)
- `DelayForecastRequestDto` & `DelayForecastResponseDto` (`operational_horizon`, `expected_delay_minutes`, `delay_p10_minutes`, `delay_p90_minutes`, `prediction_interval_width`)
- `ConnectionRiskRequestDto` & `ConnectionRiskResponseDto` (`effective_mct_minutes`, `is_inter_terminal_transfer`, `connection_margin_minutes`, `connection_failure_probability`, `recommended_action`)
- `PassengerImpactRequestDto` & `PassengerImpactResponseDto` (`seat_capacity`, `estimated_passengers_total`, `vulnerable_connections_count`, `projected_rebooking_demand`, `impact_severity`)

---

## 4. Operational Invariant

> [!CRITICAL]
> **Advisory-Only Invariant**:
> Machine learning predictions and impact estimations generated by the FastAPI microservice and delivered via `PredictionServiceClient` serve strictly as operational prioritization intelligence.
> Under no circumstances can ML inference autonomously cancel flights, modify PNR reservations, reissue e-tickets, or alter ticket coupons without supervisory human authorization.
