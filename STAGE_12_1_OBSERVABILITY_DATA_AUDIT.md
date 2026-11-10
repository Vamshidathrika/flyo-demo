# FLYO RESCUE — STAGE 12.1 OBSERVABILITY DATA AUDIT

## 1. Executive Summary

This document establishes the end-to-end data audit for the Flyo Rescue ML Observability and Operational Telemetry system. Following the Stage 12.1 verification mandate, every hop in the telemetry pipeline—from Python statistical inference engines through the Spring Boot proxy client and REST controllers down to DOM binding in the browser—was traced, audited, and hardened against data corruption, unit mismatch, and stale network responses.

---

## 2. End-to-End Data Path Architecture

```
┌─────────────────────────────────┐
│     Python FastAPI Service      │
│     (ml/service/main.py:8000)   │
└──────────────┬──────────────────┘
               │ HTTP GET /monitoring/* + X-Flyo-Monitoring-Key
               ▼
┌─────────────────────────────────┐
│    PredictionServiceClient      │
│  (com.flyo.rescue.client, 8080) │
└──────────────┬──────────────────┘
               │ Aggregated Java Map<String, Object>
               ▼
┌─────────────────────────────────┐
│ AviationIntelligenceController  │
│  (GET /api/intelligence/...)    │
└──────────────┬──────────────────┘
               │ JSON HTTP 200 (JWT Auth)
               ▼
┌─────────────────────────────────┐
│   Frontend Fetch & DOM Binding  │
│    (app.js: renderMonitoring)   │
└─────────────────────────────────┘
```

---

## 3. Authoritative Telemetry Schemas & Field Mappings

### 3.1. Primary Summary Endpoint (`/api/intelligence/monitoring/summary`)

| JSON Key | Type | Originating Component | Purpose / Operational Semantics |
| :--- | :--- | :--- | :--- |
| `circuitState` | `String` | Spring Boot `PredictionServiceClient` | Circuit breaker status (`CLOSED`, `HALF_OPEN`, `OPEN`). |
| `consecutiveFailures` | `Integer` | Spring Boot `PredictionServiceClient` | In-memory failure counter guarding against Python backend outages. |
| `mlServiceHealthy` | `Boolean` | Spring Boot `PredictionServiceClient` | Fast-fail health check flag. |
| `health` | `Object` | Python `/monitoring/health` | Service storage state, total logged predictions, pending outcome counts. |
| `predictions` | `Object` | Python `/monitoring/predictions/summary` | Volume breakdown (`PREDICTION_AVAILABLE`, `UNSUPPORTED_AIRSPACE`), p50/p95 latency metrics, and imputation rates. |
| `dataQuality` | `Object` | Python `/monitoring/data-quality` | Missingness percentages, range violation counters, and quality composite score. |
| `drift` | `Object` | Python `/monitoring/drift` | Population Stability Index (PSI), Kolmogorov-Smirnov p-values, and drift level. |
| `alerts` | `Object` | Python `/monitoring/alerts` | Active deduplicated operational notices and threshold violations. |
| `performance` | `Object` | Python `/monitoring/model-performance` | Evaluated delayed flight counts, Brier score calibration, and delta vs training baseline. |
| `governance` | `Object` | Python `/monitoring/governance` | Active model SHA-256 checksums and rollback deployment flags. |
| `leaderboard` | `Object` | Python `/monitoring/leaderboard` | Top 10 flights flagged with high disruption probabilities. |
| `heatmap` | `Object` | Python `/monitoring/heatmap` | Indian and US hub congestion statuses. |

---

## 4. Discovered Data Incompatibilities & Resolutions

1. **Duration & Distance Imputation Metric Mapping**:
   * *Before*: Looked for `dq.scheduled_duration_imputation_rate` on the root of `dataQuality`. It was `undefined`, multiplying by 100 to yield `NaN%`.
   * *Audit Finding*: The Python service exposes `overall_imputation_rate` (0.0 to 1.0) under `dataQuality.metrics` and `imputation_rate` (0.0 to 1.0) under `predictions.imputation_stats`.
   * *Resolution*: Standardized on `overall_imputation_rate` with explicit unit normalization logic (`normalizePercentage`).

2. **Drift Detected Boolean vs Categorical Drift Level**:
   * *Before*: Frontend checked `if (dr.drift_detected)`. The Python service returns `drift_level: "CRITICAL"` or `drift_level: "WARNING"` along with `metrics.max_psi >= 0.25`.
   * *Audit Finding*: `drift_detected` was not present in the dictionary. Drift defaulted to `STABLE` even with $\text{PSI} = 5.78$.
   * *Resolution*: Updated check to `isCritical = dr.drift_level === "CRITICAL" || (dr.metrics && dr.metrics.max_psi >= 0.25)`. Missing drift data renders `UNAVAILABLE`, never `STABLE`.

3. **Ground Truth Evaluated Flight Counts**:
   * *Before*: Looked for `perf.evaluated_records_count`.
   * *Audit Finding*: Python `PerformanceEvaluator` outputs `evaluated_flights_count`.
   * *Resolution*: Mapped directly to `perf.evaluated_flights_count ?? 0`.
