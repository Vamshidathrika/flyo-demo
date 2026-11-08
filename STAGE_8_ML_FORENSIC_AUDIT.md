# STAGE 8 — COMPLETE ML FORENSIC AUDIT & SUBSYSTEM INSPECTION

**Document ID:** `FLYO-STG8-AUDIT-001`  
**Classification:** Machine Learning Engineering Forensic Audit  
**Target Subsystem:** `ml/` (Python ML Engine) & Backend Integration (`com.flyo.rescue.client.PredictionServiceClient`)  
**Auditor:** Principal AI/ML Engineer & Systems Architect  
**Date:** November 2026  

---

## 1. Executive Summary

This forensic audit evaluates Flyo's existing machine learning subsystem (referred to as `v2.0.0-multiseason`) prior to Stage 8 development. Rather than relying on previous summary documentation, this audit examines the actual Python source code (`ml/src/`, `ml/service/`), trained model serialization artifacts (`ml/artifacts/`), database schemas (`ml/data/monitoring.db`), and Spring Boot client integration (`PredictionServiceClient.java`).

### Key Audit Findings
1. **Model Scope & Geography:** The existing model was trained strictly on US domestic Bureau of Transportation Statistics (BTS) data from January, February, and July 2024. **Zero Indian flights, Indian carriers, or Indian airports exist in the training data.**
2. **Hardcoded Airspace Geofencing:** Because of the US-only training corpus, the Spring Boot backend (`PredictionServiceClient.java`) and FastAPI service (`predictor.py`) explicitly contain hardcoded geofences that intercept all Indian airports (`DEL`, `BOM`, `BLR`, `HYD`, `MAA`, etc.) and Indian carriers (`6E`, `AI`, `UK`, `SG`, `QP`) and return `UNSUPPORTED_AIRSPACE` without invoking statistical inference.
3. **Severe Class Imbalance & Threshold Collapse:** While the cancellation classifier reports a calibrated Brier score of `0.0293` on the held-out test split, its ROC-AUC is only `0.5279` (barely above random guessing at 0.50), and PR-AUC is `0.0318`. At standard decision thresholds ($\ge 0.05, \ge 0.10, \ge 0.20$), the model flagged **0 true positives** (Precision = 0.0, Recall = 0.0).
4. **Regression Collapse to Mean:** The XGBoost continuous delay regressor achieves an MAE of `34.00 minutes` on the held-out test partition—essentially matching the naive dummy mean baseline of `34.05 minutes`.
5. **Weather Lookahead Fix Left a Signal Vacuum:** While the v2 preprocessor successfully removed post-event weather lookahead leakage, it completely stripped all weather features from the runtime feature matrix ($X$), depriving the model of the primary physical driver of severe disruptions.
6. **Absence of Explainability:** The existing service generates static text strings (`PEAK_MORNING_DEPARTURE`, `ORIGIN_CONGESTION_HUB`) via hand-crafted if-else rules rather than true statistical feature attributions such as SHAP.

---

## 2. Inventory of Existing ML Artifacts & Source Code

### A. Serialized Model Artifacts (`ml/artifacts/`)
| Artifact File | Size | Format | Description & Role |
|---|---|---|---|
| `xgb_cancellation_v2.json` | ~450 KB | XGBoost JSON | Raw gradient boosted decision trees for cancellation binary classification |
| `calibrated_cancellation_v2.joblib` | ~1.2 KB | Scikit-Learn Joblib | Sigmoid/Platt calibrated probability wrapper for cancellation model |
| `xgb_delay120_v2.json` | ~520 KB | XGBoost JSON | Raw booster predicting extreme arrival delay ($\ge 120$ minutes) |
| `calibrated_delay120_v2.joblib` | ~1.2 KB | Scikit-Learn Joblib | Sigmoid calibrated wrapper for extreme delay classification |
| `xgb_delay_regressor_v2.json` | ~680 KB | XGBoost JSON | Continuous arrival delay regressor predicting expected delay minutes |
| `preprocessor_v2.joblib` | ~180 KB | Joblib Pickle | `LeakageFreePreprocessor` holding fitted training priors (carrier, origin, dest, route rates) |
| `feature_schema_v2.json` | ~3.8 KB | JSON Schema | 79 feature names, dataset date boundaries, and supported hubs |
| `evaluation_metrics_v2.json` | ~7.2 KB | JSON | Empirical validation and test metrics on 50,000 held-out summer flights |
| `baseline_reference_distributions.json`| ~4.5 KB | JSON | Empirical quantile references for Kolmogorov-Smirnov drift detection |

### B. Python Subsystem Source Code
- `ml/src/leakage_free_preprocessor.py`: Implements 79-feature point-in-time transform ($T_{\text{predict}} = T_{\text{dep}} - 2\text{h}$).
- `ml/src/train_v2.py`: Multi-season chronological training script (Train: Jan 2024, Val: Feb 2024, Test: Jul 2024).
- `ml/service/main.py`: FastAPI application exposing `/predict/flight`, `/predict/batch`, and `/monitoring/*`.
- `ml/service/predictor.py`: `FlightPredictor` service executing geofence checks, feature vectorization, and database logging.
- `ml/service/monitoring_db.py`: SQLite persistence for inference logs, data quality stats, and drift alerts (`ml/data/monitoring.db`).
- `ml/service/drift_detector.py`: KS-test and PSI statistical drift evaluator comparing live inputs against training baselines.

---

## 3. Training Data Forensic Analysis

### Data Sources & Volume
The training corpus was extracted from the US Bureau of Transportation Statistics (BTS) *On-Time Reporting Carrier On-Time Performance* records for calendar year 2024:
- **Partition 1 (Train):** January 2024 (`flights_2024_01.csv`, 547,271 records, 150,000 sampled for training).
- **Partition 2 (Validation):** February 2024 (`flights_2024_02.csv`, 482,109 records, 50,000 sampled for validation).
- **Partition 3 (Held-Out Test):** July 2024 (`flights_2024_07.csv`, 601,420 records, 50,000 sampled for test).

### Representation Limitations
- **Carriers Represented:** Exactly 15 US major carriers (`WN`, `AA`, `DL`, `UA`, `OO`, `YX`, `MQ`, `NK`, `B6`, `AS`, `9E`, `OH`, `F9`, `G4`, `HA`).
- **Airports Represented:** 334 US domestic facilities; top 20 hubs explicitly one-hot encoded (`ATL`, `ORD`, `DFW`, `DEN`, `CLT`, `LAX`, etc.).
- **Indian Coverage:** **0.0%**. No Indian airlines (IndiGo, Air India, Vistara, SpiceJet, Akasa) or Indian airports (`DEL`, `BOM`, `BLR`, `HYD`, `MAA`, `CCU`) are represented.

---

## 4. Empirical Performance Evaluation (Test Split: 50,000 Flights)

From actual recorded evaluation metrics in `evaluation_metrics_v2.json`:

### A. Flight Cancellation Classification
- **Prevalence in Test Set:** 1,481 / 50,000 (2.96%)
- **ROC-AUC:** `0.5279` (Baseline Dummy: `0.5000`, Logistic Regression: `0.4446`)
- **PR-AUC:** `0.0318` (Baseline Dummy: `0.0296`, Logistic Regression: `0.0266`)
- **Brier Score:** `0.0293` (Baseline Dummy: `0.0288`)
- **Expected Calibration Error (ECE):** `0.0239`
- **Confusion Matrix at 10% Probability Threshold:**
  $$\begin{pmatrix} \text{TN}=48,519 & \text{FP}=0 \\ \text{FN}=1,481 & \text{TP}=0 \end{pmatrix}$$
- **Diagnosis:** The classifier exhibits complete threshold collapse. Because probability calibration compressed all outputs into a narrow band below 0.08, zero flights were flagged, yielding Recall = 0.0%.

### B. Extreme Delay ($\ge 120$ Minutes) Classification
- **Prevalence in Test Set:** 2,617 / 50,000 (5.23%)
- **ROC-AUC:** `0.5950` (Baseline Dummy: `0.5000`, Logistic Regression: `0.5679`)
- **PR-AUC:** `0.0693` (Baseline Dummy: `0.0523`, Logistic Regression: `0.0633`)
- **Brier Score:** `0.0508`
- **Confusion Matrix at 15% Probability Threshold:**
  $$\begin{pmatrix} \text{TN}=47,383 & \text{FP}=0 \\ \text{FN}=2,617 & \text{TP}=0 \end{pmatrix}$$

### C. Continuous Delay Regressor (Expected Delay Minutes)
- **XGBoost Regressor v2 MAE:** `34.00 minutes`
- **Dummy Baseline (Mean) MAE:** `34.05 minutes`
- **Ridge Baseline MAE:** `34.34 minutes`
- **Root Mean Squared Error (RMSE):** `72.99 minutes` vs Dummy `73.03 minutes`
- **Diagnosis:** The regressor provides virtually zero predictive gain over predicting the static mean delay.

---

## 5. Architectural & Integration Disconnects

1. **Backend Failsafe vs ML Value:** The Spring Boot backend's geofencing check in `PredictionServiceClient.java` correctly prevented fraudulent inferences on Indian routes. However, this means that in the primary commercial target market (India), 100% of cases bypass the ML system and fall back to static rule-based heuristics.
2. **No Connection-Risk Awareness:** The ML service evaluates individual flight legs in isolation. It possesses no awareness of connecting passenger itineraries, minimum connection times (MCT), terminal transfers (e.g., DEL Terminal 3 to Terminal 1), or passenger vulnerability.
3. **No Passenger Impact Estimation:** The model outputs single-flight probabilities but cannot estimate affected passenger volume, rebooking demand, or operations workload.
4. **Missing Weather Integration:** Although Open-Meteo weather client code exists in the Java backend (`OpenMeteoWeatherService.java`), the ML service receives neither live METAR observations nor forecast weather vectors during inference.

---

## 6. Stage 8 Remediation Strategy

To elevate Flyo from an isolated US BTS prototype into a true enterprise predictive intelligence platform, Stage 8 must execute:
1. **Indian Corridors Knowledge Architecture:** Model priority domestic routes (HYD-DEL, HYD-BOM, HYD-BLR, DEL-BOM, BLR-MAA, BOM-BLR) with explicit operational priors (monsoon convection, winter fog/CAT-III, hub congestion).
2. **Point-in-Time Weather & Operational Fusion:** Restore weather vectors (visibility, wind speed, precipitation, thunderstorms) with strict timestamp validation to avoid lookahead while capturing physical causes.
3. **Multi-Horizon Delay Forecasting:** Predict expected delay and confidence intervals ($P_{10}, P_{50}, P_{90}$) at operational horizons: $T-24\text{h}$, $T-6\text{h}$, $T-3\text{h}$, $T-1\text{h}$.
4. **Connection Risk & Passenger Impact Engine:** Model connection failure probability as a function of inbound delay, MCT, and terminal transfer; estimate affected passenger counts and rescue workloads.
5. **SHAP Feature Explainability:** Provide exact directional feature attributions per prediction so airline dispatchers understand *why* a flight is flagged.
6. **FastAPI & Spring Boot Integration:** Expose versioned endpoints for all four predictive capabilities and display them within the frontend Operations Control Center.
