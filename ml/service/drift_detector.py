import json
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

import numpy as np
from scipy import stats

from ml.service.config import ARTIFACTS_DIR

logger = logging.getLogger("DriftDetector")

DEFAULT_BASELINE_PATH = ARTIFACTS_DIR / "baseline_reference_distributions.json"

class DriftDetector:
    """
    Evaluates Population Stability Index (PSI), Kolmogorov-Smirnov (KS) tests,
    and categorical distribution shifts against the July 2024 held-out baseline.
    Enforces a strict minimum sample size threshold (N >= 30).
    """

    def __init__(self, baseline_path: Optional[Path] = None):
        target_path = baseline_path or DEFAULT_BASELINE_PATH
        if target_path.exists():
            with open(target_path, "r") as f:
                self.baseline = json.load(f)
        else:
            self.baseline = {}
        self.min_sample_size = self.baseline.get("drift_thresholds", {}).get("min_sample_size", 30)

    @staticmethod
    def calculate_psi(expected_frequencies: List[float], actual_values: List[float], bins: List[float]) -> float:
        if not actual_values or not expected_frequencies:
            return 0.0

        counts, _ = np.histogram(actual_values, bins=bins)
        actual_frequencies = counts / len(actual_values)
        eps = 1e-4

        psi = 0.0
        for actual, expected in zip(actual_frequencies, expected_frequencies):
            a_s = max(actual, eps)
            e_s = max(expected, eps)
            psi += (a_s - e_s) * np.log(a_s / e_s)
        return round(float(psi), 4)

    def evaluate_drift(self, prediction_records: List[Dict[str, Any]]) -> Dict[str, Any]:
        valid_predictions = [
            r for r in prediction_records
            if r.get("status") == "PREDICTION_AVAILABLE" and r.get("cancellation_probability") is not None
        ]
        sample_size = len(valid_predictions)

        # 1. Minimum Sample Size Check
        if sample_size < self.min_sample_size:
            return {
                "status": "INSUFFICIENT_DATA",
                "sample_size": sample_size,
                "min_sample_required": self.min_sample_size,
                "message": f"Sample size (N={sample_size}) is below minimum threshold (N={self.min_sample_size}) for statistically reliable drift inference.",
                "metrics": {},
                "drift_level": "INSUFFICIENT_DATA"
            }

        cancel_probs = [float(r["cancellation_probability"]) for r in valid_predictions]
        delay_probs = [float(r["delay_120m_probability"]) for r in valid_predictions]
        exp_delays = [float(r["expected_delay_minutes"]) for r in valid_predictions if r.get("expected_delay_minutes") is not None]
        distances = [float(r["distance_miles"]) for r in valid_predictions if r.get("distance_miles") is not None]
        durations = [float(r["scheduled_elapsed_minutes"]) for r in valid_predictions if r.get("scheduled_elapsed_minutes") is not None]

        # 2. PSI on Probabilities and Delay
        prob_bins = self.baseline.get("probability_bins", {})
        c_bins = prob_bins.get("cancellation", {}).get("bins", [0.0, 0.01, 0.02, 0.04, 0.08, 0.15, 1.0])
        c_exp = prob_bins.get("cancellation", {}).get("expected_frequencies", [0.582, 0.241, 0.115, 0.046, 0.014, 0.002])
        cancel_psi = self.calculate_psi(c_exp, cancel_probs, c_bins)

        d_bins = prob_bins.get("delay120", {}).get("bins", [0.0, 0.02, 0.05, 0.10, 0.20, 1.0])
        d_exp = prob_bins.get("delay120", {}).get("expected_frequencies", [0.420, 0.335, 0.165, 0.068, 0.012])
        delay120_psi = self.calculate_psi(d_exp, delay_probs, d_bins)

        r_bins = prob_bins.get("continuous_delay_minutes", {}).get("bins", [0.0, 10.0, 20.0, 40.0, 60.0, 120.0, 1000.0])
        r_exp = prob_bins.get("continuous_delay_minutes", {}).get("expected_frequencies", [0.380, 0.260, 0.190, 0.100, 0.055, 0.015])
        reg_delay_psi = self.calculate_psi(r_exp, exp_delays, r_bins)

        # 3. Kolmogorov-Smirnov Test on Numeric Geometry vs Baseline Generative Priors
        dur_stats = self.baseline.get("numeric_feature_percentiles", {}).get("scheduled_elapsed_minutes", {})
        baseline_dur_mean = dur_stats.get("mean", 152.4)
        baseline_dur_std = dur_stats.get("std", 78.5)
        np.random.seed(42)
        ref_durations = np.random.normal(baseline_dur_mean, baseline_dur_std, 1000)
        ks_duration = stats.ks_2samp(durations, ref_durations) if durations else None

        dist_stats = self.baseline.get("numeric_feature_percentiles", {}).get("distance_miles", {})
        baseline_dist_mean = dist_stats.get("mean", 925.6)
        baseline_dist_std = dist_stats.get("std", 620.4)
        ref_distances = np.random.normal(baseline_dist_mean, baseline_dist_std, 1000)
        ks_distance = stats.ks_2samp(distances, ref_distances) if distances else None

        # 4. Carrier Distribution Divergence
        carrier_baseline = self.baseline.get("carrier_frequencies", {})
        carrier_counts: Dict[str, int] = {}
        for r in valid_predictions:
            c = r.get("carrier_code", "other")
            carrier_counts[c] = carrier_counts.get(c, 0) + 1
        carrier_actual_freqs = {c: count / sample_size for c, count in carrier_counts.items()}
        carrier_max_delta = 0.0
        most_drifted_carrier = "None"
        for c, base_f in carrier_baseline.items():
            act_f = carrier_actual_freqs.get(c, 0.0)
            delta = abs(act_f - base_f)
            if delta > carrier_max_delta:
                carrier_max_delta = delta
                most_drifted_carrier = c

        # 5. Drift Level Determination
        max_psi = max(cancel_psi, delay120_psi, reg_delay_psi)
        if max_psi >= 0.25:
            drift_level = "CRITICAL"
        elif max_psi >= 0.10 or (ks_duration and ks_duration.pvalue < 0.01) or carrier_max_delta > 0.15:
            drift_level = "WARNING"
        else:
            drift_level = "NORMAL"

        return {
            "status": "EVALUATED",
            "sample_size": sample_size,
            "drift_level": drift_level,
            "metrics": {
                "cancellation_probability_psi": cancel_psi,
                "delay_120m_probability_psi": delay120_psi,
                "continuous_delay_psi": reg_delay_psi,
                "max_psi": max_psi,
                "duration_ks_statistic": round(float(ks_duration.statistic), 4) if ks_duration else None,
                "duration_ks_pvalue": round(float(ks_duration.pvalue), 4) if ks_duration else None,
                "distance_ks_statistic": round(float(ks_distance.statistic), 4) if ks_distance else None,
                "distance_ks_pvalue": round(float(ks_distance.pvalue), 4) if ks_distance else None,
                "carrier_max_frequency_delta": round(float(carrier_max_delta), 4),
                "most_drifted_carrier": most_drifted_carrier
            },
            "thresholds": {
                "psi_warning": 0.10,
                "psi_critical": 0.25,
                "ks_pvalue_significance": 0.01
            }
        }
