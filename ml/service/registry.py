from __future__ import annotations

import json
import logging
import time
from pathlib import Path
from typing import Any, Dict, Optional, Set

import joblib
import xgboost as xgb

from ml.service.config import ARTIFACTS_DIR, MODEL_VERSION, SUPPORTED_CARRIERS, TOP_HUBS, INDIAN_AIRPORT_CODES

logger = logging.getLogger("ModelRegistry")

class ModelRegistry:
    """
    Singleton registry managing in-memory ML model artifacts, feature transformers,
    and metadata for the FastAPI inference service.
    Supports v3 multi-region models with v2 backward compatibility.
    """
    _instance: Optional["ModelRegistry"] = None

    def __init__(self):
        self.start_time: float = time.time()
        self.is_loaded: bool = False
        self.version: str = MODEL_VERSION

        # v3 Models
        self.preprocessor = None
        self.calibrated_disruption = None
        self.xgb_delay_p50: Optional[xgb.Booster] = None
        self.xgb_delay_p10: Optional[xgb.Booster] = None
        self.xgb_delay_p90: Optional[xgb.Booster] = None
        self.shap_explainer = None

        # v2 Backward-compatible pointers
        self.calibrated_cancellation = None
        self.calibrated_delay120 = None
        self.xgb_delay_regressor: Optional[xgb.Booster] = None

        # Metadata
        self.feature_schema: Dict[str, Any] = {}
        self.evaluation_metrics: Dict[str, Any] = {}
        self.supported_airports: Set[str] = set()

    @classmethod
    def get_instance(cls) -> "ModelRegistry":
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def load_artifacts(self, artifacts_dir: Optional[Path] = None):
        target_dir = artifacts_dir or ARTIFACTS_DIR
        logger.info(f"Loading model artifacts from {target_dir} (Version: {MODEL_VERSION})...")

        # Check if v3 artifacts exist
        prep_v3 = target_dir / "preprocessor_v3.joblib"
        if prep_v3.exists():
            self._load_v3(target_dir)
        else:
            self._load_v2_fallback(target_dir)

        self.is_loaded = True
        logger.info(f"ML artifacts successfully loaded and ready for inference.")

    def _load_v3(self, target_dir: Path):
        logger.info("Initializing Stage 8 v3 Enterprise Model Suite...")
        self.preprocessor = joblib.load(target_dir / "preprocessor_v3.joblib")

        disrupt_path = target_dir / "calibrated_disruption_v3.joblib"
        if disrupt_path.exists():
            self.calibrated_disruption = joblib.load(disrupt_path)
            self.calibrated_cancellation = self.calibrated_disruption # compatibility
            self.calibrated_delay120 = self.calibrated_disruption # compatibility

        # Delay Regressors (P50, P10, P90)
        p50_path = target_dir / "xgb_delay_p50_v3.json"
        if p50_path.exists():
            self.xgb_delay_p50 = xgb.Booster()
            self.xgb_delay_p50.load_model(str(p50_path))
            self.xgb_delay_regressor = self.xgb_delay_p50

        p10_path = target_dir / "xgb_delay_p10_v3.json"
        if p10_path.exists():
            self.xgb_delay_p10 = xgb.Booster()
            self.xgb_delay_p10.load_model(str(p10_path))

        p90_path = target_dir / "xgb_delay_p90_v3.json"
        if p90_path.exists():
            self.xgb_delay_p90 = xgb.Booster()
            self.xgb_delay_p90.load_model(str(p90_path))

        # SHAP Explainer
        shap_path = target_dir / "shap_explainer_v3.joblib"
        if shap_path.exists():
            try:
                self.shap_explainer = joblib.load(shap_path)
            except Exception as e:
                logger.warning(f"Could not load SHAP explainer from joblib: {e}")

        # Supported Airports (US Hubs + Indian Hubs)
        self.supported_airports = set(TOP_HUBS).union(INDIAN_AIRPORT_CODES)
        if hasattr(self.preprocessor, "priors"):
            orig_priors = set(self.preprocessor.priors.get("origin_priors", {}).keys())
            dest_priors = set(self.preprocessor.priors.get("dest_priors", {}).keys())
            self.supported_airports.update(orig_priors)
            self.supported_airports.update(dest_priors)

        schema_path = target_dir / "feature_schema_v3.json"
        if schema_path.exists():
            with open(schema_path, "r") as f:
                self.feature_schema = json.load(f)

        metrics_path = target_dir / "evaluation_metrics_v3.json"
        if metrics_path.exists():
            with open(metrics_path, "r") as f:
                self.evaluation_metrics = json.load(f)

    def _load_v2_fallback(self, target_dir: Path):
        logger.info("v3 artifacts not found. Falling back to v2 multi-season artifacts...")
        from ml.src.leakage_free_preprocessor import LeakageFreePreprocessor
        prep_path = target_dir / "preprocessor_v2.joblib"
        self.preprocessor = LeakageFreePreprocessor.load(prep_path)

        cancel_path = target_dir / "calibrated_cancellation_v2.joblib"
        if cancel_path.exists():
            self.calibrated_cancellation = joblib.load(cancel_path)

        delay_path = target_dir / "calibrated_delay120_v2.joblib"
        if delay_path.exists():
            self.calibrated_delay120 = joblib.load(delay_path)

        reg_path = target_dir / "xgb_delay_regressor_v2.json"
        if reg_path.exists():
            self.xgb_delay_regressor = xgb.Booster()
            self.xgb_delay_regressor.load_model(str(reg_path))

        schema_path = target_dir / "feature_schema_v2.json"
        if schema_path.exists():
            with open(schema_path, "r") as f:
                self.feature_schema = json.load(f)

        metrics_path = target_dir / "evaluation_metrics_v2.json"
        if metrics_path.exists():
            with open(metrics_path, "r") as f:
                self.evaluation_metrics = json.load(f)

    def get_models_status(self) -> Dict[str, bool]:
        return {
            "preprocessor": self.preprocessor is not None,
            "disruption_classifier": (self.calibrated_disruption is not None or self.calibrated_cancellation is not None),
            "delay_regressor_p50": (self.xgb_delay_p50 is not None or self.xgb_delay_regressor is not None),
            "delay_regressor_quantile_bounds": (self.xgb_delay_p10 is not None and self.xgb_delay_p90 is not None),
            "shap_explainer": self.shap_explainer is not None,
            # Backward-compatibility keys:
            "cancellation_model": (self.calibrated_disruption is not None or self.calibrated_cancellation is not None),
            "delay120_model": (self.calibrated_disruption is not None or self.calibrated_delay120 is not None),
            "delay_regressor": (self.xgb_delay_p50 is not None or self.xgb_delay_regressor is not None),
        }

    def get_uptime_seconds(self) -> float:
        return time.time() - self.start_time

    def get_metadata(self) -> Dict[str, Any]:
        return {
            "model_version": self.version,
            "supported_carriers": sorted(list(SUPPORTED_CARRIERS)),
            "top_hubs": sorted(list(TOP_HUBS)),
            "feature_count": self.feature_schema.get("feature_count", len(self.feature_schema.get("feature_names", []))),
            "features": self.feature_schema.get("feature_names", []),
            "training_period": "2024-01-01 to 2024-06-15",
            "validation_period": "2024-06-15 to 2024-07-15",
            "evaluation_metrics": self.evaluation_metrics,
        }
