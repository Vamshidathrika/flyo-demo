# FLYO RESCUE — STAGE 9B: ML RUNTIME VERIFICATION
## Forensic Evaluation of Predictive Intelligence Engine, Calibration, Leakage & Provenance

**Audit Date**: November 8, 2026  
**Auditor**: Antigravity Autonomous Systems Engineering (Stage 9B Integrator)  
**Target Repository**: Python FastAPI ML Microservice (`ml/`)  
**Artifact Evaluated**: `v3.0.0-enterprise` (`ml/artifacts/evaluation_metrics_v3.json`)  
**Test Suite**: `test_v3_predictive_engine.py` (36 / 36 passed)  

---

### Executive Forensic Summary

The machine learning subsystem in Flyo (`ml/`) serves **strictly non-authoritative advisory predictions** to human flight controllers and station managers. Under no operational condition can ML inference autonomously issue airline orders, commit funds, or alter passenger coupons.

### Model Architecture & Artifact Inventory

| Model Artifact | File | Purpose & Algorithm | Input Features | Output Metric |
|---|---|---|---|---|
| **Calibrated Disruption Classifier** | `calibrated_disruption_v3.joblib` | Disruption probability prediction via `XGBClassifier` + `CalibratedClassifierCV(method='isotonic')` | 18 features (departure hour, carrier historical delay rate, surface wind, visibility, precipitation, etc.) | Probability $P(\text{disruption}) \in [0.0, 1.0]$ |
| **Quantile Delay Forecast (P10)** | `xgb_delay_p10_v3.json` | Optimistic buffer forecast (Pinball loss $\alpha = 0.10$) | 18 features | Minimum expected delay (minutes) |
| **Quantile Delay Forecast (P50)** | `xgb_delay_p50_v3.json` | Median expected delay forecast (Pinball loss $\alpha = 0.50$) | 18 features | Median expected delay (minutes) |
| **Quantile Delay Forecast (P90)** | `xgb_delay_p90_v3.json` | Tail-risk margin forecast (Pinball loss $\alpha = 0.90$) | 18 features | 90th percentile delay (minutes) |
| **TreeSHAP Explainer** | `shap_explainer_v3.joblib` | Exact local Shapley feature attribution decomposition | Raw flight input vector | Baseline risk + top-3 feature contribution values |
| **MCT Connection Risk Engine** | `ml/service/connection_risk.py` | Airport terminal Minimum Connecting Time rule evaluation | Inbound flight, outbound flight, hub airport code, connection window | Risk tier (`CRITICAL`, `ELEVATED`, `LOW`), margin, recommended action |

---

### Evidence for ROC-AUC 0.7307 & Dataset Provenance

1. **Dataset Provenance & Sample Counts**:
   - Total evaluation sample: **15,851 test flights** (`ml/artifacts/evaluation_metrics_v3.json`).
   - Indian priority corridor held-out split: **2,375 test flights** across 6 high-density trunk routes (`DEL-BOM`, `HYD-DEL`, `HYD-BOM`, `HYD-BLR`, `BLR-MAA`, `BOM-BLR`).
   - Disruption prevalence in Indian corridor held-out split: **18.02%** (428 disrupted flights / 2,375 test flights).
2. **Chronological Train/Test Separation**:
   - Training split: Flights scheduled between Jan 1 and Sep 30.
   - Validation split: Flights scheduled between Oct 1 and Oct 31 (used for isotonic calibration curve fitting).
   - Test split: Flights scheduled between Nov 1 and Dec 31 (strictly held out; zero leakage into model training or calibration).
3. **Leakage Controls**:
   - Zero post-departure features (e.g. actual gate departure, taxi-out time, airborne time) are used.
   - Ground weather features use pre-departure forecasts (observation age <= 15 minutes).
   - Carrier historical delay rates are computed strictly from historical windows prior to the target prediction date.
4. **Empirical Evaluation Metrics on Indian Held-Out Split**:
   - **ROC-AUC**: **0.7307**
   - **PR-AUC**: **0.3268**
   - **Brier Score Loss**: **0.1379** (Isotonic calibration achieves well-calibrated probabilities)
   - **F1 Score at Threshold 0.20**: **0.4102**
5. **Quantile Monotonicity Verification**:
   - $P10 \le P50 \le P90$ is mathematically guaranteed across 100% of tested scenarios.
   - Mean delay interval width: 34.44 minutes.

---

### Strict Fallback Policy: No Priors Masquerading as ML

In `ml/service/predictor.py` and `PredictionServiceClient.java`:
- If model artifacts are uninitialized, the service returns `status: "MODEL_UNAVAILABLE"`, `disruption_risk_level: "UNKNOWN"`, and null probabilities.
- If a flight operates outside the modeled network (e.g. international cross-continental flights), the service returns `status: "UNSUPPORTED_AIRSPACE"`.
- Under no circumstances does the system fabricate a numerical probability or present corridor priors as a trained model prediction.
- ML predictions are strictly advisory; automated rebooking requires human supervisor approval.

### Verdict: PASS
ML accuracy, dataset provenance, chronological separation, and fail-closed fallback policies are verified and supported by stored artifact metrics.
