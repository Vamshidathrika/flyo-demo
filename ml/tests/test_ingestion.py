import unittest
from pathlib import Path
import sys

# Add ml parent dir to path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from ml.src.bts_parser import BTSParser
from ml.src.weather_matcher import WeatherMatcher

class TestBTSParser(unittest.TestCase):

    def setUp(self):
        self.parser = BTSParser()

    def test_hhmm_formatting(self):
        self.assertEqual(self.parser.format_hhmm("930"), "09:30")
        self.assertEqual(self.parser.format_hhmm("0930"), "09:30")
        self.assertEqual(self.parser.format_hhmm("2145"), "21:45")
        self.assertEqual(self.parser.format_hhmm("2400"), "00:00")
        self.assertEqual(self.parser.format_hhmm("5"), "00:05")
        self.assertIsNone(self.parser.format_hhmm(""))
        self.assertIsNone(self.parser.format_hhmm("9999"))

    def test_parse_float_and_int(self):
        self.assertEqual(self.parser.parse_float("15.5"), 15.5)
        self.assertEqual(self.parser.parse_float("-12.0"), -12.0)
        self.assertIsNone(self.parser.parse_float(""))
        self.assertIsNone(self.parser.parse_float("NA"))

        self.assertEqual(self.parser.parse_int("1"), 1)
        self.assertEqual(self.parser.parse_int("0.0"), 0)
        self.assertIsNone(self.parser.parse_int(""))

class TestWeatherMatcher(unittest.TestCase):

    def setUp(self):
        self.matcher = WeatherMatcher()

    def test_weather_matching_structure(self):
        # ATL is in major airport registry
        w = self.matcher.match_scheduled_weather("ATL", "2024-01-01", "10:00")
        self.assertIn("temperature_c", w)
        self.assertIn("weather_code", w)
        self.assertIn("is_weather_available", w)

    def test_unregistered_airport_graceful_handling(self):
        # UNK is not in registry
        w = self.matcher.match_scheduled_weather("UNK", "2024-01-01", "10:00")
        self.assertEqual(w["is_weather_available"], 0)
        self.assertIsNone(w["temperature_c"])

if __name__ == "__main__":
    unittest.main()
