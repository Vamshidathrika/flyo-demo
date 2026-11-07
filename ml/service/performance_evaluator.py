import json
import logging
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    confusion_matrix,
    mean_absolute_error,
    mean_squared_error,
    median_absolute_error,
    roc_auc_score,
)

from ml.service.config import ARTIFACTS_DIR

logger = logging.getLogger("PerformanceEvaluator")

DEFAULT_METRICS_PATH = ARTIFACTS_DIR / "evaluation_metrics_v2.json"

def compute_ece(y_true: np.ndarray, y_prob: np.ndarray, n_bins: int = 10) -> float:
    bins = np.linspace(0.0, 1.0, n_bins + 1)
    bin_indices = np.digitize(y_prob, bins) - 1
    ece = 0.0
    n = len(y_true)
    for b in range(n_bins):
        mask = bin_indices == b
        if np.any(mask):
            bin_size = np.sum(mask)
            bin_acc = np.mean(y_true[mask])
            bin_conf = np.mean(y_prob[mask])
            ece += (bin_size / n) * abs(bin_acc - bin_conf)
    return float(ece)

class PerformanceEvaluator:
    """
    Evaluates real-world prediction accuracy against ingested delayed outcomes.
    Separately scores Cancellation, Delay >=120m, and Continuous Arrival Delay.
    """

    def __init__(self, baseline_metrics_path: Optional[Path] = None):
        target_path = baseline_metrics_path or DEFAULT_METRICS_PATH
        if target_path.exists():
            with open(target_path, "r") as f:
                self.baseline_metrics = json.load(f)
        else:
            self.baseline_metrics = {}

    def evaluate_performance(self, matched_records: List[Dict[str, Any]]) -> Dict[str, Any]:
        n = len(matched_records)
        if n == 0:
            return {
                "status": "NO_EVALUATED_OUTCOMES",
                "evaluated_flights_count": 0,
                "message": "No prediction records have been matched with ingested ground-truth flight outcomes yet.",
                "cancellation_metrics": None,
                "delay120_metrics": None,
                "continuous_delay_metrics": None,
                "carrier_performance": {}
            }

        y_c_true = np.array([int(r["actual_cancelled"]) for r in matched_records])
        y_c_prob = np.array([float(r["cancellation_probability"]) for r in matched_records])

        y_d_true = np.array([int(r["actual_delayed_120m"]) for r in matched_records])
        y_d_prob = np.array([float(r["delay_120m_probability"]) for r in matched_records])

        # Filter completed flights for delay regression
        completed_records = [r for r in matched_records if int(r["actual_cancelled"]) == 0 and r.get("expected_delay_minutes") is not None]
        y_r_true = np.array([float(r["actual_arr_delay"]) for r in completed_records]) if completed_records else np.array([])
        y_r_pred = np.array([float(r["expected_delay_minutes"]) for r in completed_records]) if completed_records else np.array([])

        # 1. Target A: Cancellation Classifier Performance
        c_brier = float(brier_score_loss(y_c_true, y_c_prob))
        c_ece = compute_ece(y_c_true, y_c_prob)
        c_roc_auc = float(roc_auc_score(y_c_true, y_c_prob)) if len(np.unique(y_c_true)) > 1 else None
        c_pr_auc = float(average_precision_score(y_c_true, y_c_prob)) if np.any(y_c_true == 1) else None
        c_conf = confusion_matrix(y_c_true, (y_c_prob >= 0.08).astype(int)).tolist()

        base_c_brier = self.baseline_metrics.get("cancellation_evaluation", {}).get("xgboost_calibrated_v2", {}).get("brier_score", 0.02931)
        c_brier_delta = c_brier - base_c_brier

        # 2. Target B: Delay >=120m Classifier Performance
        d_brier = float(brier_score_loss(y_d_true, y_d_prob))
        d_ece = compute_ece(y_d_true, y_d_prob)
        d_roc_auc = float(roc_auc_score(y_d_true, y_d_prob)) if len(np.unique(y_d_true)) > 1 else None
        d_pr_auc = float(average_precision_score(y_d_true, y_d_prob)) if np.any(y_d_true == 1) else None
        d_conf = confusion_matrix(y_d_true, (y_d_prob >= 0.10).astype(int)).tolist()

        base_d_brier = self.baseline_metrics.get("delay120_evaluation", {}).get("xgboost_calibrated_v2", {}).get("brier_score", 0.05075)
        d_brier_delta = d_brier - base_d_brier

        # 3. Target C: Continuous Delay Regressor Performance
        if len(y_r_true) > 0:
            r_mae = float(mean_absolute_error(y_r_true, y_r_pred))
            r_rmse = float(np.sqrt(mean_squared_error(y_r_true, y_r_pred)))
            r_median_ae = float(median_absolute_error(y_r_true, y_r_pred))
        else:
            r_mae, r_rmse, r_median_ae = 0.0, 0.0, 0.0

        base_r_mae = self.baseline_metrics.get("continuous_delay_evaluation", {}).get("xgboost_regressor_v2", {}).get("mae", 34.00)
        r_mae_delta = r_mae - base_r_mae if len(y_r_true) > 0 else 0.0

        # 4. Carrier Subgroup Breakdown
        carriers = sorted(list(set(r["carrier_code"] for r in matched_records)))
        carrier_perf = {}
        for c in carriers:
            c_recs = [r for r in matched_records if r["carrier_code"] == c]
            if c_recs:
                c_yt = np.array([int(r["actual_cancelled"]) for r in c_recs])
                c_yp = np.array([float(r["cancellation_probability"]) for r in c_recs])
                d_yt = np.array([int(r["actual_delayed_120m"]) for r in c_recs])
                d_yp = np.array([float(r["delay_120m_probability"]) for r in c_recs])

                carrier_perf[c] = {
                    "sample_count": len(c_recs),
                    "actual_cancellation_rate": round(float(np.mean(c_yt)), 4),
                    "actual_delay120_rate": round(float(np.mean(d_yt)), 4),
                    "cancellation_brier": round(float(brier_score_loss(c_yt, c_yp)), 5),
                    "delay120_brier": round(float(brier_score_loss(d_yt, d_yp)), 5),
                }

        return {
            "status": "EVALUATED",
            "evaluated_flights_count": n,
            "completed_flights_for_regression": len(y_r_true),
            "cancellation_classifier": {
                "brier_score": round(c_brier, 5),
                "brier_delta_vs_baseline": round(c_brier_delta, 5),
                "roc_auc": round(c_roc_auc, 4) if c_roc_auc is not None else "SINGLE_CLASS_IN_SAMPLE",
                "pr_auc": round(c_pr_auc, 4) if c_pr_auc is not None else "NO_POSITIVES_IN_SAMPLE",
                "expected_calibration_error": round(c_ece, 4),
                "confusion_matrix_at_08": c_conf,
                "baseline_brier": base_c_brier
            },
            "delay120_classifier": {
                "brier_score": round(d_brier, 5),
                "brier_delta_vs_baseline": round(d_brier_delta, 5),
                "roc_auc": round(d_roc_auc, 4) if d_roc_auc is not None else "SINGLE_CLASS_IN_SAMPLE",
                "pr_auc": round(d_pr_auc, 4) if d_pr_auc is not None else "NO_POSITIVES_IN_SAMPLE",
                "expected_calibration_error": round(d_ece, 4),
                "confusion_matrix_at_10": d_conf,
                "baseline_brier": base_d_brier
            },
            "continuous_delay_regressor": {
                "mae_minutes": round(r_mae, 2),
                "mae_delta_vs_baseline": round(r_mae_delta, 2),
                "rmse_minutes": round(r_rmse, 2),
                "median_ae_minutes": round(r_median_ae, 2),
                "baseline_mae": base_r_mae
            },
            "carrier_performance": carrier_perf
        }
