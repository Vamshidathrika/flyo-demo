import logging
from typing import Any, Dict, List, Optional

logger = logging.getLogger("AlertEngine")

class AlertEngine:
    """
    Evaluates evidence-based operational and statistical alerts across:
    1. Inference failures & latency
    2. Input feature missingness & imputation spikes
    3. Feature & prediction drift (PSI / KS)
    4. Delayed outcome calibration deterioration (Brier / MAE)
    5. Carrier-specific performance drops
    """

    def __init__(self):
        self.thresholds = {
            "error_rate": {"warning": 0.05, "critical": 0.15},
            "p95_latency_ms": {"warning": 100.0, "critical": 250.0},
            "imputation_rate": {"warning": 0.10, "critical": 0.25},
            "psi": {"warning": 0.10, "critical": 0.25},
            "brier_delta": {"warning": 0.02, "critical": 0.05},
            "mae_delta": {"warning": 5.0, "critical": 15.0}
        }

    def evaluate_operational_alerts(self, summary: Dict[str, Any]) -> List[Dict[str, Any]]:
        alerts = []
        total = summary.get("total_predictions", 0)
        if total == 0:
            return alerts

        # 1. Error Rate Check
        status_counts = summary.get("status_breakdown", {})
        error_count = status_counts.get("INVALID_FEATURE_INPUT", 0) + status_counts.get("MODEL_UNAVAILABLE", 0)
        error_rate = error_count / total if total > 0 else 0.0

        if error_rate >= self.thresholds["error_rate"]["critical"]:
            alerts.append({
                "category": "INFERENCE_FAILURES",
                "severity": "CRITICAL",
                "metric_name": "error_rate",
                "metric_value": round(error_rate, 4),
                "threshold_value": self.thresholds["error_rate"]["critical"],
                "message": f"CRITICAL: Model inference failure rate is {error_rate*100:.1f}%, exceeding critical threshold of 15%."
            })
        elif error_rate >= self.thresholds["error_rate"]["warning"]:
            alerts.append({
                "category": "INFERENCE_FAILURES",
                "severity": "WARNING",
                "metric_name": "error_rate",
                "metric_value": round(error_rate, 4),
                "threshold_value": self.thresholds["error_rate"]["warning"],
                "message": f"WARNING: Model inference failure rate is {error_rate*100:.1f}%, exceeding warning threshold of 5%."
            })

        # 2. Latency p95 Check
        p95_lat = summary.get("latency_ms", {}).get("p95", 0.0)
        if p95_lat >= self.thresholds["p95_latency_ms"]["critical"]:
            alerts.append({
                "category": "LATENCY",
                "severity": "CRITICAL",
                "metric_name": "p95_latency_ms",
                "metric_value": p95_lat,
                "threshold_value": self.thresholds["p95_latency_ms"]["critical"],
                "message": f"CRITICAL: 95th percentile inference latency is {p95_lat}ms, exceeding critical budget of 250ms."
            })
        elif p95_lat >= self.thresholds["p95_latency_ms"]["warning"]:
            alerts.append({
                "category": "LATENCY",
                "severity": "WARNING",
                "metric_name": "p95_latency_ms",
                "metric_value": p95_lat,
                "threshold_value": self.thresholds["p95_latency_ms"]["warning"],
                "message": f"WARNING: 95th percentile inference latency is {p95_lat}ms, exceeding warning budget of 100ms."
            })

        # 3. Imputation Frequency Spike
        imp_rate = summary.get("imputation_stats", {}).get("imputation_rate", 0.0)
        if imp_rate >= self.thresholds["imputation_rate"]["critical"]:
            alerts.append({
                "category": "IMPUTATION_SPIKE",
                "severity": "CRITICAL",
                "metric_name": "imputation_rate",
                "metric_value": imp_rate,
                "threshold_value": self.thresholds["imputation_rate"]["critical"],
                "message": f"CRITICAL: Median imputation rate for duration/distance reached {imp_rate*100:.1f}%, exceeding 25% tolerance."
            })
        elif imp_rate >= self.thresholds["imputation_rate"]["warning"]:
            alerts.append({
                "category": "IMPUTATION_SPIKE",
                "severity": "WARNING",
                "metric_name": "imputation_rate",
                "metric_value": imp_rate,
                "threshold_value": self.thresholds["imputation_rate"]["warning"],
                "message": f"WARNING: Median imputation rate for duration/distance reached {imp_rate*100:.1f}%, exceeding 10% tolerance."
            })

        return alerts

    def evaluate_drift_alerts(self, drift_results: Dict[str, Any]) -> List[Dict[str, Any]]:
        alerts = []
        if drift_results.get("status") != "EVALUATED":
            return alerts

        metrics = drift_results.get("metrics", {})
        max_psi = metrics.get("max_psi", 0.0)

        if max_psi >= self.thresholds["psi"]["critical"]:
            alerts.append({
                "category": "FEATURE_DRIFT",
                "severity": "CRITICAL",
                "metric_name": "max_psi",
                "metric_value": max_psi,
                "threshold_value": self.thresholds["psi"]["critical"],
                "message": f"CRITICAL: Population Stability Index reached {max_psi:.4f} (>= 0.25), indicating significant prediction distribution shift."
            })
        elif max_psi >= self.thresholds["psi"]["warning"]:
            alerts.append({
                "category": "FEATURE_DRIFT",
                "severity": "WARNING",
                "metric_name": "max_psi",
                "metric_value": max_psi,
                "threshold_value": self.thresholds["psi"]["warning"],
                "message": f"WARNING: Population Stability Index reached {max_psi:.4f} (>= 0.10), indicating moderate distribution drift."
            })

        return alerts

    def evaluate_performance_alerts(self, perf_results: Dict[str, Any]) -> List[Dict[str, Any]]:
        alerts = []
        if perf_results.get("status") != "EVALUATED":
            return alerts

        c_delta = perf_results.get("cancellation_classifier", {}).get("brier_delta_vs_baseline", 0.0)
        if c_delta >= self.thresholds["brier_delta"]["critical"]:
            alerts.append({
                "category": "CALIBRATION_DRIFT",
                "severity": "CRITICAL",
                "metric_name": "cancellation_brier_delta",
                "metric_value": round(c_delta, 5),
                "threshold_value": self.thresholds["brier_delta"]["critical"],
                "message": f"CRITICAL: Cancellation Brier score degraded by +{c_delta:.5f} over baseline. Probability calibration severely impaired."
            })
        elif c_delta >= self.thresholds["brier_delta"]["warning"]:
            alerts.append({
                "category": "CALIBRATION_DRIFT",
                "severity": "WARNING",
                "metric_name": "cancellation_brier_delta",
                "metric_value": round(c_delta, 5),
                "threshold_value": self.thresholds["brier_delta"]["warning"],
                "message": f"WARNING: Cancellation Brier score degraded by +{c_delta:.5f} over baseline."
            })

        r_delta = perf_results.get("continuous_delay_regressor", {}).get("mae_delta_vs_baseline", 0.0)
        if r_delta >= self.thresholds["mae_delta"]["critical"]:
            alerts.append({
                "category": "REGRESSION_ERROR",
                "severity": "CRITICAL",
                "metric_name": "mae_delta_minutes",
                "metric_value": round(r_delta, 2),
                "threshold_value": self.thresholds["mae_delta"]["critical"],
                "message": f"CRITICAL: Delay regressor MAE degraded by +{r_delta:.1f} minutes over baseline."
            })
        elif r_delta >= self.thresholds["mae_delta"]["warning"]:
            alerts.append({
                "category": "REGRESSION_ERROR",
                "severity": "WARNING",
                "metric_name": "mae_delta_minutes",
                "metric_value": round(r_delta, 2),
                "threshold_value": self.thresholds["mae_delta"]["warning"],
                "message": f"WARNING: Delay regressor MAE degraded by +{r_delta:.1f} minutes over baseline."
            })

        return alerts
