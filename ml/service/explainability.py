"""
FLYO STAGE 8 — SHAP MODEL EXPLAINABILITY SERVICE
Computes exact TreeSHAP feature attributions for disruption predictions.
Extracts top positive and negative risk factors, directional impacts,
and enforces clear distinction between statistical correlation and physical causality.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import joblib
import numpy as np
import pandas as pd
import shap

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("ModelExplainability")

ARTIFACTS_DIR = Path(__file__).resolve().parent.parent / "artifacts"

FEATURE_FRIENDLY_NAMES = {
    "delhi_winter_fog_risk": "Delhi Morning Winter Fog Window",
    "mumbai_monsoon_convection_risk": "Mumbai Monsoon Convective Window",
    "is_dep_morning_peak": "Morning Airspace Peak (06:00-09:59)",
    "is_dep_evening_peak": "Evening Airspace Peak (17:00-20:59)",
    "is_dep_night": "Night Operations / Curfew Buffer",
    "scheduled_dep_hour": "Scheduled Departure Hour",
    "scheduled_elapsed_minutes": "Scheduled Flight Duration",
    "distance_miles": "Corridor Distance",
    "precip_mm": "Surface Precipitation",
    "wind_speed_kmh": "Surface Wind Speed",
    "visibility_km": "Visibility",
    "temp_c": "Surface Temperature",
    "is_thunderstorm_reported": "Active Thunderstorm / Lightning",
    "carrier_prior_cancel_rate": "Carrier Historical Cancellation Prior",
    "carrier_prior_delay_rate": "Carrier Historical Delay Prior",
    "origin_prior_cancel_rate": "Origin Airport Disruption Prior",
    "dest_prior_cancel_rate": "Destination Airport Disruption Prior",
    "route_prior_mean_delay": "Corridor Average Historical Delay",
    "orig_DEL": "Departure from Delhi Hub (DEL)",
    "orig_BOM": "Departure from Mumbai Hub (BOM)",
    "orig_BLR": "Departure from Bengaluru Hub (BLR)",
    "orig_HYD": "Departure from Hyderabad Hub (HYD)",
    "dest_DEL": "Arrival into Delhi Hub (DEL)",
    "dest_BOM": "Arrival into Mumbai Hub (BOM)",
}

class ShapExplainabilityService:
    """
    Computes and formats SHAP feature attributions for real-time model transparency.
    """

    def __init__(self):
        self.explainer: Optional[shap.TreeExplainer] = None
        self._load_explainer()

    def _load_explainer(self):
        explainer_path = ARTIFACTS_DIR / "shap_explainer_v3.joblib"
        if explainer_path.exists():
            try:
                self.explainer = joblib.load(explainer_path)
                logger.info("Loaded SHAP TreeExplainer v3 successfully.")
            except Exception as e:
                logger.warning(f"Could not load SHAP TreeExplainer: {e}")
        else:
            logger.info("SHAP explainer artifact not found; will evaluate on demand.")

    def explain_prediction(
        self,
        features_df: pd.DataFrame,
        top_k: int = 5
    ) -> Dict[str, Any]:
        """
        Computes SHAP attributions for a single-row feature dataframe.
        """
        if self.explainer is None:
            self._load_explainer()

        if self.explainer is None or features_df.empty:
            return {
                "explainability_available": False,
                "reason": "SHAP explainer artifact not loaded in memory.",
                "top_contributing_factors": [],
                "base_value": 0.0,
            }

        try:
            shap_values = self.explainer.shap_values(features_df)
            if isinstance(shap_values, list):
                # Binary classification: index 1 is positive class
                values = shap_values[1][0] if len(shap_values) > 1 else shap_values[0][0]
            elif isinstance(shap_values, np.ndarray) and len(shap_values.shape) > 1:
                values = shap_values[0]
            else:
                values = shap_values

            base_val = float(self.explainer.expected_value[1] if isinstance(self.explainer.expected_value, (list, np.ndarray)) else self.explainer.expected_value)

            feature_names = list(features_df.columns)
            attributions = []

            for name, val in zip(feature_names, values):
                raw_val = features_df[name].iloc[0]
                attributions.append({
                    "feature_name": name,
                    "display_name": FEATURE_FRIENDLY_NAMES.get(name, name.replace("_", " ").title()),
                    "feature_value": float(raw_val) if not np.isnan(raw_val) else 0.0,
                    "shap_value": round(float(val), 4),
                    "impact_direction": "INCREASES_RISK" if val > 0 else "DECREASES_RISK",
                    "absolute_magnitude": abs(float(val)),
                })

            # Sort by absolute impact
            attributions.sort(key=lambda x: x["absolute_magnitude"], reverse=True)
            top_factors = attributions[:top_k]

            return {
                "explainability_available": True,
                "base_value": round(base_val, 4),
                "top_contributing_factors": top_factors,
                "causal_disclaimer": (
                    "STATISTICAL ATTRIBUTION NOTICE: Factor contributions represent statistical feature "
                    "weights derived via TreeSHAP within the XGBoost model. They indicate correlation "
                    "patterns in training data, not independent physical causation."
                ),
            }
        except Exception as e:
            logger.error(f"Error computing SHAP values: {e}")
            return {
                "explainability_available": False,
                "reason": f"SHAP calculation exception: {str(e)}",
                "top_contributing_factors": [],
                "base_value": 0.0,
            }
