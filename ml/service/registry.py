import json
import logging
import time
from pathlib import Path
from typing import Any, Dict, Optional

import joblib
import xgboost as xgb

from ml.service.config import ARTIFACTS_DIR, MODEL_VERSION, SUPPORTED_CARRIERS, TOP_HUBS, INDIAN_AIRPORT_CODES
from ml.src.leakage_free_preprocessor import LeakageFreePreprocessor

logger = logging.getLogger("ModelRegistry")

class ModelRegistry:
    """
    Singleton registry managing in-memory ML model artifacts, feature transformers,
    and metadata for the FastAPI inference service.
    """
    _instance: Optional["ModelRegistry"] = None

    def __init__(self):
        self.start_time: float = time.time()
        self.is_loaded: bool = False
        self.calibrated_cancellation = None
        self.calibrated_delay120 = None
        self.xgb_delay_regressor: Optional[xgb.Booster] = None
        self.preprocessor: Optional[LeakageFreePreprocessor] = None
        self.feature_schema: Dict[str, Any] = {}
        self.evaluation_metrics: Dict[str, Any] = {}
        self.feature_importance: Dict[str, Any] = {}
        self.supported_airports: set = set()

    @classmethod
    def get_instance(cls) -> "ModelRegistry":
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def load_artifacts(self, artifacts_dir: Optional[Path] = None):
        """Loads all v2 model artifacts and preprocessors into memory."""
        target_dir = artifacts_dir or ARTIFACTS_DIR
        logger.info(f"Loading model artifacts from {target_dir} (Version: {MODEL_VERSION})...")

        # 1. Feature Preprocessor
        prep_path = target_dir / "preprocessor_v2.joblib"
        if not prep_path.exists():
            raise FileNotFoundError(f"Preprocessor artifact missing: {prep_path}")
        self.preprocessor = LeakageFreePreprocessor.load(prep_path)
        logger.info(f"Loaded Preprocessor v2 with {len(self.preprocessor.feature_columns)} features.")

        # 2. Calibrated Cancellation Model
        cancel_path = target_dir / "calibrated_cancellation_v2.joblib"
        if not cancel_path.exists():
            raise FileNotFoundError(f"Cancellation model missing: {cancel_path}")
        self.calibrated_cancellation = joblib.load(cancel_path)
        logger.info("Loaded Calibrated Cancellation Classifier v2.")

        # 3. Calibrated Extreme Delay (>=120m) Model
        delay_path = target_dir / "calibrated_delay120_v2.joblib"
        if not delay_path.exists():
            raise FileNotFoundError(f"Extreme delay model missing: {delay_path}")
        self.calibrated_delay120 = joblib.load(delay_path)
        logger.info("Loaded Calibrated Extreme Delay (>=120m) Classifier v2.")

        # 4. Continuous Delay Regressor Booster
        reg_path = target_dir / "xgb_delay_regressor_v2.json"
        if not reg_path.exists():
            raise FileNotFoundError(f"Delay regressor missing: {reg_path}")
        self.xgb_delay_regressor = xgb.Booster()
        self.xgb_delay_regressor.load_model(str(reg_path))
        logger.info("Loaded XGBoost Continuous Delay Regressor Booster v2.")

        # 5. Extract BTS US Domestic Airports from empirical priors
        origin_airports = set(self.preprocessor.training_priors.get("origin_stats", {}).keys())
        dest_airports = set(self.preprocessor.training_priors.get("dest_stats", {}).keys())
        self.supported_airports = origin_airports.union(dest_airports)
        logger.info(f"Loaded {len(self.supported_airports)} verified US domestic BTS airports.")

        # 6. Metadata and Metrics
        schema_path = target_dir / "feature_schema_v2.json"
        if schema_path.exists():
            with open(schema_path, "r") as f:
                self.feature_schema = json.load(f)

        metrics_path = target_dir / "evaluation_metrics_v2.json"
        if metrics_path.exists():
            with open(metrics_path, "r") as f:
                self.evaluation_metrics = json.load(f)

        importance_path = target_dir / "feature_importance_v2.json"
        if importance_path.exists():
            with open(importance_path, "r") as f:
                self.feature_importance = json.load(f)

        self.is_loaded = True
        logger.info(f"All ML v2 artifacts successfully initialized for inference.")

    def get_models_status(self) -> Dict[str, bool]:
        return {
            "preprocessor": self.preprocessor is not None,
            "cancellation_model": self.calibrated_cancellation is not None,
            "delay120_model": self.calibrated_delay120 is not None,
            "delay_regressor": self.xgb_delay_regressor is not None,
        }

    def get_uptime_seconds(self) -> float:
        return time.time() - self.start_time

    def get_metadata(self) -> Dict[str, Any]:
        return {
            "model_version": MODEL_VERSION,
            "models": {
                "cancellation_classifier": "XGBoost + CalibratedClassifierCV (Sigmoid)",
                "delay120_classifier": "XGBoost + CalibratedClassifierCV (Sigmoid)",
                "delay_regressor": "XGBoost Regressor (Squared Error)"
            },
            "supported_carriers": sorted(list(SUPPORTED_CARRIERS)),
            "top_hubs": sorted(list(TOP_HUBS)),
            "unsupported_airspaces": ["India (DEL, BOM, BLR, HYD, etc.)", "International non-US"],
            "metrics": self.evaluation_metrics,
            "feature_count": len(self.preprocessor.feature_columns) if self.preprocessor else 0,
            "feature_names": self.preprocessor.feature_columns if self.preprocessor else []
        }
