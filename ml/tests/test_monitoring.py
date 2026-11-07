import os
import tempfile
import unittest
from pathlib import Path
from starlette.testclient import TestClient

from ml.service.alert_engine import AlertEngine
from ml.service.drift_detector import DriftDetector
from ml.service.governance import ModelGovernance
from ml.service.main import MONITORING_API_KEY, app
from ml.service.monitoring_db import MonitoringDB
from ml.service.performance_evaluator import PerformanceEvaluator
from ml.service.quality_tracker import DataQualityTracker
from ml.service.registry import ModelRegistry

class TestMonitoringSuite(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        # Use isolated test database
        cls.temp_dir = tempfile.TemporaryDirectory()
        cls.test_db_path = Path(cls.temp_dir.name) / "test_monitoring.db"
        cls.db = MonitoringDB(db_path=cls.test_db_path)

        ModelRegistry.get_instance().load_artifacts()
        cls.client = TestClient(app)
        cls.auth_headers = {"X-Flyo-Monitoring-Key": MONITORING_API_KEY}

    @classmethod
    def tearDownClass(cls):
        cls.temp_dir.cleanup()

    def test_prediction_record_persistence(self):
        pred_id = self.db.record_prediction(
            model_version="v2.0.0-multiseason",
            schema_version="2.0.0",
            flight_number="AA100",
            carrier_code="AA",
            origin_airport="ORD",
            dest_airport="DFW",
            scheduled_dep_time="14:30",
            scheduled_arr_time="17:15",
            departure_date="2024-07-20",
            status="PREDICTION_AVAILABLE",
            status_reason="Evaluated v2",
            cancellation_probability=0.012,
            delay_120m_probability=0.035,
            expected_delay_minutes=15.4,
            disruption_risk_level="LOW",
            inference_latency_ms=12.4,
            is_duration_imputed=False,
            is_distance_imputed=True,
            distance_miles=800.0,
            scheduled_elapsed_minutes=165.0,
            dep_hour=14,
            arr_hour=17,
            day_of_week=6,
            month=7
        )
        self.assertIsNotNone(pred_id)

        records = self.db.get_predictions(limit=10)
        self.assertTrue(any(r["prediction_id"] == pred_id for r in records))
        saved = next(r for r in records if r["prediction_id"] == pred_id)
        self.assertEqual(saved["outcome_status"], "PENDING_OUTCOME")
        self.assertEqual(saved["is_distance_imputed"], 1)
        self.assertEqual(saved["is_duration_imputed"], 0)

    def test_duplicate_outcome_prevention_and_matching(self):
        # 1. Record pending prediction
        pred_id = self.db.record_prediction(
            model_version="v2.0.0-multiseason",
            schema_version="2.0.0",
            flight_number="DL888",
            carrier_code="DL",
            origin_airport="ATL",
            dest_airport="LAX",
            scheduled_dep_time="09:00",
            scheduled_arr_time="11:30",
            departure_date="2024-07-22",
            status="PREDICTION_AVAILABLE",
            status_reason="Normal",
            cancellation_probability=0.005,
            delay_120m_probability=0.020,
            expected_delay_minutes=5.0,
            disruption_risk_level="LOW",
            inference_latency_ms=10.0,
            is_duration_imputed=False,
            is_distance_imputed=False,
            distance_miles=1946.0,
            scheduled_elapsed_minutes=270.0,
            dep_hour=9,
            arr_hour=11,
            day_of_week=1,
            month=7
        )

        # 2. Ingest ground truth for the first time
        inserted, outcome_id = self.db.ingest_ground_truth(
            flight_number="DL888",
            carrier_code="DL",
            origin_airport="ATL",
            dest_airport="LAX",
            flight_date="2024-07-22",
            is_cancelled=0,
            is_delayed_120m=0,
            arr_delay_minutes=8.0,
            provenance="BTS_ON_TIME_DATABASE"
        )
        self.assertTrue(inserted)
        self.assertNotEqual(outcome_id, "DUPLICATE")

        # 3. Verify prediction status updated to OUTCOME_AVAILABLE
        recs = self.db.get_predictions(limit=50)
        matched_pred = next(r for r in recs if r["prediction_id"] == pred_id)
        self.assertEqual(matched_pred["outcome_status"], "OUTCOME_AVAILABLE")
        self.assertEqual(matched_pred["matched_outcome_id"], outcome_id)

        # 4. Attempt duplicate ingestion (Exact same flight identity and date)
        inserted_dup, dup_id = self.db.ingest_ground_truth(
            flight_number="DL888",
            carrier_code="DL",
            origin_airport="ATL",
            dest_airport="LAX",
            flight_date="2024-07-22",
            is_cancelled=0,
            is_delayed_120m=0,
            arr_delay_minutes=8.0,
            provenance="BTS_ON_TIME_DATABASE"
        )
        self.assertFalse(inserted_dup, "Duplicate outcome insertion must be rejected")
        self.assertEqual(dup_id, outcome_id)

    def test_drift_calculations_and_min_sample_size(self):
        detector = DriftDetector()

        # Less than 30 samples -> INSUFFICIENT_DATA
        small_sample = [
            {
                "status": "PREDICTION_AVAILABLE",
                "cancellation_probability": 0.01,
                "delay_120m_probability": 0.03,
                "expected_delay_minutes": 10.0,
                "distance_miles": 800.0,
                "scheduled_elapsed_minutes": 140.0,
                "carrier_code": "AA"
            }
            for _ in range(10)
        ]
        res_small = detector.evaluate_drift(small_sample)
        self.assertEqual(res_small["status"], "INSUFFICIENT_DATA")
        self.assertEqual(res_small["drift_level"], "INSUFFICIENT_DATA")

        # 40 samples (>= 30) -> EVALUATED
        sufficient_sample = [
            {
                "status": "PREDICTION_AVAILABLE",
                "cancellation_probability": 0.012 + (i * 0.001),
                "delay_120m_probability": 0.03 + (i * 0.002),
                "expected_delay_minutes": 12.0 + i,
                "distance_miles": 750.0 + (i * 10),
                "scheduled_elapsed_minutes": 130.0 + i,
                "carrier_code": "WN" if i % 2 == 0 else "DL"
            }
            for i in range(40)
        ]
        res_eval = detector.evaluate_drift(sufficient_sample)
        self.assertEqual(res_eval["status"], "EVALUATED")
        self.assertIn(res_eval["drift_level"], ["NORMAL", "WARNING", "CRITICAL"])
        self.assertIsNotNone(res_eval["metrics"]["cancellation_probability_psi"])
        self.assertIsNotNone(res_eval["metrics"]["delay_120m_probability_psi"])
        self.assertIsNotNone(res_eval["metrics"]["duration_ks_statistic"])

    def test_data_quality_and_imputation_tracking(self):
        tracker = DataQualityTracker()

        # Sample with 25% missing duration
        sample = []
        for i in range(20):
            sample.append({
                "status": "PREDICTION_AVAILABLE",
                "is_duration_imputed": 1 if i < 5 else 0, # 5/20 = 25% duration missing
                "is_distance_imputed": 0,
                "distance_miles": 850.0,
                "scheduled_elapsed_minutes": None if i < 5 else 145.0
            })

        quality = tracker.evaluate_quality(sample)
        self.assertEqual(quality["metrics"]["missing_duration_count"], 5)
        self.assertEqual(quality["metrics"]["missing_duration_percentage"], 25.0)
        self.assertEqual(quality["status"], "WARNING") # overall imputation 12.5% >= 10%

    def test_model_performance_evaluation(self):
        evaluator = PerformanceEvaluator()

        matched = [
            {
                "prediction_id": f"p-{i}",
                "carrier_code": "UA" if i % 2 == 0 else "AA",
                "cancellation_probability": 0.02 if i < 90 else 0.85,
                "delay_120m_probability": 0.04 if i < 85 else 0.90,
                "expected_delay_minutes": 10.0 if i < 85 else 140.0,
                "actual_cancelled": 0 if i < 90 else 1,
                "actual_delayed_120m": 0 if i < 85 else 1,
                "actual_arr_delay": 5.0 if i < 85 else 135.0,
                "provenance": "BTS_ON_TIME_DATABASE"
            }
            for i in range(100)
        ]

        perf = evaluator.evaluate_performance(matched)
        self.assertEqual(perf["status"], "EVALUATED")
        self.assertEqual(perf["evaluated_flights_count"], 100)

        # Brier scores & continuous delay MAE
        self.assertGreater(perf["cancellation_classifier"]["brier_score"], 0.0)
        self.assertGreater(perf["delay120_classifier"]["brier_score"], 0.0)
        self.assertGreater(perf["continuous_delay_regressor"]["mae_minutes"], 0.0)
        self.assertIn("UA", perf["carrier_performance"])
        self.assertIn("AA", perf["carrier_performance"])

    def test_monitoring_api_auth(self):
        # 1. Unauthorized request (Missing key header) -> 401
        res_no_auth = self.client.get("/monitoring/health")
        self.assertEqual(res_no_auth.status_code, 401)

        # 2. Unauthorized request (Invalid key) -> 401
        res_bad_auth = self.client.get("/monitoring/health", headers={"X-Flyo-Monitoring-Key": "wrong-key"})
        self.assertEqual(res_bad_auth.status_code, 401)

        # 3. Authorized request -> 200
        res_auth = self.client.get("/monitoring/health", headers=self.auth_headers)
        self.assertEqual(res_auth.status_code, 200)
        data = res_auth.json()
        self.assertEqual(data["status"], "HEALTHY")
        self.assertEqual(data["service"], "Flyo ML Observability Layer")

    def test_monitoring_endpoints_roundtrip(self):
        # /monitoring/predictions/summary
        res_sum = self.client.get("/monitoring/predictions/summary", headers=self.auth_headers)
        self.assertEqual(res_sum.status_code, 200)

        # /monitoring/data-quality
        res_dq = self.client.get("/monitoring/data-quality", headers=self.auth_headers)
        self.assertEqual(res_dq.status_code, 200)

        # /monitoring/drift
        res_dr = self.client.get("/monitoring/drift", headers=self.auth_headers)
        self.assertEqual(res_dr.status_code, 200)

        # /monitoring/model-performance
        res_mp = self.client.get("/monitoring/model-performance", headers=self.auth_headers)
        self.assertEqual(res_mp.status_code, 200)

        # /monitoring/alerts
        res_al = self.client.get("/monitoring/alerts", headers=self.auth_headers)
        self.assertEqual(res_al.status_code, 200)

        # /monitoring/governance
        res_gov = self.client.get("/monitoring/governance", headers=self.auth_headers)
        self.assertEqual(res_gov.status_code, 200)
        gov_data = res_gov.json()
        self.assertEqual(gov_data["active_version"], "v2.0.0-multiseason")
        self.assertTrue(gov_data["rollback_readiness"]["rollback_possible"])

if __name__ == "__main__":
    unittest.main()
