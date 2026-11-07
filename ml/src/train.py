import json
import logging
import sys
from pathlib import Path
from typing import Dict, Tuple

import joblib
import numpy as np
import pandas as pd
from sklearn.calibration import CalibratedClassifierCV
from sklearn.dummy import DummyClassifier, DummyRegressor
from sklearn.frozen import FrozenEstimator
from sklearn.linear_model import LogisticRegression, Ridge
from sklearn.preprocessing import StandardScaler
import xgboost as xgb

from .config import PROCESSED_DATA_DIR
from .preprocessor import FlightDataPreprocessor

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("Trainer")

ARTIFACTS_DIR = Path(__file__).resolve().parent.parent / "artifacts"
ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)

class ModelTrainer:
    def __init__(self, data_path: Path = PROCESSED_DATA_DIR / "flights_2024_01.csv"):
        self.data_path = data_path
        self.preprocessor = FlightDataPreprocessor()
        self.models: Dict = {}
        self.training_metadata: Dict = {}

    def prepare_temporal_datasets(self) -> Tuple:
        logger.info(f"Loading raw dataset from {self.data_path}")
        df = pd.read_csv(self.data_path)
        logger.info(f"Loaded {len(df)} flight records.")

        # Weather enrichment
        df_enriched = self.preprocessor.enrich_with_weather(df)

        # Strict Temporal Split:
        # Days 1-20: Train
        # Days 21-25: Validation
        # Days 26-31: Held-out Test
        train_mask = df_enriched["day_of_month"] <= 20
        val_mask = (df_enriched["day_of_month"] >= 21) & (df_enriched["day_of_month"] <= 25)
        test_mask = df_enriched["day_of_month"] >= 26

        train_df = df_enriched[train_mask].copy()
        val_df = df_enriched[val_mask].copy()
        test_df = df_enriched[test_mask].copy()

        logger.info(f"Split sizes: Train={len(train_df)}, Val={len(val_df)}, Test={len(test_df)}")

        # Fit priors strictly on train
        self.preprocessor.fit_training_priors(train_df)

        # Transform features
        X_train, y_c_train, y_d_train, y_r_train = self.preprocessor.transform(train_df)
        X_val, y_c_val, y_d_val, y_r_val = self.preprocessor.transform(val_df)
        X_test, y_c_test, y_d_test, y_r_test = self.preprocessor.transform(test_df)

        # Save preprocessor artifact and schema
        self.preprocessor.save(ARTIFACTS_DIR / "preprocessor_v1.joblib")
        with open(ARTIFACTS_DIR / "feature_schema.json", "w", encoding="utf-8") as f:
            json.dump({
                "feature_count": len(self.preprocessor.feature_columns),
                "feature_names": self.preprocessor.feature_columns,
                "created_at_utc": pd.Timestamp.utcnow().isoformat(),
                "temporal_split": {
                    "train_days": "1-20",
                    "val_days": "21-25",
                    "test_days": "26-31",
                    "train_rows": len(train_df),
                    "val_rows": len(val_df),
                    "test_rows": len(test_df)
                }
            }, f, indent=2)

        return (
            (X_train, y_c_train, y_d_train, y_r_train, train_df),
            (X_val, y_c_val, y_d_val, y_r_val, val_df),
            (X_test, y_c_test, y_d_test, y_r_test, test_df)
        )

    def train_cancellation_models(self, X_train, y_c_train, X_val, y_c_val):
        logger.info("=== Training Cancellation Models ===")
        # 1. Baseline: Dummy Classifier
        dummy_c = DummyClassifier(strategy="prior")
        dummy_c.fit(X_train, y_c_train)
        self.models["dummy_cancellation"] = dummy_c

        # 2. Baseline: Logistic Regression
        scaler = StandardScaler()
        X_train_scaled = scaler.fit_transform(X_train)
        X_val_scaled = scaler.transform(X_val)

        lr_c = LogisticRegression(class_weight="balanced", max_iter=1000, random_state=42)
        lr_c.fit(X_train_scaled, y_c_train)
        self.models["lr_cancellation"] = lr_c
        self.models["cancellation_scaler"] = scaler

        # 3. XGBoost Classifier
        neg_count = (y_c_train == 0).sum()
        pos_count = (y_c_train == 1).sum()
        scale_pos = neg_count / pos_count if pos_count > 0 else 1.0
        logger.info(f"Cancellation class balance: {neg_count} neg, {pos_count} pos (scale_pos_weight={scale_pos:.2f})")

        xgb_c = xgb.XGBClassifier(
            n_estimators=300,
            max_depth=5,
            learning_rate=0.05,
            scale_pos_weight=scale_pos,
            subsample=0.8,
            colsample_bytree=0.8,
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
        self.models["xgb_cancellation"] = xgb_c
        xgb_c.save_model(ARTIFACTS_DIR / "xgb_cancellation_v1.json")

        # 4. Calibrated Classifier (using FrozenEstimator on validation split)
        logger.info("Fitting probability calibrator on validation split...")
        frozen_xgb_c = FrozenEstimator(xgb_c)
        calibrated_c = CalibratedClassifierCV(estimator=frozen_xgb_c, method="sigmoid")
        calibrated_c.fit(X_val, y_c_val)
        self.models["calibrated_cancellation"] = calibrated_c
        joblib.dump(calibrated_c, ARTIFACTS_DIR / "calibrated_cancellation_v1.joblib")

        logger.info("Cancellation models trained and serialized successfully.")

    def train_delay120_models(self, X_train, y_d_train, X_val, y_d_val):
        logger.info("=== Training Extreme Delay (>= 120m) Models ===")
        # 1. Baseline: Dummy Classifier
        dummy_d = DummyClassifier(strategy="prior")
        dummy_d.fit(X_train, y_d_train)
        self.models["dummy_delay120"] = dummy_d

        # 2. Baseline: Logistic Regression
        scaler = StandardScaler()
        X_train_scaled = scaler.fit_transform(X_train)
        lr_d = LogisticRegression(class_weight="balanced", max_iter=1000, random_state=42)
        lr_d.fit(X_train_scaled, y_d_train)
        self.models["lr_delay120"] = lr_d
        self.models["delay120_scaler"] = scaler

        # 3. XGBoost Classifier
        neg_count = (y_d_train == 0).sum()
        pos_count = (y_d_train == 1).sum()
        scale_pos = neg_count / pos_count if pos_count > 0 else 1.0
        logger.info(f"Delay120 class balance: {neg_count} neg, {pos_count} pos (scale_pos_weight={scale_pos:.2f})")

        xgb_d = xgb.XGBClassifier(
            n_estimators=300,
            max_depth=5,
            learning_rate=0.05,
            scale_pos_weight=scale_pos,
            subsample=0.8,
            colsample_bytree=0.8,
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
        self.models["xgb_delay120"] = xgb_d
        xgb_d.save_model(ARTIFACTS_DIR / "xgb_delay120_v1.json")

        # 4. Calibrated Classifier
        frozen_xgb_d = FrozenEstimator(xgb_d)
        calibrated_d = CalibratedClassifierCV(estimator=frozen_xgb_d, method="sigmoid")
        calibrated_d.fit(X_val, y_d_val)
        self.models["calibrated_delay120"] = calibrated_d
        joblib.dump(calibrated_d, ARTIFACTS_DIR / "calibrated_delay120_v1.joblib")

        logger.info("Extreme delay models trained and serialized successfully.")

    def train_delay_regressor(self, X_train, y_c_train, y_r_train, X_val, y_c_val, y_r_val):
        logger.info("=== Training Continuous Arrival Delay Regressors ===")
        # Filter strictly to completed (non-cancelled) flights
        train_mask = (y_c_train == 0)
        val_mask = (y_c_val == 0)

        X_train_comp = X_train[train_mask]
        y_r_train_comp = y_r_train[train_mask]
        X_val_comp = X_val[val_mask]
        y_r_val_comp = y_r_val[val_mask]

        # 1. Baseline: Dummy Regressor (mean)
        dummy_reg = DummyRegressor(strategy="mean")
        dummy_reg.fit(X_train_comp, y_r_train_comp)
        self.models["dummy_delay_reg"] = dummy_reg

        # 2. Baseline: Ridge Regressor
        scaler = StandardScaler()
        X_train_comp_scaled = scaler.fit_transform(X_train_comp)
        ridge_reg = Ridge(alpha=100.0, random_state=42)
        ridge_reg.fit(X_train_comp_scaled, y_r_train_comp)
        self.models["ridge_delay_reg"] = ridge_reg
        self.models["delay_reg_scaler"] = scaler

        # 3. XGBoost Regressor
        xgb_reg = xgb.XGBRegressor(
            n_estimators=350,
            max_depth=6,
            learning_rate=0.04,
            subsample=0.85,
            colsample_bytree=0.85,
            objective="reg:squarederror",
            eval_metric="mae",
            early_stopping_rounds=30,
            random_state=42,
            n_jobs=-1
        )
        xgb_reg.fit(
            X_train_comp, y_r_train_comp,
            eval_set=[(X_train_comp, y_r_train_comp), (X_val_comp, y_r_val_comp)],
            verbose=False
        )
        self.models["xgb_delay_reg"] = xgb_reg
        xgb_reg.save_model(ARTIFACTS_DIR / "xgb_delay_regressor_v1.json")

        logger.info("Continuous arrival delay regressor trained and serialized successfully.")

    def run_training_pipeline(self):
        train_data, val_data, test_data = self.prepare_temporal_datasets()
        X_train, y_c_train, y_d_train, y_r_train, train_df = train_data
        X_val, y_c_val, y_d_val, y_r_val, val_df = val_data
        X_test, y_c_test, y_d_test, y_r_test, test_df = test_data

        # Save partitioned test and validation sets for evaluation script
        joblib.dump(test_data, ARTIFACTS_DIR / "test_split_bundle.joblib")
        joblib.dump(val_data, ARTIFACTS_DIR / "val_split_bundle.joblib")
        joblib.dump(self.models, ARTIFACTS_DIR / "all_models_bundle.joblib")

        self.train_cancellation_models(X_train, y_c_train, X_val, y_c_val)
        self.train_delay120_models(X_train, y_d_train, X_val, y_d_val)
        self.train_delay_regressor(X_train, y_c_train, y_r_train, X_val, y_c_val, y_r_val)

        # Re-save all models bundle with fitted models
        joblib.dump(self.models, ARTIFACTS_DIR / "all_models_bundle.joblib")
        logger.info("=== Full Training Pipeline Finished Successfully ===")

if __name__ == "__main__":
    trainer = ModelTrainer()
    trainer.run_training_pipeline()
