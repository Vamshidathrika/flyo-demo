# STAGE 13B — ML OBSERVABILITY & TELEMETRY CERTIFICATION

**Document Version:** 1.0.0  
**Audit Date:** 2026-11-10  
**Target Milestone:** Stage 13B — End-to-End Operational Certification, Data Provenance & Recovery Integrity  
**Component Audited:** Python ML Telemetry Microservice (Port 8000), FastAPI Router, SQLite Telemetry Store, Frontend Dashboard Normalization Engine  
**Test Suites:** Python Unittest (36/36 Passed), Frontend Behavior Tests (8/8 Passed)  

---

## 1. Executive Summary & Problem Resolution

During early Stage 12 testing, the ML Observability panel exhibited confusing and alarming behavior:
1. **Red Logs & Severe Warnings:** Telemetry logs appeared flooded with red/amber warnings that were difficult for flight dispatchers to interpret.
2. **`NaN%` and Multiplied Scale Anomalies:** Imputation cards displayed `NaN%` or `9200%` due to uncoordinated units between backend microservices (`0.92` vs `92.0`).
3. **Drift Misclassification:** Missing drift data defaulted to `STABLE`, falsely reassuring operators during upstream sensor outages.
4. **Frozen Refresh Button:** Rapid clicking of `Refresh Telemetry` caused UI lockouts and race conditions where older cached responses overwrote newer telemetry.

Stage 13B certifies the complete resolution of these defects through strict mathematical normalization contracts, monotonic request sequencing, transparent failure indicators, and simplified log categorizations.

---

## 2. Telemetry Endpoints & API Contract Specification

All telemetry endpoints are hosted on the Python FastAPI service (`http://localhost:8000/monitoring/*`) and backed by SQLite storage (`ml/artifacts/monitoring.db`):

| Endpoint | Method | Response Payload Schema | Key Metrics Surfaced | Status |
| :--- | :--- | :--- | :--- | :--- |
| `/monitoring/predictions/summary` | `GET` | `{"total_predictions": int, "window_hours": int, "avg_disruption_probability": float, "high_risk_count": int}` | Overall inference volume and risk distribution | **CERTIFIED** |
| `/monitoring/data-quality` | `GET` | `{"missing_duration_percentage": float, "imputation_rate": float, "completeness_score": float, "schema_violations": int}` | Missing sensor data, feature imputation rates, schema health | **CERTIFIED** |
| `/monitoring/drift` | `GET` | `{"max_psi": float, "drift_level": str, "features_drifted": list, "calculation_window_days": int}` | Feature drift via Population Stability Index (PSI) | **CERTIFIED** |
| `/monitoring/model-performance` | `GET` | `{"evaluated_flights_count": int, "brier_score": float, "roc_auc": float, "calibration_error": float}` | Ground-truth evaluated accuracy and probabilistic calibration | **CERTIFIED** |
| `/monitoring/alerts` | `GET` | `{"active_alerts": list[{"severity": str, "message": str, "source": str, "timestamp": str}]}` | Real-time threshold breach notifications | **CERTIFIED** |
| `/monitoring/governance` | `GET` | `{"model_version": str, "trained_at": str, "features": list, "fairness_metrics": dict}` | Model card metadata, bias/fairness parity, training provenance | **CERTIFIED** |

---

## 3. The Percentage Normalization Contract & Unit Ambiguity Resolution

### The Ambiguity of `1.0`
A central question addressed in Stage 13B was: *How should a raw numeric value of `1.0` be interpreted by the frontend?*
- If interpreted as a **percentage**, $1.0 = 1.0\%$.
- If interpreted as a **unit fraction**, $1.0 = 100.0\%$.

### Codified Normalization Contract
In airline and ML operations, metric fields with names containing `rate`, `ratio`, `score`, `index`, or `percentage` almost universally fall into one of two conventions:
1. Fractional decimal between $0.00$ and $1.00$ (e.g., scikit-learn metrics, accuracy, AUC).
2. Whole percentage between $0.0\%$ and $100.0\%$ (e.g., sensor completion rate).

The Flyo normalization function `normalizePercentage(v)` resolves this with absolute determinism:

```javascript
/**
 * Normalizes any numeric input into a formatted percentage string.
 * - Values <= 1.05 are treated as unit fractions (1.0 -> 100.0%, 0.92 -> 92.0%, 0.05 -> 5.0%)
 * - Values > 1.05 and <= 100.0 are treated as whole percentages (92.0 -> 92.0%, 15.5 -> 15.5%)
 * - Null, undefined, NaN, or out-of-range values return 'N/A'
 */
function normalizePercentage(val) {
  if (val === null || val === undefined || val === '') return 'N/A';
  const num = Number(val);
  if (isNaN(num)) return 'N/A';
  if (num < 0 || num > 100) return 'N/A';
  
  if (num <= 1.05) {
    return `${(num * 100).toFixed(1)}%`;
  }
  return `${num.toFixed(1)}%`;
}
```

### Empirical Validation Across 14 Edge Cases (from `test.mjs`):
- `normalizePercentage(0.92)` $\rightarrow$ `"92.0%"` (PASSED)
- `normalizePercentage(92.0)` $\rightarrow$ `"92.0%"` (PASSED)
- `normalizePercentage(1.0)` $\rightarrow$ `"100.0%"` (PASSED)
- `normalizePercentage(0.0)` $\rightarrow$ `"0.0%"` (PASSED)
- `normalizePercentage(100.0)` $\rightarrow$ `"100.0%"` (PASSED)
- `normalizePercentage(0.05)` $\rightarrow$ `"5.0%"` (PASSED)
- `normalizePercentage(5.0)` $\rightarrow$ `"5.0%"` (PASSED)
- `normalizePercentage(null)` $\rightarrow$ `"N/A"` (PASSED)
- `normalizePercentage(undefined)` $\rightarrow$ `"N/A"` (PASSED)
- `normalizePercentage(NaN)` $\rightarrow$ `"N/A"` (PASSED)
- `normalizePercentage('invalid')` $\rightarrow$ `"N/A"` (PASSED)
- `normalizePercentage(-5)` $\rightarrow$ `"N/A"` (PASSED)
- `normalizePercentage(150)` $\rightarrow$ `"N/A"` (PASSED)
- `normalizePercentage('0.9117')` $\rightarrow$ `"91.2%"` (PASSED)

---

## 4. Simplified, Dispatcher-Friendly Observability Logs

To eliminate panic caused by raw stack traces and debug output in the UI, telemetry logs were restructured into four clean, color-coded functional categories:

```
[SYSTEM INFO]  05:10:02 IST - Ingested disruption feed: 4 flights evaluated.
[DATA QUALITY] 05:10:05 IST - Weather sensor imputation active: 92.0% of missing convective indices filled via regional interpolation.
[MODEL DRIFT]  05:10:12 IST - Drift check completed: Feature PSI = 0.041 (Stable. No retraining required).
[CALIBRATION]  05:10:15 IST - Model Brier score = 0.082 across 1,420 historical flights (Well-calibrated).
```

- **Green (`badge-info`):** Routine operations, scheduled model health checks.
- **Amber (`badge-warning`):** Imputation rate $> 80\%$, moderate drift ($0.10 \le \text{PSI} < 0.25$).
- **Red (`badge-danger`):** High drift ($\text{PSI} \ge 0.25$), schema violation, or microservice unreachable.

---

## 5. Drift Integrity & Ground Truth Accounting

1. **Drift Truthfulness:** When the drift calculation fails or returns empty data, the UI displays `DRIFT: UNKNOWN` with an amber caution outline. Under no circumstances will it render a false `STABLE`.
2. **Ground Truth Accounting:** The dashboard explicitly separates:
   - **Total Monitored Flights:** Active flights in the current operations window (e.g., `42 flights`).
   - **Evaluated Flights (Ground Truth):** Completed flights where actual arrival times were compared against predictions (e.g., `1,420 flights`).
   - **Pending Ground Truth:** Active flights currently in the air where outcome verification is pending.

**ML Observability Certification Verdict:** **APPROVED & FULLY NORMALIZED**
