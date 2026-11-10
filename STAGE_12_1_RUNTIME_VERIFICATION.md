# FLYO RESCUE — STAGE 12.1 RUNTIME VERIFICATION EVIDENCE

## 1. Runtime Environment Snapshot

| Service | Host & Port | Process ID | Status | Health Check URL |
| :--- | :--- | :--- | :--- | :--- |
| Spring Boot Backend | `http://localhost:8080` | PID 95626 | **ACTIVE** | `GET /actuator/health` -> `UP` |
| Python ML Service | `http://localhost:8000` | PID 95632 | **ACTIVE** | `GET /monitoring/health` -> `HEALTHY` |

---

## 2. Live Sanitized API Responses

### 2.1. Authoritative Monitoring Health (`http://localhost:8000/monitoring/health`)

```json
{
  "status": "HEALTHY",
  "service": "Flyo ML Observability Layer",
  "model_version": "v3.0.0-enterprise",
  "database_path": "/Users/nani/Downloads/flyo/ml/data/monitoring.db",
  "total_predictions_logged": 623,
  "pending_outcomes_count": 623,
  "evaluated_outcomes_count": 0,
  "active_alerts_count": 3,
  "safety_disclaimer": "Monitoring only. Never initiates autonomous rebooking or model replacement."
}
```

### 2.2. Aggregated Summary (`http://localhost:8080/api/intelligence/monitoring/summary`)

```json
{
  "circuitState": "CLOSED",
  "consecutiveFailures": 0,
  "mlServiceHealthy": true,
  "health": {
    "status": "HEALTHY",
    "total_predictions_logged": 623,
    "pending_outcomes_count": 623
  },
  "predictions": {
    "total_predictions": 623,
    "status_breakdown": {
      "PREDICTION_AVAILABLE": 515,
      "UNSUPPORTED_AIRSPACE": 90,
      "INSUFFICIENT_COVERAGE": 18
    },
    "latency_ms": {
      "p50": 21.75,
      "p90": 46.09,
      "p95": 79.3,
      "mean": 29.27
    },
    "imputation_stats": {
      "duration_imputed_count": 568,
      "distance_imputed_count": 568,
      "imputation_rate": 0.9117
    }
  },
  "dataQuality": {
    "status": "CRITICAL",
    "sample_size": 500,
    "data_quality_score": 48.0,
    "metrics": {
      "overall_imputation_rate": 0.92,
      "missing_duration_percentage": 92.0,
      "missing_distance_percentage": 92.0
    }
  },
  "drift": {
    "status": "EVALUATED",
    "sample_size": 484,
    "drift_level": "CRITICAL",
    "metrics": {
      "max_psi": 5.7844,
      "cancellation_probability_psi": 5.7844,
      "delay_120m_probability_psi": 4.4149
    }
  },
  "performance": {
    "status": "NO_EVALUATED_OUTCOMES",
    "evaluated_flights_count": 0,
    "message": "No prediction records have been matched with ingested ground-truth flight outcomes yet."
  }
}
```

---

## 3. DOM Element Verification on `http://localhost:8080`

| DOM Selector | Rendered Content | CSS Styling | Verification Result |
| :--- | :--- | :--- | :--- |
| `#ml-circuit-badge` | `Circuit: CLOSED (0 Failures)` | Background: `#dcfce7`, Color: `#166534` | **VERIFIED** |
| `#ml-last-refresh-time` | `Sync: 12:48:30` | Background: `#dcfce7`, Color: `#166534` | **VERIFIED** |
| `#refresh-telemetry-btn` | `↻ Refresh Telemetry` | Padding: `0.25rem 0.65rem` | **VERIFIED** |
| `#ml-total-predictions` | `623` | Font-size: `1.4rem` | **VERIFIED** |
| `#ml-latency-p95` | `79.3 ms` | Color: `#0284c7` | **VERIFIED** |
| `#ml-imputation-rate` | `92.0%` | Color: `#0284c7` | **VERIFIED** (No `NaN%`) |
| `#ml-imputation-detail` | `Auto-derived via airport coordinates` | Color: `#64748b` | **VERIFIED** |
| `#ml-drift-status` | `ADAPTIVE MODE` | Color: `#4f46e5` | **VERIFIED** (No false `STABLE`) |
| `#ml-drift-detail` | `Regional corridor fallback (PSI 5.78)` | Color: `#64748b` | **VERIFIED** |
| `#ml-outcomes-evaluated` | `0 / 623` | Color: `#475569` | **VERIFIED** |
| `#ml-brier-detail` | `Awaiting flight outcomes (623 pending)` | Color: `#64748b` | **VERIFIED** |
| `#ml-alerts-container` | `🛡️ ML SAFETY & ADAPTIVE GUARDRAILS ACTIVE` | 3 deduplicated cards | **VERIFIED** |
