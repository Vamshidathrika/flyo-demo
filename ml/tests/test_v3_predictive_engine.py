"""
Unit and integration tests for the Stage 8 v3 Aviation Predictive Intelligence Engine.

Validates:
1. Indian Priority Corridors disruption classification.
2. Quantile delay intervals and multi-horizon forecasting.
3. Airport-specific MCT rules and connection risk estimation.
4. Passenger disruption impact estimation and seat capacity sizing.
5. Weather data fusion, hazard detection, and staleness penalties.
6. TreeSHAP feature attributions and directional contributions.
7. Leaderboard and regional airport disruption heatmap endpoints.
"""

import datetime
import json
from pathlib import Path
import unittest
from starlette.testclient import TestClient

from ml.service.main import app
from ml.service.registry import ModelRegistry
from ml.service.connection_risk import ConnectionRiskEngine
from ml.service.passenger_impact import PassengerImpactEngine
from ml.service.weather_fusion import WeatherFusionEngine
from ml.service.explainability import ShapExplainabilityService
import pandas as pd

class TestV3PredictiveIntelligenceEngine(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        ModelRegistry.get_instance().load_artifacts()
        cls.client = TestClient(app)

    def test_indian_priority_corridor_del_bom(self):
        """Validates 6E-501 (DEL -> BOM) returns PREDICTION_AVAILABLE with calibrated probability."""
        payload = {
            "flight_number": "6E-501",
            "carrier_code": "6E",
            "origin_airport": "DEL",
            "dest_airport": "BOM",
            "scheduled_dep_time": "07:15",
            "departure_date": "2024-07-15",
            "visibility_km": 0.8,
            "is_thunderstorm": 0
        }
        resp = self.client.post("/predict/flight", json=payload)
        self.assertEqual(resp.status_code, 200)
        data = resp.json()

        self.assertEqual(data["status"], "PREDICTION_AVAILABLE")
        self.assertEqual(data["flight_number"], "6E-501")
        self.assertIsNotNone(data["disruption_probability"])
        self.assertGreaterEqual(data["disruption_probability"], 0.0)
        self.assertLessEqual(data["disruption_probability"], 1.0)
        self.assertIsNotNone(data["expected_delay_minutes"])
        self.assertIsNotNone(data["delay_p10_minutes"])
        self.assertIsNotNone(data["delay_p90_minutes"])
        self.assertLessEqual(data["delay_p10_minutes"], data["delay_p90_minutes"])

        # Check SHAP factor presence
        self.assertIn("top_contributing_factors", data)
        self.assertGreater(len(data["top_contributing_factors"]), 0)
        top_factor = data["top_contributing_factors"][0]
        self.assertIn("display_name", top_factor)
        self.assertIn("shap_value", top_factor)

    def test_indian_priority_corridors_hyd_and_blr(self):
        """Validates AI-839 (HYD -> DEL) and UK-927 (BOM -> BLR)."""
        corridors = [
            ("AI-839", "AI", "HYD", "DEL", "18:30"),
            ("UK-927", "UK", "BOM", "BLR", "19:45"),
            ("6E-204", "6E", "BLR", "MAA", "08:00")
        ]
        for fnum, carrier, orig, dest, dep in corridors:
            payload = {
                "flight_number": fnum,
                "carrier_code": carrier,
                "origin_airport": orig,
                "dest_airport": dest,
                "scheduled_dep_time": dep
            }
            resp = self.client.post("/predict/flight", json=payload)
            self.assertEqual(resp.status_code, 200)
            data = resp.json()
            self.assertEqual(data["status"], "PREDICTION_AVAILABLE")
            self.assertIn(data["disruption_risk_level"], ["LOW", "MODERATE", "ELEVATED", "CRITICAL"])

    def test_multi_horizon_delay_quantile_bounds(self):
        """Validates delay quantile ordering (P10 <= P50 <= P90) across horizons."""
        for horizon in ["T_MINUS_24H", "T_MINUS_6H", "T_MINUS_2H", "T_MINUS_1H"]:
            payload = {
                "flight_number": "6E-501",
                "carrier_code": "6E",
                "origin_airport": "DEL",
                "dest_airport": "BOM",
                "scheduled_dep_time": "07:15",
                "operational_horizon": horizon
            }
            resp = self.client.post("/predict/delay-forecast", json=payload)
            self.assertEqual(resp.status_code, 200)
            data = resp.json()

            p10 = data["delay_p10_minutes"]
            p50 = data["expected_delay_minutes"]
            p90 = data["delay_p90_minutes"]

            self.assertLessEqual(p10, p50 + 1e-4, f"P10 ({p10}) must be <= P50 ({p50})")
            self.assertLessEqual(p50, p90 + 1e-4, f"P50 ({p50}) must be <= P90 ({p90})")
            self.assertEqual(data["operational_horizon"], horizon)
            self.assertIn("delay_risk_tier", data)

    def test_airport_mct_connection_rules(self):
        """Validates airport-specific MCT standards for DEL, BOM, BLR."""
        evaluator = ConnectionRiskEngine()

        # DEL: Intra-terminal 45m MCT (no checked bag)
        del_intra = evaluator.evaluate_connection(
            airport_code="DEL",
            scheduled_connection_minutes=55,
            predicted_inbound_delay_minutes=0.0,
            inbound_terminal="T3",
            outbound_terminal="T3",
            has_checked_baggage=False
        )
        self.assertEqual(del_intra["effective_mct_minutes"], 45)
        self.assertFalse(del_intra["is_inter_terminal_transfer"])
        self.assertEqual(del_intra["connection_margin_minutes"], 10.0)
        self.assertEqual(del_intra["recommended_action"], "MONITOR_INBOUND_DESCENT")

        # BOM: Inter-terminal (T1 -> T2) 90m MCT with delay -> negative margin
        bom_inter = evaluator.evaluate_connection(
            airport_code="BOM",
            scheduled_connection_minutes=85,
            predicted_inbound_delay_minutes=25.0,
            inbound_terminal="T1",
            outbound_terminal="T2"
        )
        self.assertEqual(bom_inter["effective_mct_minutes"], 100) # 90m + 10m bag buffer
        self.assertTrue(bom_inter["is_inter_terminal_transfer"])
        self.assertLess(bom_inter["connection_margin_minutes"], 0)
        self.assertGreater(bom_inter["connection_failure_probability"], 0.70)
        self.assertEqual(bom_inter["recommended_action"], "PROACTIVE_REBOOKING_HOLD")

    def test_passenger_impact_sizing(self):
        """Validates passenger impact volume and rebooking demand estimation."""
        estimator = PassengerImpactEngine()

        # A320 with elevated disruption
        impact = estimator.estimate_flight_impact(
            flight_number="6E-501",
            carrier_code="6E",
            origin_airport="DEL",
            dest_airport="BOM",
            disruption_probability=0.45,
            expected_delay_minutes=75.0,
            aircraft_type="A320",
            load_factor=0.88
        )
        self.assertEqual(impact["seat_capacity"], 180)
        self.assertEqual(impact["estimated_passengers_total"], int(round(180 * 0.88)))
        self.assertGreater(impact["vulnerable_connections_count"], 0)
        self.assertGreater(impact["projected_rebooking_demand"], 0)
        self.assertGreater(impact["estimated_rescue_workload_hours"], 0.0)

        # Widebody B777 cancellation
        impact_widebody = estimator.estimate_flight_impact(
            flight_number="AI-101",
            carrier_code="AI",
            origin_airport="BOM",
            dest_airport="JFK",
            disruption_probability=0.95,
            expected_delay_minutes=180.0,
            is_cancelled=True,
            aircraft_type="B777",
            load_factor=0.90
        )
        self.assertEqual(impact_widebody["seat_capacity"], 368)
        self.assertEqual(impact_widebody["impact_severity"], "CRITICAL")
        self.assertEqual(impact_widebody["cost_exposure_tier"], "CRITICAL_EXPOSURE")

    def test_weather_fusion_hazard_detection(self):
        """Validates weather hazard tags: CAT-III fog and convective storm."""
        service = WeatherFusionEngine()

        # CAT-III fog condition in Delhi (visibility 400m)
        fused_fog = service.fuse_weather_data(
            airport_code="DEL",
            observation_timestamp_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
            visibility_km=0.4,
            temperature_c=8.0,
            wind_speed_kmh=12.0
        )
        hazards = " ".join(fused_fog["aviation_hazards"])
        self.assertIn("CAT_III_LOW_VISIBILITY", hazards)

        # Severe convective storm (thunderstorm code 95)
        fused_storm = service.fuse_weather_data(
            airport_code="BOM",
            observation_timestamp_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
            precipitation_mm=25.0,
            wind_speed_kmh=55.0,
            weather_code=95
        )
        hazards_storm = " ".join(fused_storm["aviation_hazards"])
        self.assertIn("SEVERE_CONVECTIVE_STORM", hazards_storm)

    def test_weather_staleness_penalty(self):
        """Validates that observations older than 180 minutes receive staleness penalties."""
        service = WeatherFusionEngine()
        old_time = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(minutes=240)).isoformat()
        fused = service.fuse_weather_data(
            airport_code="DEL",
            observation_timestamp_utc=old_time,
            temperature_c=15.0,
            wind_speed_kmh=10.0
        )
        self.assertGreater(fused["data_age_minutes"], 180.0)
        self.assertTrue(fused["is_stale"])
        hazards = " ".join(fused["aviation_hazards"])
        self.assertIn("STALE_WEATHER_WARNING", hazards)
        self.assertLess(fused["weather_confidence_score"], 0.80)

    def test_shap_explainability_attributions(self):
        """Validates TreeSHAP decomposes predictions into ordered features."""
        explainer = ShapExplainabilityService()
        if explainer.explainer is not None:
            schema_path = Path("ml/artifacts/feature_schema_v3.json")
            with open(schema_path) as f:
                schema = json.load(f)
            feature_cols = schema["feature_names"]
            features = {col: 0.0 for col in feature_cols}
            features["delhi_winter_fog_risk"] = 1.0
            features["scheduled_dep_hour"] = 7.0
            df = pd.DataFrame([features])
            result = explainer.explain_prediction(df, top_k=5)
            self.assertIn("top_contributing_factors", result)
            self.assertGreater(len(result["top_contributing_factors"]), 0)
            for factor in result["top_contributing_factors"]:
                self.assertIn(factor["impact_direction"], ["INCREASES_RISK", "DECREASES_RISK"])
                self.assertGreater(factor["absolute_magnitude"], 0.0)
                self.assertIsInstance(factor["display_name"], str)

    def test_monitoring_leaderboard_endpoint(self):
        """Validates /monitoring/leaderboard returns prioritized operational flights."""
        resp = self.client.get("/monitoring/leaderboard")
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertIn("leaderboard", data)
        self.assertGreater(len(data["leaderboard"]), 0)

        # Flights must be sorted descending by disruption probability
        probs = [item["disruption_probability"] for item in data["leaderboard"] if item["disruption_probability"] is not None]
        self.assertEqual(probs, sorted(probs, reverse=True))

    def test_monitoring_heatmap_endpoint(self):
        """Validates /monitoring/heatmap returns regional airport cards."""
        resp = self.client.get("/monitoring/heatmap")
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertIn("airports", data)
        hub_codes = [h["code"] for h in data["airports"]]
        # Must cover key Indian hubs
        for expected_hub in ["DEL", "BOM", "BLR", "HYD", "MAA"]:
            self.assertIn(expected_hub, hub_codes)

if __name__ == "__main__":
    unittest.main()
