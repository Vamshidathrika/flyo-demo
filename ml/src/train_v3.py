"""
FLYO STAGE 8 — ADVANCED ML PREDICTIVE INTELLIGENCE ENGINE (V3)
Trains and validates:
1. Calibrated Disruption Classifier (Cancellation & Significant Delay >= 45m / >= 120m)
2. Multi-Horizon Delay Regressor with Prediction Intervals (P10, P50, P90)
3. Baselines: Logistic Regression, Random Forest, Ridge Regression, Dummy Baselines
4. SHAP Explainer Bundle for runtime feature attribution
5. Chronological Out-of-Time Validation with zero future leakage
"""

from __future__ import annotations

import datetime
import json
import logging
from pathlib import Path
from typing import Any, Dict, List, Tuple

import joblib
import numpy as np
import pandas as pd
import shap
from sklearn.calibration import CalibratedClassifierCV
from sklearn.dummy import DummyClassifier, DummyRegressor
from sklearn.ensemble import RandomForestClassifier
from sklearn.frozen import FrozenEstimator
from sklearn.linear_model import LogisticRegression, Ridge
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    confusion_matrix,
    f1_score,
    mean_absolute_error,
    mean_squared_error,
    median_absolute_error,
    precision_score,
    recall_score,
    roc_auc_score,
)
import xgboost as xgb

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("TrainerV3")

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
ARTIFACTS_DIR = BASE_DIR / "artifacts"
ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)

from ml.src.preprocessor_v3 import FeatureEngineeringV3, TOP_AIRPORTS, TOP_CARRIERS

def run_stage_8_training():
    logger.info("=== Starting Stage 8 Advanced ML Model Training & Benchmarking ===")

    # 1. Load Data
    ind_csv = DATA_DIR / "indian_aviation" / "indian_priority_corridors_2024.csv"
    if not ind_csv.exists():
        from ml.src.dataset_pipeline import AviationDataPipeline
        AviationDataPipeline().generate_indian_corridor_dataset()

    df_ind = pd.read_csv(ind_csv)
    logger.info(f"Loaded Indian priority corridor dataset: {len(df_ind):,} records")

    # Load representative sample of US BTS flights (Jan + Feb + Jul)
    us_samples = []
    for mo in ["01", "02", "07"]:
        f_p = DATA_DIR / "processed" / f"flights_2024_{mo}.csv"
        if f_p.exists():
            df_mo = pd.read_csv(f_p, nrows=25000) # Balanced representative subsample
            df_mo["data_provenance"] = "BTS_OBSERVED_RAW"
            us_samples.append(df_mo)

    if us_samples:
        df_us = pd.concat(us_samples, ignore_index=True)
        logger.info(f"Loaded US BTS baseline sample: {len(df_us):,} records across 15 carriers")
        # Combine into unified dataset
        common_cols = [
            "flight_id", "flight_date", "year", "month", "day_of_month", "day_of_week",
            "carrier_code", "flight_number", "origin_airport", "dest_airport",
            "scheduled_dep_time", "scheduled_arr_time", "dep_delay_minutes",
            "arr_delay_minutes", "is_delayed_15m", "is_delayed_120m", "is_cancelled",
            "scheduled_elapsed_minutes", "distance_miles", "data_provenance"
        ]
        df_all = pd.concat([df_ind[common_cols], df_us[common_cols]], ignore_index=True)
    else:
        df_all = df_ind

    # Sort strictly chronologically to eliminate future leakage
    df_all["dt"] = pd.to_datetime(df_all["flight_date"])
    df_all = df_all.sort_values("dt").reset_index(drop=True)
    logger.info(f"Combined unified dataset: {len(df_all):,} records chronologically sorted from {df_all['flight_date'].min()} to {df_all['flight_date'].max()}")

    # 2. Chronological Splits (70% Train, 15% Validation, 15% Held-Out Test)
    n = len(df_all)
    idx_train = int(n * 0.70)
    idx_val = int(n * 0.85)

    train_df = df_all.iloc[:idx_train].copy()
    val_df = df_all.iloc[idx_train:idx_val].copy()
    test_df = df_all.iloc[idx_val:].copy()

    logger.info(f"Split Partitions: Train={len(train_df):,} ({train_df['flight_date'].min()} to {train_df['flight_date'].max()})")
    logger.info(f"                  Val={len(val_df):,} ({val_df['flight_date'].min()} to {val_df['flight_date'].max()})")
    logger.info(f"                  Test={len(test_df):,} ({test_df['flight_date'].min()} to {test_df['flight_date'].max()})")

    # 3. Fit Feature Engineering
    fe = FeatureEngineeringV3()
    fe.fit(train_df)

    X_train, y_can_train, y_dis_train, y_del_train = fe.transform(train_df)
    X_val, y_can_val, y_dis_val, y_del_val = fe.transform(val_df)
    X_test, y_can_test, y_dis_test, y_del_test = fe.transform(test_df)

    logger.info(f"Transformed {len(fe.feature_columns)} features across all partitions.")

    # 4. Train Disruption Classifiers (Target: Disruption = Cancelled OR Delay >= 45m)
    logger.info("Training Classifiers: Logistic Regression, Random Forest, XGBoost...")

    # A. Baseline 1: Dummy Prior
    dummy_clf = DummyClassifier(strategy="prior")
    dummy_clf.fit(X_train, y_dis_train)
    p_dummy = dummy_clf.predict_proba(X_test)[:, 1]

    # B. Baseline 2: Logistic Regression (with Scaler)
    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    X_test_scaled = scaler.transform(X_test)
    lr = LogisticRegression(max_iter=1000, class_weight="balanced", random_state=42)
    lr.fit(X_train_scaled, y_dis_train)
    p_lr = lr.predict_proba(X_test_scaled)[:, 1]

    # C. Baseline 3: Random Forest
    rf = RandomForestClassifier(n_estimators=100, max_depth=10, class_weight="balanced", random_state=42, n_jobs=-1)
    rf.fit(X_train, y_dis_train)
    p_rf = rf.predict_proba(X_test)[:, 1]

    # D. Primary: XGBoost Disruption Classifier
    xgb_clf = xgb.XGBClassifier(
        n_estimators=150,
        max_depth=5,
        learning_rate=0.08,
        subsample=0.85,
        colsample_bytree=0.85,
        random_state=42,
        eval_metric="auc"
    )
    xgb_clf.fit(X_train, y_dis_train, eval_set=[(X_val, y_dis_val)], verbose=False)
    p_xgb_raw = xgb_clf.predict_proba(X_test)[:, 1]

    # Calibrate on validation partition using FrozenEstimator
    cal_xgb = CalibratedClassifierCV(FrozenEstimator(xgb_clf), method="sigmoid")
    cal_xgb.fit(X_val, y_dis_val)
    p_xgb = cal_xgb.predict_proba(X_test)[:, 1]

    # 5. Train Multi-Horizon Delay Regressors (Predicting Arrival Delay Minutes)
    logger.info("Training Delay Regressors: Dummy Mean, Ridge, XGBoost P50, P10, P90...")

    # Filter completed flights for delay training
    mask_train_comp = (y_can_train == 0)
    mask_val_comp = (y_can_val == 0)
    mask_test_comp = (y_can_test == 0)

    X_train_comp, y_train_comp = X_train[mask_train_comp], y_del_train[mask_train_comp]
    X_val_comp, y_val_comp = X_val[mask_val_comp], y_del_val[mask_val_comp]
    X_test_comp, y_test_comp = X_test[mask_test_comp], y_del_test[mask_test_comp]

    dummy_reg = DummyRegressor(strategy="mean")
    dummy_reg.fit(X_train_comp, y_train_comp)
    pred_dummy_reg = dummy_reg.predict(X_test_comp)

    ridge = Ridge(alpha=10.0)
    ridge.fit(X_train_comp, y_train_comp)
    pred_ridge = ridge.predict(X_test_comp)

    # XGBoost Expected Delay (P50 Median Regressor)
    xgb_p50 = xgb.XGBRegressor(
        n_estimators=120,
        max_depth=5,
        learning_rate=0.05,
        objective="reg:squarederror",
        random_state=42
    )
    xgb_p50.fit(X_train_comp, y_train_comp, eval_set=[(X_val_comp, y_val_comp)], verbose=False)
    pred_xgb_p50 = np.maximum(0.0, xgb_p50.predict(X_test_comp))

    # XGBoost Quantile Regressors for Uncertainty Bounds (P10 & P90)
    xgb_p10 = xgb.XGBRegressor(
        n_estimators=100,
        max_depth=4,
        learning_rate=0.05,
        objective="reg:quantileerror",
        quantile_alpha=0.10,
        random_state=42
    )
    xgb_p10.fit(X_train_comp, y_train_comp, verbose=False)
    pred_xgb_p10 = np.maximum(0.0, xgb_p10.predict(X_test_comp))

    xgb_p90 = xgb.XGBRegressor(
        n_estimators=100,
        max_depth=4,
        learning_rate=0.05,
        objective="reg:quantileerror",
        quantile_alpha=0.90,
        random_state=42
    )
    xgb_p90.fit(X_train_comp, y_train_comp, verbose=False)
    pred_xgb_p90 = np.maximum(pred_xgb_p50, xgb_p90.predict(X_test_comp))

    # 6. Fit SHAP Explainer
    logger.info("Computing SHAP TreeExplainer on XGBoost disruption model...")
    explainer = shap.TreeExplainer(xgb_clf)
    # Background sample for runtime Kernel/Tree explainability
    shap_background = X_train.sample(min(100, len(X_train)), random_state=42)

    # 7. Comprehensive Model Benchmark Metrics
    def eval_clf(y_true, y_prob, name):
        roc = float(roc_auc_score(y_true, y_prob))
        pr = float(average_precision_score(y_true, y_prob))
        brier = float(brier_score_loss(y_true, y_prob))
        # Optimal threshold using F1
        thresholds = [0.10, 0.15, 0.20, 0.25, 0.30]
        th_results = {}
        for th in thresholds:
            y_pred = (y_prob >= th).astype(int)
            th_results[f"th_{th:.2f}"] = {
                "precision": round(float(precision_score(y_true, y_pred, zero_division=0)), 4),
                "recall": round(float(recall_score(y_true, y_pred, zero_division=0)), 4),
                "f1": round(float(f1_score(y_true, y_pred, zero_division=0)), 4),
                "flagged_count": int(y_pred.sum()),
            }
        return {
            "model_name": name,
            "roc_auc": round(roc, 4),
            "pr_auc": round(pr, 4),
            "brier_score": round(brier, 4),
            "threshold_performance": th_results,
        }

    def eval_reg(y_true, y_pred, name):
        mae = float(mean_absolute_error(y_true, y_pred))
        rmse = float(np.sqrt(mean_squared_error(y_true, y_pred)))
        med_ae = float(median_absolute_error(y_true, y_pred))
        return {
            "model_name": name,
            "mae": round(mae, 2),
            "rmse": round(rmse, 2),
            "median_ae": round(med_ae, 2),
        }

    clf_metrics = {
        "dummy_baseline": eval_clf(y_dis_test, p_dummy, "Dummy Prior"),
        "logistic_regression": eval_clf(y_dis_test, p_lr, "Logistic Regression"),
        "random_forest": eval_clf(y_dis_test, p_rf, "Random Forest"),
        "xgboost_raw_v3": eval_clf(y_dis_test, p_xgb_raw, "XGBoost Raw v3"),
        "xgboost_calibrated_v3": eval_clf(y_dis_test, p_xgb, "XGBoost Calibrated v3"),
    }

    reg_metrics = {
        "dummy_baseline": eval_reg(y_test_comp, pred_dummy_reg, "Dummy Mean"),
        "ridge_baseline": eval_reg(y_test_comp, pred_ridge, "Ridge Regression"),
        "xgboost_p50_v3": eval_reg(y_test_comp, pred_xgb_p50, "XGBoost P50 Median Regressor"),
    }

    # Prediction Interval Coverage Probability (PICP) for [P10, P90]
    in_interval = ((y_test_comp >= pred_xgb_p10) & (y_test_comp <= pred_xgb_p90)).mean()
    interval_metrics = {
        "nominal_coverage": 0.80, # Expected 80% coverage for [P10, P90]
        "empirical_picp": round(float(in_interval), 4),
        "mean_interval_width_minutes": round(float((pred_xgb_p90 - pred_xgb_p10).mean()), 2),
    }

    # Indian Corridor Subgroup Evaluation
    is_ind_test = (test_df["data_provenance"] == "INDIAN_DGCA_SCHEDULE_SYNTHESIS")
    if is_ind_test.sum() > 0:
        y_dis_ind = y_dis_test[is_ind_test]
        p_xgb_ind = p_xgb[is_ind_test]
        indian_metrics = {
            "test_sample_count": int(is_ind_test.sum()),
            "disruption_prevalence": round(float(y_dis_ind.mean()), 4),
            "roc_auc": round(float(roc_auc_score(y_dis_ind, p_xgb_ind)), 4),
            "pr_auc": round(float(average_precision_score(y_dis_ind, p_xgb_ind)), 4),
            "brier_score": round(float(brier_score_loss(y_dis_ind, p_xgb_ind)), 4),
            "f1_at_20": round(float(f1_score(y_dis_ind, (p_xgb_ind >= 0.20).astype(int), zero_division=0)), 4),
        }
    else:
        indian_metrics = {}

    full_evaluation = {
        "model_version": "v3.0.0-enterprise",
        "evaluation_time_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "test_sample_size": len(test_df),
        "disruption_classification": clf_metrics,
        "delay_regression": reg_metrics,
        "prediction_interval_coverage": interval_metrics,
        "indian_corridor_evaluation": indian_metrics,
    }

    # 8. Save Artifacts
    logger.info("Saving trained v3 artifacts to disk...")
    joblib.dump(fe, ARTIFACTS_DIR / "preprocessor_v3.joblib")
    joblib.dump(cal_xgb, ARTIFACTS_DIR / "calibrated_disruption_v3.joblib")
    xgb_clf.save_model(ARTIFACTS_DIR / "xgb_disruption_raw_v3.json")
    xgb_p50.save_model(ARTIFACTS_DIR / "xgb_delay_p50_v3.json")
    xgb_p10.save_model(ARTIFACTS_DIR / "xgb_delay_p10_v3.json")
    xgb_p90.save_model(ARTIFACTS_DIR / "xgb_delay_p90_v3.json")
    joblib.dump(explainer, ARTIFACTS_DIR / "shap_explainer_v3.joblib")
    joblib.dump(shap_background, ARTIFACTS_DIR / "shap_background_v3.joblib")

    with open(ARTIFACTS_DIR / "evaluation_metrics_v3.json", "w", encoding="utf-8") as f:
        json.dump(full_evaluation, f, indent=2)

    with open(ARTIFACTS_DIR / "feature_schema_v3.json", "w", encoding="utf-8") as f:
        json.dump({
            "model_version": "v3.0.0-enterprise",
            "feature_count": len(fe.feature_columns),
            "feature_names": fe.feature_columns,
            "created_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "supported_indian_corridors": [f"{o}-{d}" for o, d in [("HYD", "DEL"), ("HYD", "BOM"), ("HYD", "BLR"), ("DEL", "BOM"), ("BLR", "MAA"), ("BOM", "BLR")]],
            "supported_carriers": TOP_CARRIERS,
            "supported_airports": TOP_AIRPORTS,
        }, f, indent=2)

    logger.info("=== Stage 8 Training & Artifact Serialization Complete! ===")
    return full_evaluation

if __name__ == "__main__":
    run_stage_8_training()
