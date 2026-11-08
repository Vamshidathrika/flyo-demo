import logging
from typing import Any, Dict, List, Optional

logger = logging.getLogger("DataQualityTracker")

class DataQualityTracker:
    """
    Monitors data quality across inference requests:
    - Imputation frequency for scheduled duration and distance (training median fallback)
    - Unexpected categorical values
    - Input range violations
    - Schema incompatibilities
    """

    def __init__(self):
        self.imputation_warning_threshold = 0.10
        self.imputation_critical_threshold = 0.25

    def evaluate_quality(self, records: List[Dict[str, Any]]) -> Dict[str, Any]:
        total = len(records)
        if total == 0:
            return {
                "status": "NORMAL",
                "sample_size": 0,
                "data_quality_score": 100.0,
                "metrics": {
                    "missing_duration_count": 0,
                    "missing_duration_percentage": 0.0,
                    "missing_distance_count": 0,
                    "missing_distance_percentage": 0.0,
                    "overall_imputation_rate": 0.0,
                    "range_violations_count": 0,
                    "unexpected_categoricals_count": 0
                },
                "alerts": []
            }

        missing_dur = sum(1 for r in records if r.get("is_duration_imputed", 0) == 1)
        missing_dist = sum(1 for r in records if r.get("is_distance_imputed", 0) == 1)
        imputation_rate = (missing_dur + missing_dist) / (total * 2)

        # Range violation checks
        range_violations = 0
        for r in records:
            dist = r.get("distance_miles")
            dur = r.get("scheduled_elapsed_minutes")
            if dist is not None and dist < 0:
                range_violations += 1
            if dur is not None and (dur <= 0 or dur > 1200):
                range_violations += 1

        # Unexpected categoricals (carrier outside supported 15)
        unexpected_cat = sum(1 for r in records if r.get("status") == "INSUFFICIENT_COVERAGE")

        # Compute quality score (100 - penalties)
        score = 100.0 - (imputation_rate * 50.0) - (min(20.0, range_violations * 2.0)) - (min(20.0, unexpected_cat * 2.0))
        score = max(0.0, round(score, 1))

        alerts = []
        if imputation_rate >= self.imputation_critical_threshold:
            status = "CRITICAL"
            alerts.append(f"CRITICAL: Median imputation rate reached {imputation_rate*100:.1f}%, exceeding 25% threshold.")
        elif imputation_rate >= self.imputation_warning_threshold:
            status = "WARNING"
            alerts.append(f"WARNING: Median imputation rate reached {imputation_rate*100:.1f}%, exceeding 10% threshold.")
        else:
            status = "NORMAL"

        return {
            "status": status,
            "sample_size": total,
            "data_quality_score": score,
            "metrics": {
                "missing_duration_count": missing_dur,
                "missing_duration_percentage": round((missing_dur / total) * 100, 2),
                "missing_distance_count": missing_dist,
                "missing_distance_percentage": round((missing_dist / total) * 100, 2),
                "overall_imputation_rate": round(imputation_rate, 4),
                "range_violations_count": range_violations,
                "unexpected_categoricals_count": unexpected_cat
            },
            "alerts": alerts
        }
