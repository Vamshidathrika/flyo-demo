import json
from pathlib import Path
import unittest

import joblib
import numpy as np
import pandas as pd
import xgboost as xgb

from ml.src.leakage_free_preprocessor import ALL_BTS_CARRIERS, TOP_20_AIRPORTS, LeakageFreePreprocessor

ARTIFACTS_DIR = Path(__file__).resolve().parent.parent / "artifacts"

class TestModelPipelineV2(unittest.TestCase):

    def setUp(self):
        self.artifacts_dir = ARTIFACTS_DIR
        self.preprocessor_path = self.artifacts_dir / "preprocessor_v2.joblib"
        self.schema_path = self.artifacts_dir / "feature_schema_v2.json"
        self.metrics_path = self.artifacts_dir / "evaluation_metrics_v2.json"

    def test_v2_artifacts_exist_and_loadable(self):
        """Verifies that all required v2 multi-season model artifacts exist and load cleanly."""
        self.assertTrue(self.preprocessor_path.exists(), "preprocessor_v2.joblib must exist")
        self.assertTrue(self.schema_path.exists(), "feature_schema_v2.json must exist")
        self.assertTrue(self.metrics_path.exists(), "evaluation_metrics_v2.json must exist")
        self.assertTrue((self.artifacts_dir / "xgb_cancellation_v2.json").exists())
        self.assertTrue((self.artifacts_dir / "calibrated_cancellation_v2.joblib").exists())
        self.assertTrue((self.artifacts_dir / "xgb_delay120_v2.json").exists())
        self.assertTrue((self.artifacts_dir / "calibrated_delay120_v2.joblib").exists())
        self.assertTrue((self.artifacts_dir / "xgb_delay_regressor_v2.json").exists())

        # Load boosters and calibrators
        xgb_c = xgb.XGBClassifier()
        xgb_c.load_model(str(self.artifacts_dir / "xgb_cancellation_v2.json"))
        self.assertEqual(xgb_c.n_features_in_, 79)

        xgb_d = xgb.XGBClassifier()
        xgb_d.load_model(str(self.artifacts_dir / "xgb_delay120_v2.json"))
        self.assertEqual(xgb_d.n_features_in_, 79)

        xgb_r = xgb.XGBRegressor()
        xgb_r.load_model(str(self.artifacts_dir / "xgb_delay_regressor_v2.json"))
        self.assertEqual(xgb_r.n_features_in_, 79)

    def test_v2_zero_leakage_and_weather_elimination(self):
        """Verifies that v2 feature schema has zero post-departure fields and zero observed weather."""
        with open(self.schema_path, "r", encoding="utf-8") as f:
            schema = json.load(f)
        features = schema["feature_names"]

        leakage_candidates = [
            "actual_dep_time",
            "dep_delay_minutes",
            "actual_arr_time",
            "arr_delay_minutes",
            "is_delayed_15m",
            "is_delayed_120m",
            "is_cancelled",
            "cancellation_code",
            "cancellation_reason",
            "carrier_delay",
            "weather_delay",
            "nas_delay",
            "security_delay",
            "late_aircraft_delay",
            "origin_temp_c",
            "dest_temp_c",
            "origin_weather_code",
            "dest_weather_code",
            "origin_wind_speed_kmh",
            "dest_wind_speed_kmh"
        ]
        for leaked in leakage_candidates:
            self.assertNotIn(leaked, features, f"Data leakage violation: {leaked} present in v2 features!")

    def test_v2_all_15_carriers_covered(self):
        """Verifies that all 15 reporting domestic carriers have one-hot columns in schema."""
        with open(self.schema_path, "r", encoding="utf-8") as f:
            schema = json.load(f)
        features = schema["feature_names"]

        for carrier in ALL_BTS_CARRIERS:
            self.assertIn(f"carrier_{carrier}", features, f"Missing one-hot feature for carrier {carrier}")

    def test_v2_calibrated_multi_carrier_inference(self):
        """Verifies valid calibrated probabilities across major US carriers (DL, UA, WN, AA)."""
        preprocessor = LeakageFreePreprocessor.load(self.preprocessor_path)
        calibrated_c = joblib.load(self.artifacts_dir / "calibrated_cancellation_v2.joblib")
        calibrated_d = joblib.load(self.artifacts_dir / "calibrated_delay120_v2.joblib")

        test_flights = [
            {"carrier": "WN", "origin": "MDW", "dest": "DEN", "dep": "07:30", "arr": "09:15"},
            {"carrier": "DL", "origin": "ATL", "dest": "JFK", "dep": "14:00", "arr": "16:20"},
            {"carrier": "UA", "origin": "ORD", "dest": "SFO", "dep": "18:45", "arr": "21:30"},
            {"carrier": "AA", "origin": "DFW", "dest": "MIA", "dep": "10:15", "arr": "14:05"}
        ]

        for flight in test_flights:
            df = pd.DataFrame([{
                "flight_id": "TEST",
                "flight_date": "2024-07-15",
                "day_of_month": 15,
                "day_of_week": 1,
                "month": 7,
                "carrier_code": flight["carrier"],
                "origin_airport": flight["origin"],
                "dest_airport": flight["dest"],
                "scheduled_dep_time": flight["dep"],
                "scheduled_arr_time": flight["arr"],
                "scheduled_elapsed_minutes": 150.0,
                "distance_miles": 850.0,
                "is_cancelled": 0,
                "is_delayed_120m": 0,
                "arr_delay_minutes": 0.0
            }])

            X, _, _, _ = preprocessor.transform(df)
            self.assertEqual(len(X.columns), 79)
            self.assertEqual(X.isnull().sum().sum(), 0)

            prob_c = float(calibrated_c.predict_proba(X)[:, 1][0])
            prob_d = float(calibrated_d.predict_proba(X)[:, 1][0])

            self.assertGreaterEqual(prob_c, 0.0)
            self.assertLessEqual(prob_c, 1.0)
            self.assertGreaterEqual(prob_d, 0.0)
            self.assertLessEqual(prob_d, 1.0)

    def test_v1_backup_preserved(self):
        """Verifies that rollback v1 artifacts remain preserved in v1_backup/."""
        backup_dir = self.artifacts_dir / "v1_backup"
        self.assertTrue((backup_dir / "xgb_cancellation_v1.json").exists())
        self.assertTrue((backup_dir / "calibrated_cancellation_v1.joblib").exists())
        self.assertTrue((backup_dir / "xgb_delay120_v1.json").exists())
        self.assertTrue((backup_dir / "calibrated_delay120_v1.joblib").exists())

if __name__ == "__main__":
    unittest.main()
