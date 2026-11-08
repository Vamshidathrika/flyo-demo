# Flyo ML Phase 2.5 — Model Retraining Report (v2 Multi-Season)

> **Model Release Version:** v2.0.0-multiseason  
> **Training Date:** November 5, 2026  
> **Historical Corpus:** 1.7M BTS domestic flights (January, February & July 2024)  
> **Leakage Boundary:** Strict $T - 2\text{h}$ Point-in-Time (All observed weather features eliminated)  
> **Artifacts Directory:** `ml/artifacts/`

---

## 1. Architectural Summary & Objectives

Phase 2.5 replaces the truncated Phase 2 models with multi-carrier, multi-season, leakage-free models. The objectives achieved:
1. **Full Airline Representation:** Trained on all 15 domestic reporting airlines (`WN`, `AA`, `DL`, `UA`, `OO`, `YX`, `MQ`, `NK`, `B6`, `AS`, `9E`, `OH`, `F9`, `G4`, `HA`).
2. **Multi-Seasonal Exposure:** Trained on Winter peak conditions (January 2024), calibrated on Late Winter conditions (February 2024), and evaluated on Summer convective storm conditions (July 2024).
3. **Leakage-Free Execution:** Zero post-departure or reanalysis observed weather features used.
4. **Three Distinct Supervised Models:**
   - **Model A (Cancellation Classifier v2):** Predicts flight cancellation probability prior to departure.
   - **Model B (Extreme Delay $\ge 120$m Classifier v2):** Predicts whether arrival delay will reach or exceed 120 minutes.
   - **Model C (Arrival Delay Continuous Regressor v2):** Estimates expected arrival delay in minutes for completed flights.

---

## 2. Chronological Dataset Partitioning

To test real-world seasonal generalization, dataset partitions follow strict temporal separation:

| Partition | Calendar Period | Season & Conditions | Sample Size Used | Cancellation Count & Rate | Extreme Delay $\ge 120$m Rate |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Training Partition** | Jan 1 – Jan 31, 2024 | Winter blizzards, Arctic vortex | **150,000 flights** | 5,568 (3.712%) | 5,459 (3.639%) |
| **Validation Partition** | Feb 1 – Feb 29, 2024 | Late winter, baseline calm | **50,000 flights** | 291 (0.582%) | 1,260 (2.520%) |
| **Held-Out Test Partition** | Jul 1 – Jul 31, 2024 | Summer travel peak, convective storms | **50,000 flights** | 1,481 (2.962%) | 2,617 (5.234%) |

---

## 3. Preprocessing & Feature Engineering (`preprocessor_v2.joblib`)

The feature preprocessor engineers **79 strictly leakage-free features**:

### 3.1 Schedule & Route Geometry (12 Features)
- `day_of_week` (1–7), `day_of_month` (1–31), `month` (1–12)
- `scheduled_dep_hour` (0–23), `scheduled_arr_hour` (0–23)
- `scheduled_elapsed_minutes`, `distance_miles`
- `is_dep_morning_peak` (06:00–09:59), `is_dep_evening_peak` (16:00–19:59), `is_dep_night` (21:00–05:59)
- `is_transcon` ($\ge 2000$ miles), `is_short_hop` ($\le 400$ miles)

### 3.2 Point-in-Time Historical Training Priors (9 Features)
Calculated **strictly on the January training partition** (zero lookahead into February or July):
- `carrier_prior_cancel_rate`, `carrier_prior_delay120_rate`, `carrier_prior_mean_delay`
- `origin_prior_cancel_rate`, `origin_prior_delay120_rate`
- `dest_prior_cancel_rate`, `dest_prior_delay120_rate`
- `route_prior_cancel_rate`, `route_prior_delay120_rate` (origin-dest pair rate)

### 3.3 Categorical Encodings (58 One-Hot Features)
- **16 Carrier Indicators:** `carrier_WN`, `carrier_AA`, `carrier_DL`, `carrier_UA`, `carrier_OO`, `carrier_YX`, `carrier_MQ`, `carrier_NK`, `carrier_B6`, `carrier_AS`, `carrier_9E`, `carrier_OH`, `carrier_F9`, `carrier_G4`, `carrier_HA`, `carrier_other`.
- **21 Origin Hub Indicators:** `origin_ATL`, `origin_ORD`, `origin_DFW`, `origin_DEN`, `origin_CLT`, `origin_LAX`, `origin_PHX`, `origin_MCO`, `origin_LAS`, `origin_SEA`, `origin_MIA`, `origin_IAH`, `origin_JFK`, `origin_EWR`, `origin_FLL`, `origin_MSP`, `origin_SFO`, `origin_DTW`, `origin_BOS`, `origin_SLC`, `origin_other`.
- **21 Destination Hub Indicators:** Matching 20 hubs plus `dest_other`.

---

## 4. Model Hyperparameters & Calibration Pipeline

### 4.1 Model A: Cancellation Classifier v2
```python
xgb.XGBClassifier(
    n_estimators=300,
    max_depth=6,
    learning_rate=0.04,
    scale_pos_weight=25.94, # Corrects for 25.9:1 training class imbalance
    tree_method="hist",     # High-speed binned histogram algorithm
    subsample=0.85,
    colsample_bytree=0.85,
    eval_metric=["logloss", "aucpr"],
    early_stopping_rounds=25,
    random_state=42
)
```
- **Post-Hoc Probability Calibration:** Wrapped in `FrozenEstimator(xgb_c)` and calibrated via `CalibratedClassifierCV(method='sigmoid')` fit strictly on the 50,000 February validation split.
- **Serialized Artifacts:** `xgb_cancellation_v2.json` (571 KB), `calibrated_cancellation_v2.joblib` (411 KB).

### 4.2 Model B: Extreme Delay ($\ge 120$m) Classifier v2
```python
xgb.XGBClassifier(
    n_estimators=300,
    max_depth=6,
    learning_rate=0.04,
    scale_pos_weight=26.48, # Corrects for 26.5:1 training delay imbalance
    tree_method="hist",
    subsample=0.85,
    colsample_bytree=0.85,
    eval_metric=["logloss", "aucpr"],
    early_stopping_rounds=25,
    random_state=42
)
```
- **Post-Hoc Probability Calibration:** Wrapped in `FrozenEstimator(xgb_d)` and calibrated via `CalibratedClassifierCV(method='sigmoid')` fit on the February validation split.
- **Serialized Artifacts:** `xgb_delay120_v2.json` (178 KB), `calibrated_delay120_v2.joblib` (139 KB).

### 4.3 Model C: Continuous Arrival Delay Regressor v2
```python
xgb.XGBRegressor(
    n_estimators=350,
    max_depth=6,
    learning_rate=0.04,
    tree_method="hist",
    subsample=0.85,
    colsample_bytree=0.85,
    objective="reg:squarederror",
    eval_metric="mae",
    early_stopping_rounds=30,
    random_state=42
)
```
- **Trained Strictly on Completed Flights:** Filtered to non-cancelled flights (`is_cancelled == 0`).
- **Serialized Artifact:** `xgb_delay_regressor_v2.json` (213 KB).

---

## 5. Rollback Preservation

The previous Phase 2 model artifacts have been archived in `ml/artifacts/v1_backup/`:
- `xgb_cancellation_v1.json`
- `calibrated_cancellation_v1.joblib`
- `xgb_delay120_v1.json`
- `calibrated_delay120_v1.joblib`
- `xgb_delay_regressor_v1.json`
- `preprocessor_v1.joblib`
- `feature_schema.json`

If rollback is ever required, the v1 models can be restored in seconds without affecting the repository.
