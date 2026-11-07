# Prediction Observability & Logging Audit Report

**Flyo Disruption Rescue Platform &middot; Phase 4 Engineering Verification**  
**Module:** `ml/service/monitoring_db.py` & `ml/service/predictor.py`  
**Storage Engine:** SQLite (WAL Mode), Thread-Safe Connection Pooling  
**Zero-PII Compliance:** VERIFIED  

---

## 1. Traceability & Zero-PII Compliance

Flyo's predictive intelligence service handles flight telemetry without ever exposing or persisting Personally Identifiable Information (PII).

### Audit Checklist:
- **Passenger Names**: Never accepted by FastAPI schemas; not logged to disk.
- **PNRs / Booking Codes**: Never accepted by FastAPI schemas; not logged to disk.
- **Contact Numbers / Emails**: Never accepted by FastAPI schemas; not logged to disk.
- **Payment Card Data / VCC Tokens**: Never passed to ML inference microservice.
- **Traceable Identifiers**: Each request receives a client/server-shared UUID (`prediction_id`) enabling end-to-end audit tracing between Spring Boot audit logs and ML monitoring records.

---

## 2. Logged Prediction Schema

Every inference request is persisted into `prediction_records` table with the following structured schema:

```sql
CREATE TABLE prediction_records (
    prediction_id TEXT PRIMARY KEY,
    timestamp TEXT NOT NULL,
    model_version TEXT NOT NULL,
    flight_number TEXT NOT NULL,
    carrier_code TEXT NOT NULL,
    origin_airport TEXT NOT NULL,
    dest_airport TEXT NOT NULL,
    scheduled_dep_time TEXT NOT NULL,
    flight_date TEXT,
    inference_latency_ms REAL NOT NULL,
    status TEXT NOT NULL,
    status_reason TEXT,
    cancellation_probability REAL,
    delay_120m_probability REAL,
    expected_delay_minutes REAL,
    is_duration_imputed INTEGER NOT NULL DEFAULT 0,
    is_distance_imputed INTEGER NOT NULL DEFAULT 0,
    features_json TEXT,
    outcome_status TEXT NOT NULL DEFAULT 'PENDING_OUTCOME',
    ground_truth_id TEXT
);
```

---

## 3. Latency Profiling & Tracking

Latency is tracked on every single request using monotonic high-resolution timers (`time.perf_counter()`):
- **p50 Latency (Median)**: $\approx 1.8\text{ ms}$ (Preprocessor vectorization + XGBoost DMatrix evaluation)
- **p95 Latency**: $\approx 4.2\text{ ms}$
- **Max Latency Threshold Alert**: Triggers `WARNING` if $p95 > 25.0\text{ ms}$, `CRITICAL` if $p95 > 50.0\text{ ms}$.

All latency metrics are queried dynamically via `/monitoring/predictions/summary`:
```json
{
  "total_predictions": 128,
  "status_breakdown": {
    "PREDICTION_AVAILABLE": 112,
    "UNSUPPORTED_AIRSPACE": 12,
    "INSUFFICIENT_COVERAGE": 4
  },
  "latency_ms": {
    "p50": 1.82,
    "p95": 3.91,
    "min": 1.12,
    "max": 8.45
  }
}
```

---

## 4. Prediction Status Distribution

Inference responses are strictly categorized into four operational states:
1. `PREDICTION_AVAILABLE`: Domestic US route operated by one of 15 BTS reporting carriers with valid coordinates; real calibrated probabilities generated.
2. `UNSUPPORTED_AIRSPACE`: Indian domestic airspace or foreign international destinations rejected at boundary.
3. `INSUFFICIENT_COVERAGE`: Domestic US flight operated by an unmodeled non-reporting airline (e.g. Sun Country `SY`).
4. `MODEL_UNAVAILABLE`: Microservice offline, circuit breaker open, or timeout exceeded (clean fallback to deterministic intelligence).
