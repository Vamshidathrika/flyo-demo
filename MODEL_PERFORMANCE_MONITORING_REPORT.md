# Model Performance & Calibration Monitoring Report

**Flyo Disruption Rescue Platform &middot; Phase 4 Engineering Verification**  
**Module:** `ml/service/performance_evaluator.py`  
**Evaluation Scope:** All 3 Disruption Targets vs Held-Out BTS Baseline  

---

## 1. Multi-Target Evaluation Framework

Flyo monitors real-world predictive quality across three specialized modeling targets:
1. **Flight Cancellation Classifier (Binary)**: Calibrated probability that flight is cancelled before departure.
2. **Extreme Delay ($\ge 120$ min) Classifier (Binary)**: Calibrated probability that departure delay equals or exceeds 120 minutes.
3. **Continuous Departure Delay Regressor (Regression)**: Point prediction and expected delay minutes.

---

## 2. Metrics & Comparison vs Baseline

| Target | Metric | Observed Baseline (July 2024 Split) | Alert Threshold (Deterioration) |
| :--- | :--- | :---: | :---: |
| **Cancellation** | Brier Score | **0.02931** | $+0.0150$ |
| | ROC-AUC | **0.682** | $-0.100$ |
| | PR-AUC | **0.0841** | $-0.030$ |
| | ECE (Expected Calibration Error) | **0.0182** | $+0.040$ |
| **Delay $\ge 120$m** | Brier Score | **0.05739** | $+0.0200$ |
| | ROC-AUC | **0.595** | $-0.080$ |
| | PR-AUC | **0.0693** | $-0.025$ |
| | ECE | **0.0245** | $+0.050$ |
| **Continuous Delay** | MAE (Mean Absolute Error) | **18.89 min** | $+10.0\text{ min}$ |
| | RMSE | **34.12 min** | $+15.0\text{ min}$ |

---

## 3. Real-World Outcome Matching Evaluation

Real flights complete hours or days after predictions are made. When ground truth outcomes arrive via `/monitoring/outcomes/ingest`, Flyo computes live performance deltas.

Endpoint: `GET /monitoring/model-performance`

```json
{
  "evaluated_records_count": 84,
  "sufficient_data": true,
  "cancellation": {
    "brier_score": 0.0289,
    "baseline_brier_score": 0.02931,
    "brier_delta": -0.0004,
    "roc_auc": 0.701,
    "baseline_roc_auc": 0.682,
    "pr_auc": 0.0892,
    "ece": 0.0164,
    "confusion_matrix": { "tn": 80, "fp": 2, "fn": 1, "tp": 1 }
  },
  "delay_120m": {
    "brier_score": 0.0561,
    "baseline_brier_score": 0.05739,
    "brier_delta": -0.0013,
    "roc_auc": 0.612,
    "baseline_roc_auc": 0.595,
    "confusion_matrix": { "tn": 77, "fp": 3, "fn": 1, "tp": 3 }
  },
  "continuous_delay": {
    "mae": 17.65,
    "baseline_mae": 18.89,
    "mae_delta": -1.24,
    "rmse": 31.42,
    "baseline_rmse": 34.12
  },
  "alert_severity": "NORMAL"
}
```

---

## 4. Calibration & ECE Tracking

A model can have decent ROC-AUC but poor calibration. Because Flyo uses predicted probabilities to evaluate connection risk, **probability calibration (Brier score & ECE) is prioritized over raw accuracy**.
If observed ECE exceeds $0.060$, a `WARNING` alert is posted to prompt temperature scaling or isotonic recalibration.
