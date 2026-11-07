import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional, Tuple

import requests

from .config import MAJOR_US_AIRPORTS, WEATHER_DATA_DIR

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("WeatherMatcher")

class WeatherMatcher:

    def __init__(self, weather_dir: Path = WEATHER_DATA_DIR):
        self.weather_dir = weather_dir
        self.weather_cache: Dict[str, Dict] = {}

    def _get_cache_key(self, airport: str, year: int, month: int) -> str:
        return f"{airport.upper()}_{year}_{month:02d}"

    def fetch_monthly_airport_weather(self, airport: str, year: int, month: int) -> Optional[Dict]:
        airport = airport.upper()
        if airport not in MAJOR_US_AIRPORTS:
            return None

        cache_key = self._get_cache_key(airport, year, month)
        if cache_key in self.weather_cache:
            return self.weather_cache[cache_key]

        cached_file = self.weather_dir / f"{cache_key}.json"
        if cached_file.exists():
            try:
                with open(cached_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.weather_cache[cache_key] = data
                    return data
            except Exception as e:
                logger.warning(f"Failed to read cached weather for {cache_key}: {e}")

        # Fetch from Open-Meteo Historical Archive API
        coord = MAJOR_US_AIRPORTS[airport]
        # Calculate month date range
        start_date = f"{year}-{month:02d}-01"
        import calendar
        last_day = calendar.monthrange(year, month)[1]
        end_date = f"{year}-{month:02d}-{last_day:02d}"

        url = (
            f"https://archive-api.open-meteo.com/v1/archive?"
            f"latitude={coord['lat']}&longitude={coord['lon']}&"
            f"start_date={start_date}&end_date={end_date}&"
            f"hourly=temperature_2m,relative_humidity_2m,precipitation,weather_code,wind_speed_10m"
        )

        logger.info(f"[Open-Meteo Archive] Fetching historical weather for {airport} ({start_date} to {end_date})")
        try:
            resp = requests.get(
                url,
                headers={"User-Agent": "FlyoPredictiveIntelligence/1.0 (Aviation Operational Research)"},
                timeout=15
            )
            if resp.status_code == 200:
                raw_json = resp.json()
                hourly = raw_json.get("hourly", {})
                times = hourly.get("time", [])

                # Index by timestamp string "YYYY-MM-DDTHH:00"
                indexed_weather = {}
                for idx, t in enumerate(times):
                    indexed_weather[t] = {
                        "temperature_c": hourly["temperature_2m"][idx] if idx < len(hourly.get("temperature_2m", [])) else None,
                        "weather_code": hourly["weather_code"][idx] if idx < len(hourly.get("weather_code", [])) else None,
                        "wind_speed_kmh": hourly["wind_speed_10m"][idx] if idx < len(hourly.get("wind_speed_10m", [])) else None,
                        "precipitation_mm": hourly["precipitation"][idx] if idx < len(hourly.get("precipitation", [])) else None
                    }

                # Save to disk cache
                with open(cached_file, "w", encoding="utf-8") as f:
                    json.dump(indexed_weather, f)

                self.weather_cache[cache_key] = indexed_weather
                logger.info(f"[Open-Meteo Archive] Cached {len(indexed_weather)} hourly weather slots for {airport}")
                return indexed_weather
            else:
                logger.warn(f"[Open-Meteo Archive] HTTP {resp.status_code} for {airport}: {resp.text[:100]}")
        except Exception as e:
            logger.error(f"[Open-Meteo Archive] Error fetching weather for {airport}: {e}")

        return None

    def match_scheduled_weather(self, airport: str, flight_date: str, scheduled_time: str) -> Dict[str, Optional[float]]:
        """
        Leakage-free lookup: Matches weather at the scheduled hour.
        Returns weather values or None if unavailable.
        """
        result = {
            "temperature_c": None,
            "weather_code": None,
            "wind_speed_kmh": None,
            "precipitation_mm": None,
            "is_weather_available": 0
        }

        if not airport or not flight_date or not scheduled_time:
            return result

        try:
            dt = datetime.strptime(flight_date, "%Y-%m-%d")
            hour = int(scheduled_time.split(":")[0])
            weather_data = self.fetch_monthly_airport_weather(airport, dt.year, dt.month)

            if weather_data:
                target_iso = f"{flight_date}T{hour:02d}:00"
                if target_iso in weather_data:
                    w = weather_data[target_iso]
                    result["temperature_c"] = w["temperature_c"]
                    result["weather_code"] = w["weather_code"]
                    result["wind_speed_kmh"] = w["wind_speed_kmh"]
                    result["precipitation_mm"] = w["precipitation_mm"]
                    result["is_weather_available"] = 1
        except Exception as e:
            logger.debug(f"Error matching weather for {airport} {flight_date} {scheduled_time}: {e}")

        return result
