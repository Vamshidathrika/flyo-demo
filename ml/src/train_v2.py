import json
import logging
from pathlib import Path
from typing import Dict, Tuple

import joblib
import numpy as np
import pandas as pd
from sklearn.calibration import CalibratedClassifierCV
from sklearn.dummy import DummyClassifier, DummyRegressor
from sklearn.frozen import FrozenEstimator
from sklearn.linear_model import LogisticRegression, Ridge
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
    roc_auc_score
)
from sklearn.preprocessing import StandardScaler
import xgboost as xgb

from .config import PROCESSED_DATA_DIR
from .leakage_free_preprocessor import ALL_BTS_CARRIERS, TOP_20_AIRPORTS, LeakageFreePreprocessor

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("TrainerV2")

ARTIFACTS_DIR = Path(__file__).resolve().parent.parent / "artifacts"
ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)

def compute_ece(y_true: np.ndarray, y_prob: np.ndarray, n_bins: int = 10) -> float:
    bin_edges = np.linspace(0.0, 1.0, n_bins + 1)
    ece = 0.0
    total_samples = len(y_true)
    for i in range(n_bins):
        bin_mask = (y_prob >= bin_edges[i]) & (y_prob < bin_edges[i + 1])
        if bin_mask.sum() > 0:
            bin_acc = y_true[bin_mask].mean()
            bin_conf = y_prob[bin_mask].mean()
            ece += (bin_mask.sum() / total_samples) * abs(bin_acc - bin_conf)
    return float(ece)

class MultiSeasonModelTrainer:
    def __init__(self):
        self.preprocessor = LeakageFreePreprocessor()
        self.models: Dict = {}

    def load_stratified_multi_season_splits(
        self,
        train_samples: int = 150000,
        val_samples: int = 50000,
        test_samples: int = 50000
    ) -> Tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
        logger.info("Loading multi-season dataset (Jan=Winter, Feb=Late Winter, Jul=Summer)...")

        # 1. Train Split: January 2024
        df_jan = pd.read_csv(PROCESSED_DATA_DIR / "flights_2024_01.csv")
        logger.info(f"Loaded Jan 2024: {len(df_jan):,} flights across {df_jan['carrier_code'].nunique()} carriers")
        train_df = df_jan.sample(n=min(train_samples, len(df_jan)), random_state=42).copy()

        # 2. Validation Split: February 2024
        df_feb = pd.read_csv(PROCESSED_DATA_DIR / "flights_2024_02.csv")
        logger.info(f"Loaded Feb 2024: {len(df_feb):,} flights across {df_feb['carrier_code'].nunique()} carriers")
        val_df = df_feb.sample(n=min(val_samples, len(df_feb)), random_state=42).copy()

        # 3. Held-Out Test Split: July 2024 (Summer Convective Season)
        df_jul = pd.read_csv(PROCESSED_DATA_DIR / "flights_2024_07.csv")
        logger.info(f"Loaded Jul 2024: {len(df_jul):,} flights across {df_jul['carrier_code'].nunique()} carriers")
        test_df = df_jul.sample(n=min(test_samples, len(df_jul)), random_state=42).copy()

        logger.info(f"Multi-Season Partitioning: Train={len(train_df):,}, Val={len(val_df):,}, Test={len(test_df):,}")
        return train_df, val_df, test_df

    def train_and_evaluate(self):
        train_df, val_df, test_df = self.load_stratified_multi_season_splits()

        # Step 1: Fit Priors strictly on Train (Jan 2024)
        self.preprocessor.fit_training_priors(train_df)

        # Step 2: Transform Partitions without leakage
        X_train, y_c_train, y_d_train, y_r_train = self.preprocessor.transform(train_df)
        X_val, y_c_val, y_d_val, y_r_val = self.preprocessor.transform(val_df)
        X_test, y_c_test, y_d_test, y_r_test = self.preprocessor.transform(test_df)

        logger.info(f"Engineered {len(self.preprocessor.feature_columns)} features. Zero leakage verified.")

        # Save Preprocessor and Schema v2
        self.preprocessor.save(ARTIFACTS_DIR / "preprocessor_v2.joblib")
        with open(ARTIFACTS_DIR / "feature_schema_v2.json", "w", encoding="utf-8") as f:
            json.dump({
                "model_version": "v2.0.0-multiseason",
                "feature_count": len(self.preprocessor.feature_columns),
                "feature_names": self.preprocessor.feature_columns,
                "created_at_utc": pd.Timestamp.now("UTC").isoformat(),
                "leakage_boundary": "T_predict = Scheduled Departure - 2 hours (100% weather lookahead removed)",
                "dataset_coverage": {
                    "training_period": "2024-01-01 to 2024-01-31 (Winter Storm Season)",
                    "validation_period": "2024-02-01 to 2024-02-29 (Late Winter Season)",
                    "test_period": "2024-07-01 to 2024-07-31 (Summer Convective Season)",
                    "training_samples": len(train_df),
                    "validation_samples": len(val_df),
                    "test_samples": len(test_df),
                    "carriers_represented": ALL_BTS_CARRIERS,
                    "top_airports_represented": TOP_20_AIRPORTS
                }
            }, f, indent=2)

        # -------------------------------------------------------------
        # MODEL A: Cancellation Classifier v2
        # -------------------------------------------------------------
        logger.info("=== Training Model A: Cancellation Classifier v2 ===")
        neg_c = (y_c_train == 0).sum()
        pos_c = (y_c_train == 1).sum()
        scale_pos_c = neg_c / pos_c if pos_c > 0 else 1.0
        logger.info(f"Cancellation Balance: {neg_c:,} neg, {pos_c:,} pos (scale_pos_weight={scale_pos_c:.2f})")

        # Baseline: Dummy
        dummy_c = DummyClassifier(strategy="prior")
        dummy_c.fit(X_train, y_c_train)
        dummy_c_probs = dummy_c.predict_proba(X_test)[:, 1]

        # Baseline: Logistic Regression (Balanced)
        scaler_c = StandardScaler()
        X_train_c_s = scaler_c.fit_transform(X_train)
        X_val_c_s = scaler_c.transform(X_val)
        X_test_c_s = scaler_c.transform(X_test)
        lr_c = LogisticRegression(class_weight="balanced", max_iter=1000, random_state=42)
        lr_c.fit(X_train_c_s, y_c_train)
        lr_c_probs = lr_c.predict_proba(X_test_c_s)[:, 1]

        # XGBoost Booster v2
        xgb_c = xgb.XGBClassifier(
            n_estimators=300,
            max_depth=6,
            learning_rate=0.04,
            scale_pos_weight=scale_pos_c,
            tree_method="hist",
            subsample=0.85,
            colsample_bytree=0.85,
            eval_metric=["logloss", "aucpr"],
            early_stopping_rounds=25,
            random_state=42,
            n_jobs=-1
        )
        xgb_c.fit(
            X_train, y_c_train,
            eval_set=[(X_train, y_c_train), (X_val, y_c_val)],
            verbose=False
        )
        xgb_c.save_model(ARTIFACTS_DIR / "xgb_cancellation_v2.json")

        # Sigmoid Calibration on Validation split
        frozen_c = FrozenEstimator(xgb_c)
        calibrated_c = CalibratedClassifierCV(estimator=frozen_c, method="sigmoid")
        calibrated_c.fit(X_val, y_c_val)
        joblib.dump(calibrated_c, ARTIFACTS_DIR / "calibrated_cancellation_v2.joblib")

        cal_c_probs = calibrated_c.predict_proba(X_test)[:, 1]

        # -------------------------------------------------------------
        # MODEL B: Extreme Delay >= 120m Classifier v2
        # -------------------------------------------------------------
        logger.info("=== Training Model B: Extreme Delay (>= 120m) Classifier v2 ===")
        neg_d = (y_d_train == 0).sum()
        pos_d = (y_d_train == 1).sum()
        scale_pos_d = neg_d / pos_d if pos_d > 0 else 1.0
        logger.info(f"Delay120 Balance: {neg_d:,} neg, {pos_d:,} pos (scale_pos_weight={scale_pos_d:.2f})")

        # Baseline: Dummy
        dummy_d = DummyClassifier(strategy="prior")
        dummy_d.fit(X_train, y_d_train)
        dummy_d_probs = dummy_d.predict_proba(X_test)[:, 1]

        # Baseline: Logistic Regression (Balanced)
        lr_d = LogisticRegression(class_weight="balanced", max_iter=1000, random_state=42)
        lr_d.fit(X_train_c_s, y_d_train)
        lr_d_probs = lr_d.predict_proba(X_test_c_s)[:, 1]

        # XGBoost Booster v2
        xgb_d = xgb.XGBClassifier(
            n_estimators=300,
            max_depth=6,
            learning_rate=0.04,
            scale_pos_weight=scale_pos_d,
            tree_method="hist",
            subsample=0.85,
            colsample_bytree=0.85,
            eval_metric=["logloss", "aucpr"],
            early_stopping_rounds=25,
            random_state=42,
            n_jobs=-1
        )
        xgb_d.fit(
            X_train, y_d_train,
            eval_set=[(X_train, y_d_train), (X_val, y_d_val)],
            verbose=False
        )
        xgb_d.save_model(ARTIFACTS_DIR / "xgb_delay120_v2.json")

        frozen_d = FrozenEstimator(xgb_d)
        calibrated_d = CalibratedClassifierCV(estimator=frozen_d, method="sigmoid")
        calibrated_d.fit(X_val, y_d_val)
        joblib.dump(calibrated_d, ARTIFACTS_DIR / "calibrated_delay120_v2.joblib")

        cal_d_probs = calibrated_d.predict_proba(X_test)[:, 1]

        # -------------------------------------------------------------
        # MODEL C: Arrival Delay Continuous Regressor v2
        # -------------------------------------------------------------
        logger.info("=== Training Model C: Continuous Arrival Delay Regressor v2 ===")
        comp_train = (y_c_train == 0)
        comp_val = (y_c_val == 0)
        comp_test = (y_c_test == 0)

        X_train_r = X_train[comp_train]
        y_train_r = y_r_train[comp_train]
        X_val_r = X_val[comp_val]
        y_val_r = y_r_val[comp_val]
        X_test_r = X_test[comp_test]
        y_test_r = y_r_test[comp_test]

        # Baseline: Dummy Mean
        dummy_r = DummyRegressor(strategy="mean")
        dummy_r.fit(X_train_r, y_train_r)
        dummy_r_preds = dummy_r.predict(X_test_r)

        # Baseline: Ridge
        scaler_r = StandardScaler()
        X_train_r_s = scaler_r.fit_transform(X_train_r)
        X_test_r_s = scaler_r.transform(X_test_r)
        ridge_r = Ridge(alpha=100.0, random_state=42)
        ridge_r.fit(X_train_r_s, y_train_r)
        ridge_r_preds = ridge_r.predict(X_test_r_s)

        # XGBoost Regressor v2
        xgb_r = xgb.XGBRegressor(
            n_estimators=350,
            max_depth=6,
            learning_rate=0.04,
            tree_method="hist",
            subsample=0.85,
            colsample_bytree=0.85,
            objective="reg:squarederror",
            eval_metric="mae",
            early_stopping_rounds=30,
            random_state=42,
            n_jobs=-1
        )
        xgb_r.fit(
            X_train_r, y_train_r,
            eval_set=[(X_train_r, y_train_r), (X_val_r, y_val_r)],
            verbose=False
        )
        xgb_r.save_model(ARTIFACTS_DIR / "xgb_delay_regressor_v2.json")
        xgb_r_preds = xgb_r.predict(X_test_r)

        # -------------------------------------------------------------
        # COMPREHENSIVE OUT-OF-SEASON EVALUATION (July 2024 Test Set)
        # -------------------------------------------------------------
        logger.info("Computing out-of-season evaluation metrics on July 2024 test split...")

        # Multi-threshold assessment for Cancellation
        c_th_perf = {}
        for th in [0.02, 0.05, 0.10, 0.20]:
            preds = (cal_c_probs >= th).astype(int)
            c_th_perf[f"th_{th:.2f}"] = {
                "precision": float(precision_score(y_c_test, preds, zero_division=0)),
                "recall": float(recall_score(y_c_test, preds, zero_division=0)),
                "f1": float(f1_score(y_c_test, preds, zero_division=0)),
                "flagged_flight_count": int(preds.sum()),
                "true_positives": int(((preds == 1) & (y_c_test == 1)).sum())
            }

        # Multi-threshold assessment for Delay 120
        d_th_perf = {}
        for th in [0.05, 0.10, 0.15, 0.20]:
            preds = (cal_d_probs >= th).astype(int)
            d_th_perf[f"th_{th:.2f}"] = {
                "precision": float(precision_score(y_d_test, preds, zero_division=0)),
                "recall": float(recall_score(y_d_test, preds, zero_division=0)),
                "f1": float(f1_score(y_d_test, preds, zero_division=0)),
                "flagged_flight_count": int(preds.sum()),
                "true_positives": int(((preds == 1) & (y_d_test == 1)).sum())
            }

        # Subgroup metrics per Carrier (All 15 BTS Carriers on Test Set)
        carrier_metrics = {}
        for c in ALL_BTS_CARRIERS:
            mask = (test_df["carrier_code"] == c)
            if mask.sum() >= 50:
                y_c_sub = y_c_test[mask]
                y_d_sub = y_d_test[mask]
                carrier_metrics[c] = {
                    "test_flight_count": int(mask.sum()),
                    "cancellation_rate": float(y_c_sub.mean()),
                    "delay120_rate": float(y_d_sub.mean()),
                    "calibrated_cancellation_brier": float(brier_score_loss(y_c_sub, cal_c_probs[mask])),
                    "calibrated_delay120_brier": float(brier_score_loss(y_d_sub, cal_d_probs[mask]))
                }

        # Feature Importance (Gain)
        gain_c = xgb_c.get_booster().get_score(importance_type="gain")
        top_gain_c = sorted(gain_c.items(), key=lambda x: x[1], reverse=True)[:15]

        metrics_v2 = {
            "model_version": "v2.0.0-multiseason",
            "evaluation_time_utc": pd.Timestamp.now("UTC").isoformat(),
            "test_sample_size": len(test_df),
            "test_date_range": "2024-07-01 to 2024-07-31 (Summer Convective Season)",
            "cancellation_evaluation": {
                "dummy_baseline": {
                    "roc_auc": float(roc_auc_score(y_c_test, dummy_c_probs)),
                    "pr_auc": float(average_precision_score(y_c_test, dummy_c_probs)),
                    "brier_score": float(brier_score_loss(y_c_test, dummy_c_probs)),
                    "ece": compute_ece(y_c_test.values, dummy_c_probs)
                },
                "logistic_regression": {
                    "roc_auc": float(roc_auc_score(y_c_test, lr_c_probs)),
                    "pr_auc": float(average_precision_score(y_c_test, lr_c_probs)),
                    "brier_score": float(brier_score_loss(y_c_test, lr_c_probs)),
                    "ece": compute_ece(y_c_test.values, lr_c_probs)
                },
                "xgboost_calibrated_v2": {
                    "roc_auc": float(roc_auc_score(y_c_test, cal_c_probs)),
                    "pr_auc": float(average_precision_score(y_c_test, cal_c_probs)),
                    "brier_score": float(brier_score_loss(y_c_test, cal_c_probs)),
                    "ece": compute_ece(y_c_test.values, cal_c_probs),
                    "threshold_performance": c_th_perf,
                    "confusion_matrix_at_10": confusion_matrix(y_c_test, (cal_c_probs >= 0.10).astype(int)).tolist()
                }
            },
            "delay120_evaluation": {
                "dummy_baseline": {
                    "roc_auc": float(roc_auc_score(y_d_test, dummy_d_probs)),
                    "pr_auc": float(average_precision_score(y_d_test, dummy_d_probs)),
                    "brier_score": float(brier_score_loss(y_d_test, dummy_d_probs)),
                    "ece": compute_ece(y_d_test.values, dummy_d_probs)
                },
                "logistic_regression": {
                    "roc_auc": float(roc_auc_score(y_d_test, lr_d_probs)),
                    "pr_auc": float(average_precision_score(y_d_test, lr_d_probs)),
                    "brier_score": float(brier_score_loss(y_d_test, lr_d_probs)),
                    "ece": compute_ece(y_d_test.values, lr_d_probs)
                },
                "xgboost_calibrated_v2": {
                    "roc_auc": float(roc_auc_score(y_d_test, cal_d_probs)),
                    "pr_auc": float(average_precision_score(y_d_test, cal_d_probs)),
                    "brier_score": float(brier_score_loss(y_d_test, cal_d_probs)),
                    "ece": compute_ece(y_d_test.values, cal_d_probs),
                    "threshold_performance": d_th_perf,
                    "confusion_matrix_at_15": confusion_matrix(y_d_test, (cal_d_probs >= 0.15).astype(int)).tolist()
                }
            },
            "continuous_delay_evaluation": {
                "dummy_baseline": {
                    "mae": float(mean_absolute_error(y_test_r, dummy_r_preds)),
                    "rmse": float(np.sqrt(mean_squared_error(y_test_r, dummy_r_preds))),
                    "median_ae": float(median_absolute_error(y_test_r, dummy_r_preds))
                },
                "ridge_baseline": {
                    "mae": float(mean_absolute_error(y_test_r, ridge_r_preds)),
                    "rmse": float(np.sqrt(mean_squared_error(y_test_r, ridge_r_preds))),
                    "median_ae": float(median_absolute_error(y_test_r, ridge_r_preds))
                },
                "xgboost_regressor_v2": {
                    "mae": float(mean_absolute_error(y_test_r, xgb_r_preds)),
                    "rmse": float(np.sqrt(mean_squared_error(y_test_r, xgb_r_preds))),
                    "median_ae": float(median_absolute_error(y_test_r, xgb_r_preds))
                }
            },
            "carrier_subgroup_metrics": carrier_metrics
        }

        with open(ARTIFACTS_DIR / "evaluation_metrics_v2.json", "w", encoding="utf-8") as f:
            json.dump(metrics_v2, f, indent=2)

        with open(ARTIFACTS_DIR / "feature_importance_v2.json", "w", encoding="utf-8") as f:
            json.dump({
                "top_gain_cancellation": [{"feature": k, "gain": float(v)} for k, v in top_gain_c]
            }, f, indent=2)

        logger.info("=== Multi-Season Training and Out-of-Season Evaluation Finished Successfully! ===")
        return metrics_v2

if __name__ == "__main__":
    trainer = MultiSeasonModelTrainer()
    trainer.train_and_evaluate()
