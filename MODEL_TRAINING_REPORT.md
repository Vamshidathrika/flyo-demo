# Flyo Predictive Intelligence v1 — Model Training Report

> **Project Component:** Machine Learning Disruption Forecasting  
> **Status:** Phase 2 Complete — Models Trained & Serialized  
> **Training Source:** US Department of Transportation Bureau of Transportation Statistics (BTS) + Open-Meteo Historical Weather Archive  
> **Evaluation Framework:** Strict Out-of-Time Temporal Split (Days 1–20 Train, Days 21–25 Validation, Days 26–31 Held-Out Test)  
> **Artifacts Directory:** `ml/artifacts/`

---

## 1. Executive Summary & Objective

In Phase 1, Flyo acquired a genuine historical dataset of **50,000 real domestic US flights** from January 2024 and matched **88.11%** of flights to hourly airport weather observations across 15 major US hubs.

Phase 2 transitions the Flyo platform from deterministic risk heuristics into supervised machine learning. We have built, trained, calibrated, and serialized three predictive models alongside baseline models:
1. **Model A (Cancellation Classifier):** XGBoost binary classifier predicting the probability that a flight will be cancelled prior to departure ($y_{\text{cancel}} \in \{0, 1\}$).
2. **Model B (Extreme Delay Classifier):** XGBoost binary classifier predicting the probability that arrival delay will reach or exceed **120 minutes** ($y_{\text{delay\_120}} \in \{0, 1\}$).
3. **Model C (Arrival Delay Continuous Regressor):** XGBoost regression model estimating continuous arrival delay in minutes for completed flights ($y_{\text{arr\_delay}} \in \mathbb{R}$).

All features are strictly constrained to information observable at **$T_{\text{predict}} = \text{Scheduled Departure } - 2\text{ hours}$**, preventing all forms of target and temporal leakage.

---

## 2. Dataset Architecture & Temporal Splitting

### 2.1 Dataset Partitioning Strategy
To avoid the optimistic bias of random K-fold cross-validation in temporal time-series, all records are partitioned chronologically:

| Split | Date Range | Flight Count | Cancellation Count | Cancellation Rate | Delay $\ge 120$m Count | Delay $\ge 120$m Rate |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Train Set** | Jan 1 – Jan 20, 2024 | 31,935 | 1,081 | **3.385%** | 1,856 | **5.812%** |
| **Validation Set** | Jan 21 – Jan 25, 2024 | 8,343 | 120 | **1.438%** | 319 | **3.824%** |
| **Held-Out Test Set** | Jan 26 – Jan 31, 2024 | 9,722 | 28 | **0.288%** | 240 | **2.469%** |
| **Total** | Jan 1 – Jan 31, 2024 | 50,000 | 1,229 | 2.458% | 2,415 | 4.830% |

> [!NOTE]
> The temporal split exposes a real-world meteorological distribution shift: early January 2024 experienced widespread winter storms (Winter Storm Gerri / Heather), elevating cancellations to 3.39%. Late January experienced mild, clear conditions, dropping cancellations to 0.29%. This provides an authentic test of model robustness under temporal covariate shift.

---

## 3. Leakage-Free Feature Engineering ($T_{\text{predict}} = \text{Dep } - 2\text{h}$)

A total of **69 leakage-free features** were engineered by `FlightDataPreprocessor`:

### 3.1 Temporal & Schedule Features
- `day_of_week` (1 to 7)
- `day_of_month` (1 to 31)
- `scheduled_dep_hour` (0 to 23)
- `scheduled_arr_hour` (0 to 23)
- `scheduled_elapsed_minutes` (CRS scheduled duration)
- `distance_miles` (great circle distance)
- `is_dep_morning_peak` (Binary: 06:00 – 09:59)
- `is_dep_evening_peak` (Binary: 16:00 – 19:59)
- `is_dep_night` (Binary: 21:00 – 05:59)

### 3.2 Hourly Weather Signals (Origin & Destination)
- `origin_temp_c`, `dest_temp_c` (2m temperature in Celsius)
- `origin_wind_speed_kmh`, `dest_wind_speed_kmh` (10m wind speed in km/h)
- `origin_is_freezing`, `dest_is_freezing` (Binary: $\le 0.0^\circ\text{C}$)
- `origin_is_high_wind`, `dest_is_high_wind` (Binary: $\ge 30.0\text{ km/h}$)
- `origin_is_adverse`, `dest_is_adverse` (Binary: WMO codes for snow, freezing rain, drizzle, thunderstorms)
- `has_origin_weather`, `has_dest_weather` (Binary missingness indicators)

### 3.3 Historical Empirical Priors (Fit Exclusively on Train Split)
To eliminate lookahead bias, empirical rates were computed **strictly on Days 1–20**:
- `carrier_prior_cancel_rate`
- `carrier_prior_delay120_rate`
- `carrier_prior_mean_delay`
- `origin_prior_cancel_rate`
- `origin_prior_delay120_rate`
- `dest_prior_cancel_rate`
- `dest_prior_delay120_rate`

### 3.4 Categorical Encodings (One-Hot)
- Carrier indicators for top carriers: `AA`, `DL`, `UA`, `WN`, `B6`, `AS`, `NK`, `F9`, plus `carrier_other`
- Airport indicators for top 15 hubs: `ATL`, `ORD`, `DFW`, `DEN`, `LAX`, `JFK`, `SFO`, `SEA`, `MIA`, `BOS`, `EWR`, `CLT`, `PHX`, `IAH`, `LGA`, plus `origin_other` and `dest_other`.

### 3.5 Prohibited Variables (Verified Excluded)
The following fields were strictly barred from the feature matrix:
- `actual_dep_time`, `dep_delay_minutes`, `actual_arr_time`, `actual_elapsed_minutes`, `is_delayed_15m`, `is_delayed_120m`, `is_cancelled`, `cancellation_code`, `cancellation_reason`, `carrier_delay`, `weather_delay`, `nas_delay`, `security_delay`, `late_aircraft_delay`.

---

## 4. Model Architectures & Hyperparameters

### 4.1 Baselines
- **Dummy Classifiers:** `DummyClassifier(strategy='prior')` predicting the empirical training base rate (3.385% cancellation, 5.812% delay $\ge 120$m).
- **Linear Classifiers:** `StandardScaler` + `LogisticRegression(class_weight='balanced', max_iter=1000)`.
- **Regression Baselines:** `DummyRegressor(strategy='mean')` and `StandardScaler` + `Ridge(alpha=100.0)`.

### 4.2 Supervised Model A: Cancellation Classifier
```python
xgb.XGBClassifier(
    n_estimators=300,
    max_depth=5,
    learning_rate=0.05,
    scale_pos_weight=28.54, # Addresses 28.5:1 train negative:positive imbalance
    subsample=0.8,
    colsample_bytree=0.8,
    eval_metric=["logloss", "aucpr"],
    early_stopping_rounds=25,
    random_state=42
)
```
- **Probability Calibration:** `CalibratedClassifierCV(estimator=FrozenEstimator(xgb_c), method='sigmoid')` fit on the validation split (Days 21–25).

### 4.3 Supervised Model B: Extreme Delay ($\ge 120$m) Classifier
```python
xgb.XGBClassifier(
    n_estimators=300,
    max_depth=5,
    learning_rate=0.05,
    scale_pos_weight=16.21, # Addresses 16.2:1 train imbalance
    subsample=0.8,
    colsample_bytree=0.8,
    eval_metric=["logloss", "aucpr"],
    early_stopping_rounds=25,
    random_state=42
)
```
- **Probability Calibration:** `CalibratedClassifierCV(estimator=FrozenEstimator(xgb_d), method='sigmoid')` fit on the validation split.

### 4.4 Supervised Model C: Continuous Arrival Delay Regressor
```python
xgb.XGBRegressor(
    n_estimators=350,
    max_depth=6,
    learning_rate=0.04,
    subsample=0.85,
    colsample_bytree=0.85,
    objective="reg:squarederror",
    eval_metric="mae",
    early_stopping_rounds=30,
    random_state=42
)
```
Trained strictly on completed flights (`is_cancelled == 0`).

---

## 5. Serialized Artifact Inventory

All models, preprocessing metadata, and evaluation results have been serialized into `ml/artifacts/`:

| Artifact | File Size | Description |
| :--- | :--- | :--- |
| `preprocessor_v1.joblib` | 13 KB | Fitted preprocessor containing imputation medians, training priors, and feature column schemas |
| `feature_schema.json` | 1.7 KB | JSON specification of the 69 feature names and temporal partition counts |
| `xgb_cancellation_v1.json` | 88 KB | Native XGBoost booster for flight cancellation prediction |
| `calibrated_cancellation_v1.joblib` | 85 KB | Sigmoid probability-calibrated cancellation estimator |
| `xgb_delay120_v1.json` | 366 KB | Native XGBoost booster for arrival delay $\ge 120$ minutes |
| `calibrated_delay120_v1.joblib` | 293 KB | Sigmoid probability-calibrated extreme delay estimator |
| `xgb_delay_regressor_v1.json` | 201 KB | Native XGBoost booster for continuous arrival delay estimation |
| `all_models_bundle.joblib` | 551 KB | Combined bundle of all baselines and supervised models for evaluation |
| `evaluation_metrics.json` | 4.8 KB | Full out-of-time evaluation metrics, threshold tables, and segment breakdowns |
| `feature_importance.json` | 3.2 KB | Gain importances and SHAP TreeExplainer attributions |
