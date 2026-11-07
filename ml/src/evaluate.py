import json
import logging
from pathlib import Path
from typing import Dict, List, Tuple

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    confusion_matrix,
    f1_score,
    mean_absolute_error,
    mean_squared_error,
    median_absolute_error,
    precision_score,
    r2_score,
    recall_score,
    roc_auc_score
)
import shap
import xgboost as xgb

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("Evaluator")

ARTIFACTS_DIR = Path(__file__).resolve().parent.parent / "artifacts"

def compute_ece(y_true: np.ndarray, y_prob: np.ndarray, n_bins: int = 10) -> float:
    """Computes Expected Calibration Error (ECE) across uniform probability bins."""
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

class ModelEvaluator:
    def __init__(self, artifacts_dir: Path = ARTIFACTS_DIR):
        self.artifacts_dir = artifacts_dir
        self.test_bundle = joblib.load(artifacts_dir / "test_split_bundle.joblib")
        self.models_bundle = joblib.load(artifacts_dir / "all_models_bundle.joblib")
        with open(artifacts_dir / "feature_schema.json", "r") as f:
            self.schema = json.load(f)

    def evaluate_cancellation_models(self) -> Dict:
        logger.info("Evaluating Cancellation models on held-out test split (Days 26-31)...")
        X_test, y_test, _, _, test_df = self.test_bundle
        results = {}

        # 1. Dummy Baseline
        dummy = self.models_bundle["dummy_cancellation"]
        dummy_probs = dummy.predict_proba(X_test)[:, 1]
        results["dummy_baseline"] = {
            "roc_auc": float(roc_auc_score(y_test, dummy_probs)),
            "pr_auc": float(average_precision_score(y_test, dummy_probs)),
            "brier_score": float(brier_score_loss(y_test, dummy_probs)),
            "ece": compute_ece(y_test.values, dummy_probs)
        }

        # 2. Logistic Regression Baseline
        lr = self.models_bundle["lr_cancellation"]
        scaler = self.models_bundle["cancellation_scaler"]
        X_test_scaled = scaler.transform(X_test)
        lr_probs = lr.predict_proba(X_test_scaled)[:, 1]
        results["logistic_regression"] = {
            "roc_auc": float(roc_auc_score(y_test, lr_probs)),
            "pr_auc": float(average_precision_score(y_test, lr_probs)),
            "brier_score": float(brier_score_loss(y_test, lr_probs)),
            "f1_at_50": float(f1_score(y_test, (lr_probs >= 0.50).astype(int), zero_division=0)),
            "ece": compute_ece(y_test.values, lr_probs)
        }

        # 3. XGBoost Raw
        xgb_c = self.models_bundle["xgb_cancellation"]
        xgb_raw_probs = xgb_c.predict_proba(X_test)[:, 1]
        results["xgboost_raw"] = {
            "roc_auc": float(roc_auc_score(y_test, xgb_raw_probs)),
            "pr_auc": float(average_precision_score(y_test, xgb_raw_probs)),
            "brier_score": float(brier_score_loss(y_test, xgb_raw_probs)),
            "f1_at_50": float(f1_score(y_test, (xgb_raw_probs >= 0.50).astype(int), zero_division=0)),
            "ece": compute_ece(y_test.values, xgb_raw_probs)
        }

        # 4. Calibrated XGBoost
        cal_c = self.models_bundle["calibrated_cancellation"]
        cal_probs = cal_c.predict_proba(X_test)[:, 1]

        # Multi-threshold assessment (Operational alert thresholds)
        thresholds = [0.05, 0.10, 0.20, 0.30, 0.50]
        threshold_perf = {}
        for th in thresholds:
            preds = (cal_probs >= th).astype(int)
            threshold_perf[f"th_{th:.2f}"] = {
                "precision": float(precision_score(y_test, preds, zero_division=0)),
                "recall": float(recall_score(y_test, preds, zero_division=0)),
                "f1": float(f1_score(y_test, preds, zero_division=0)),
                "flagged_flight_count": int(preds.sum()),
                "true_positives": int(((preds == 1) & (y_test == 1)).sum())
            }

        cm = confusion_matrix(y_test, (cal_probs >= 0.20).astype(int)).tolist()

        results["xgboost_calibrated"] = {
            "roc_auc": float(roc_auc_score(y_test, cal_probs)),
            "pr_auc": float(average_precision_score(y_test, cal_probs)),
            "brier_score": float(brier_score_loss(y_test, cal_probs)),
            "ece": compute_ece(y_test.values, cal_probs),
            "threshold_performance": threshold_perf,
            "confusion_matrix_at_20": cm
        }
        return results

    def evaluate_delay120_models(self) -> Dict:
        logger.info("Evaluating Extreme Delay (>= 120m) models on held-out test split...")
        X_test, _, y_test, _, test_df = self.test_bundle
        results = {}

        # 1. Dummy
        dummy = self.models_bundle["dummy_delay120"]
        dummy_probs = dummy.predict_proba(X_test)[:, 1]
        results["dummy_baseline"] = {
            "roc_auc": float(roc_auc_score(y_test, dummy_probs)),
            "pr_auc": float(average_precision_score(y_test, dummy_probs)),
            "brier_score": float(brier_score_loss(y_test, dummy_probs)),
            "ece": compute_ece(y_test.values, dummy_probs)
        }

        # 2. Logistic Regression
        lr = self.models_bundle["lr_delay120"]
        scaler = self.models_bundle["delay120_scaler"]
        X_test_scaled = scaler.transform(X_test)
        lr_probs = lr.predict_proba(X_test_scaled)[:, 1]
        results["logistic_regression"] = {
            "roc_auc": float(roc_auc_score(y_test, lr_probs)),
            "pr_auc": float(average_precision_score(y_test, lr_probs)),
            "brier_score": float(brier_score_loss(y_test, lr_probs)),
            "ece": compute_ece(y_test.values, lr_probs)
        }

        # 3. Calibrated XGBoost
        cal_d = self.models_bundle["calibrated_delay120"]
        cal_probs = cal_d.predict_proba(X_test)[:, 1]

        thresholds = [0.05, 0.10, 0.15, 0.20, 0.30]
        threshold_perf = {}
        for th in thresholds:
            preds = (cal_probs >= th).astype(int)
            threshold_perf[f"th_{th:.2f}"] = {
                "precision": float(precision_score(y_test, preds, zero_division=0)),
                "recall": float(recall_score(y_test, preds, zero_division=0)),
                "f1": float(f1_score(y_test, preds, zero_division=0)),
                "flagged_flight_count": int(preds.sum()),
                "true_positives": int(((preds == 1) & (y_test == 1)).sum())
            }

        cm = confusion_matrix(y_test, (cal_probs >= 0.15).astype(int)).tolist()

        results["xgboost_calibrated"] = {
            "roc_auc": float(roc_auc_score(y_test, cal_probs)),
            "pr_auc": float(average_precision_score(y_test, cal_probs)),
            "brier_score": float(brier_score_loss(y_test, cal_probs)),
            "ece": compute_ece(y_test.values, cal_probs),
            "threshold_performance": threshold_perf,
            "confusion_matrix_at_15": cm
        }
        return results

    def evaluate_delay_regressors(self) -> Dict:
        logger.info("Evaluating Continuous Arrival Delay Regressors on completed flights...")
        X_test, y_c_test, _, y_r_test, test_df = self.test_bundle
        completed_mask = (y_c_test == 0)
        X_test_comp = X_test[completed_mask]
        y_test_comp = y_r_test[completed_mask]
        results = {}

        # 1. Dummy Regressor (Mean)
        dummy_reg = self.models_bundle["dummy_delay_reg"]
        dummy_preds = dummy_reg.predict(X_test_comp)
        results["dummy_baseline"] = {
            "mae": float(mean_absolute_error(y_test_comp, dummy_preds)),
            "rmse": float(np.sqrt(mean_squared_error(y_test_comp, dummy_preds))),
            "median_ae": float(median_absolute_error(y_test_comp, dummy_preds)),
            "r2": float(r2_score(y_test_comp, dummy_preds))
        }

        # 2. Ridge Regressor
        ridge = self.models_bundle["ridge_delay_reg"]
        scaler = self.models_bundle["delay_reg_scaler"]
        X_test_comp_scaled = scaler.transform(X_test_comp)
        ridge_preds = ridge.predict(X_test_comp_scaled)
        results["ridge_baseline"] = {
            "mae": float(mean_absolute_error(y_test_comp, ridge_preds)),
            "rmse": float(np.sqrt(mean_squared_error(y_test_comp, ridge_preds))),
            "median_ae": float(median_absolute_error(y_test_comp, ridge_preds)),
            "r2": float(r2_score(y_test_comp, ridge_preds))
        }

        # 3. XGBoost Regressor
        xgb_reg = self.models_bundle["xgb_delay_reg"]
        xgb_preds = xgb_reg.predict(X_test_comp)
        results["xgboost_regressor"] = {
            "mae": float(mean_absolute_error(y_test_comp, xgb_preds)),
            "rmse": float(np.sqrt(mean_squared_error(y_test_comp, xgb_preds))),
            "median_ae": float(median_absolute_error(y_test_comp, xgb_preds)),
            "r2": float(r2_score(y_test_comp, xgb_preds))
        }
        return results

    def evaluate_subgroups(self) -> Dict:
        logger.info("Computing segmented metrics across Carriers and Hubs...")
        X_test, y_c_test, y_d_test, _, test_df = self.test_bundle
        cal_c = self.models_bundle["calibrated_cancellation"]
        cal_d = self.models_bundle["calibrated_delay120"]

        c_probs = cal_c.predict_proba(X_test)[:, 1]
        d_probs = cal_d.predict_proba(X_test)[:, 1]

        carrier_segments = {}
        for carrier in test_df["carrier_code"].value_counts().head(8).index:
            mask = (test_df["carrier_code"] == carrier)
            if mask.sum() >= 50:
                y_c_sub = y_c_test[mask]
                y_d_sub = y_d_test[mask]
                carrier_segments[carrier] = {
                    "flight_count": int(mask.sum()),
                    "cancellation_rate": float(y_c_sub.mean()),
                    "delay120_rate": float(y_d_sub.mean()),
                    "cancellation_brier": float(brier_score_loss(y_c_sub, c_probs[mask])),
                    "delay120_brier": float(brier_score_loss(y_d_sub, d_probs[mask])),
                    "cancellation_pr_auc": float(average_precision_score(y_c_sub, c_probs[mask])) if y_c_sub.sum() > 0 else 0.0,
                    "delay120_pr_auc": float(average_precision_score(y_d_sub, d_probs[mask])) if y_d_sub.sum() > 0 else 0.0
                }

        airport_segments = {}
        for apt in test_df["origin_airport"].value_counts().head(10).index:
            mask = (test_df["origin_airport"] == apt)
            if mask.sum() >= 50:
                y_c_sub = y_c_test[mask]
                y_d_sub = y_d_test[mask]
                airport_segments[apt] = {
                    "flight_count": int(mask.sum()),
                    "cancellation_rate": float(y_c_sub.mean()),
                    "delay120_rate": float(y_d_sub.mean()),
                    "cancellation_brier": float(brier_score_loss(y_c_sub, c_probs[mask])),
                    "delay120_brier": float(brier_score_loss(y_d_sub, d_probs[mask]))
                }

        return {
            "by_carrier": carrier_segments,
            "by_origin_airport": airport_segments
        }

    def compute_feature_importance_and_shap(self) -> Dict:
        logger.info("Computing XGBoost Gain importance and SHAP analysis...")
        xgb_c = self.models_bundle["xgb_cancellation"]
        xgb_d = self.models_bundle["xgb_delay120"]
        X_test, _, _, _, _ = self.test_bundle

        feature_names = self.schema["feature_names"]

        # XGBoost built-in importance (Gain)
        gain_c = xgb_c.get_booster().get_score(importance_type="gain")
        gain_d = xgb_d.get_booster().get_score(importance_type="gain")

        top_gain_c = sorted(gain_c.items(), key=lambda x: x[1], reverse=True)[:15]
        top_gain_d = sorted(gain_d.items(), key=lambda x: x[1], reverse=True)[:15]

        # SHAP TreeExplainer on a representative sample of test flights
        sample_size = min(300, len(X_test))
        X_sample = X_test.iloc[:sample_size]
        explainer_c = shap.TreeExplainer(xgb_c)
        shap_values_c = explainer_c.shap_values(X_sample)

        # Mean absolute SHAP value per feature
        mean_abs_shap = np.abs(shap_values_c).mean(axis=0)
        shap_ranking = sorted(zip(feature_names, mean_abs_shap), key=lambda x: x[1], reverse=True)[:15]

        return {
            "top_gain_cancellation": [{"feature": k, "gain": float(v)} for k, v in top_gain_c],
            "top_gain_delay120": [{"feature": k, "gain": float(v)} for k, v in top_gain_d],
            "top_shap_cancellation": [{"feature": k, "mean_abs_shap": float(v)} for k, v in shap_ranking]
        }

    def run_full_evaluation(self):
        c_eval = self.evaluate_cancellation_models()
        d_eval = self.evaluate_delay120_models()
        r_eval = self.evaluate_delay_regressors()
        segments = self.evaluate_subgroups()
        importance = self.compute_feature_importance_and_shap()

        final_metrics = {
            "evaluation_time_utc": pd.Timestamp.now("UTC").isoformat(),
            "test_sample_size": len(self.test_bundle[0]),
            "test_date_range": "2024-01-26 to 2024-01-31",
            "cancellation_evaluation": c_eval,
            "delay120_evaluation": d_eval,
            "continuous_delay_evaluation": r_eval,
            "subgroup_performance": segments
        }

        with open(self.artifacts_dir / "evaluation_metrics.json", "w", encoding="utf-8") as f:
            json.dump(final_metrics, f, indent=2)

        with open(self.artifacts_dir / "feature_importance.json", "w", encoding="utf-8") as f:
            json.dump(importance, f, indent=2)

        logger.info(f"Evaluation complete. Saved to {self.artifacts_dir / 'evaluation_metrics.json'}")
        return final_metrics, importance

if __name__ == "__main__":
    evaluator = ModelEvaluator()
    evaluator.run_full_evaluation()
