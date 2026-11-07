import unittest
from starlette.testclient import TestClient

from ml.service.main import app
from ml.service.registry import ModelRegistry
from ml.service.schemas import PredictionStatus

class TestFastAPIService(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        # Explicitly initialize registry artifacts for tests
        ModelRegistry.get_instance().load_artifacts()
        cls.client = TestClient(app)

    def test_health_live(self):
        response = self.client.get("/health/live")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "alive")
        self.assertEqual(data["service"], "flyo-ml-inference")

    def test_health_ready(self):
        response = self.client.get("/health/ready")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "HEALTHY")
        self.assertTrue(data["models_loaded"]["preprocessor"])
        self.assertTrue(data["models_loaded"]["cancellation_model"])
        self.assertTrue(data["models_loaded"]["delay120_model"])
        self.assertTrue(data["models_loaded"]["delay_regressor"])

    def test_models_metadata(self):
        response = self.client.get("/models/metadata")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("v2.0.0", data["model_version"])
        self.assertIn("AA", data["supported_carriers"])
        self.assertIn("WN", data["supported_carriers"])
        self.assertEqual(data["feature_count"], 79)

    def test_predict_valid_us_flight(self):
        payload = {
            "flight_number": "AA100",
            "carrier_code": "AA",
            "origin_airport": "ORD",
            "dest_airport": "DFW",
            "scheduled_dep_time": "14:30",
            "scheduled_arr_time": "17:15",
            "departure_date": "2024-07-15",
            "distance_miles": 802.0,
            "scheduled_elapsed_minutes": 165.0
        }
        response = self.client.post("/predict/flight", json=payload)
        self.assertEqual(response.status_code, 200)
        data = response.json()

        self.assertEqual(data["flight_number"], "AA100")
        self.assertEqual(data["status"], PredictionStatus.PREDICTION_AVAILABLE.value)
        self.assertIsNotNone(data["cancellation_probability"])
        self.assertGreaterEqual(data["cancellation_probability"], 0.0)
        self.assertLessEqual(data["cancellation_probability"], 1.0)

        self.assertIsNotNone(data["delay_120m_probability"])
        self.assertGreaterEqual(data["delay_120m_probability"], 0.0)
        self.assertLessEqual(data["delay_120m_probability"], 1.0)

        self.assertIsNotNone(data["expected_delay_minutes"])
        self.assertGreaterEqual(data["expected_delay_minutes"], 0.0)
        self.assertTrue(data["is_experimental"])
        self.assertIn("Extreme delay >=120m", data["disclaimer"])
        self.assertGreater(data["inference_latency_ms"], 0.0)

    def test_geofencing_indian_airspace_rejected(self):
        payload = {
            "flight_number": "6E501",
            "carrier_code": "6E",
            "origin_airport": "DEL",
            "dest_airport": "BOM",
            "scheduled_dep_time": "08:00",
            "scheduled_arr_time": "10:15",
            "departure_date": "2024-07-15",
        }
        response = self.client.post("/predict/flight", json=payload)
        self.assertEqual(response.status_code, 200)
        data = response.json()

        self.assertEqual(data["status"], PredictionStatus.UNSUPPORTED_AIRSPACE.value)
        self.assertIsNone(data["cancellation_probability"])
        self.assertIsNone(data["delay_120m_probability"])
        self.assertIsNone(data["expected_delay_minutes"])
        self.assertIn("Indian airspace", data["status_reason"])
        self.assertTrue(any("UNSUPPORTED_AIRSPACE" in factor for factor in data["risk_factors"]))

    def test_unsupported_carrier_insufficient_coverage(self):
        # US domestic route (ORD -> DFW) but operated by unsupported carrier (SY - Sun Country)
        payload = {
            "flight_number": "SY350",
            "carrier_code": "SY",
            "origin_airport": "ORD",
            "dest_airport": "DFW",
            "scheduled_dep_time": "18:00",
            "scheduled_arr_time": "20:30",
            "departure_date": "2024-07-15",
        }
        response = self.client.post("/predict/flight", json=payload)
        self.assertEqual(response.status_code, 200)
        data = response.json()

        self.assertEqual(data["status"], PredictionStatus.INSUFFICIENT_COVERAGE.value)
        self.assertIsNone(data["cancellation_probability"])
        self.assertIsNone(data["delay_120m_probability"])
        self.assertIn("Carrier 'SY' is not among the 15 US reporting carriers", data["status_reason"])

    def test_us_to_international_route_rejected(self):
        # US carrier (AA), US origin (JFK), but International destination (LHR - London Heathrow)
        payload = {
            "flight_number": "AA100",
            "carrier_code": "AA",
            "origin_airport": "JFK",
            "dest_airport": "LHR",
            "scheduled_dep_time": "19:00",
            "departure_date": "2024-07-15",
        }
        response = self.client.post("/predict/flight", json=payload)
        self.assertEqual(response.status_code, 200)
        data = response.json()

        self.assertEqual(data["status"], PredictionStatus.UNSUPPORTED_AIRSPACE.value)
        self.assertIsNone(data["cancellation_probability"])
        self.assertIn("LHR", data["status_reason"])
        self.assertTrue(any("UNSUPPORTED_AIRSPACE" in f for f in data["risk_factors"]))

    def test_indian_domestic_and_international_routes(self):
        # Indian domestic: HYD -> BLR
        payload_ind_dom = {
            "flight_number": "6E245",
            "carrier_code": "6E",
            "origin_airport": "HYD",
            "dest_airport": "BLR",
            "scheduled_dep_time": "10:00",
            "departure_date": "2024-07-15"
        }
        resp = self.client.post("/predict/flight", json=payload_ind_dom)
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["status"], PredictionStatus.UNSUPPORTED_AIRSPACE.value)

        # Indian international: DEL -> DXB
        payload_ind_intl = {
            "flight_number": "AI995",
            "carrier_code": "AI",
            "origin_airport": "DEL",
            "dest_airport": "DXB",
            "scheduled_dep_time": "20:00",
            "departure_date": "2024-07-15"
        }
        resp_intl = self.client.post("/predict/flight", json=payload_ind_intl)
        self.assertEqual(resp_intl.status_code, 200)
        self.assertEqual(resp_intl.json()["status"], PredictionStatus.UNSUPPORTED_AIRSPACE.value)

    def test_invalid_input_validation_error(self):
        # Missing required scheduled_dep_time
        payload = {
            "flight_number": "UA200",
            "carrier_code": "UA",
            "origin_airport": "SFO",
            "dest_airport": "EWR"
        }
        response = self.client.post("/predict/flight", json=payload)
        self.assertEqual(response.status_code, 422)

    def test_batch_prediction(self):
        payload = {
            "flights": [
                {
                    "flight_number": "DL101",
                    "carrier_code": "DL",
                    "origin_airport": "ATL",
                    "dest_airport": "LAX",
                    "scheduled_dep_time": "09:00",
                    "departure_date": "2024-07-15"
                },
                {
                    "flight_number": "AI101",
                    "carrier_code": "AI",
                    "origin_airport": "DEL",
                    "dest_airport": "HYD",
                    "scheduled_dep_time": "11:00",
                    "departure_date": "2024-07-15"
                }
            ]
        }
        response = self.client.post("/predict/batch", json=payload)
        self.assertEqual(response.status_code, 200)
        data = response.json()

        self.assertEqual(data["total_flights"], 2)
        self.assertEqual(data["successful_predictions"], 1)
        self.assertEqual(data["results"][0]["status"], PredictionStatus.PREDICTION_AVAILABLE.value)
        self.assertEqual(data["results"][1]["status"], PredictionStatus.UNSUPPORTED_AIRSPACE.value)

if __name__ == "__main__":
    unittest.main()
