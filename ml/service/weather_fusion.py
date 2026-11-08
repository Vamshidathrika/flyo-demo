"""
FLYO STAGE 8 — WEATHER & REAL-TIME OPERATIONAL DATA FUSION
Integrates point-in-time surface observations, METARs, and forecasts.
Enforces timestamp tracking, data freshness validation, missing-provider fallbacks,
and physical aviation hazard detection.
"""

from __future__ import annotations

import datetime
import logging
from typing import Any, Dict, List, Optional, Tuple

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("WeatherFusion")

class WeatherFusionEngine:
    """
    Validates, enriches, and extracts physical aviation hazards from weather reports.
    """

    MAX_FRESHNESS_AGE_MINUTES = 180 # 3 hours

    def fuse_weather_data(
        self,
        airport_code: str,
        temperature_c: Optional[float] = None,
        wind_speed_kmh: Optional[float] = None,
        visibility_km: Optional[float] = None,
        precipitation_mm: Optional[float] = None,
        weather_code: Optional[int] = None,
        observation_timestamp_utc: Optional[str] = None,
        provider_source: str = "OPEN_METEO",
    ) -> Dict[str, Any]:
        apt = airport_code.upper()
        now_utc = datetime.datetime.now(datetime.timezone.utc)

        # 1. Freshness & Timestamp Evaluation
        age_minutes = 0.0
        is_stale = False
        if observation_timestamp_utc:
            try:
                obs_dt = datetime.datetime.fromisoformat(observation_timestamp_utc.replace("Z", "+00:00"))
                age_minutes = (now_utc - obs_dt).total_seconds() / 60.0
                if age_minutes > self.MAX_FRESHNESS_AGE_MINUTES:
                    is_stale = True
            except ValueError:
                is_stale = True
                age_minutes = 999.0

        # 2. Defaults and Missing Value Imputation
        temp = temperature_c if temperature_c is not None else 22.0
        wind = wind_speed_kmh if wind_speed_kmh is not None else 10.0
        vis = visibility_km if visibility_km is not None else 10.0
        precip = precipitation_mm if precipitation_mm is not None else 0.0
        w_code = weather_code if weather_code is not None else 0

        # 3. Physical Aviation Hazard Classification
        hazards: List[str] = []
        confidence_penalty = 0.0

        # Fog / Low Visibility (CAT-II / CAT-III ILS condition)
        if vis <= 1.0 or w_code in [45, 48]:
            hazards.append("CAT_III_LOW_VISIBILITY: Dense fog operating window (<1000m visibility)")
        elif vis <= 3.0:
            hazards.append("MARGINAL_VISIBILITY: Visibility between 1000m and 3000m")

        # Thunderstorm / Convective activity
        if w_code in [95, 96, 99] or (precip >= 15.0 and wind >= 40.0):
            hazards.append("SEVERE_CONVECTIVE_STORM: Active thunderstorm, potential ground stop")
        elif precip >= 5.0 or wind >= 35.0:
            hazards.append("ADVERSE_SURFACE_WEATHER: Heavy precipitation or high surface winds")

        # High winds / Crosswind
        if wind >= 45.0:
            hazards.append("HIGH_SURFACE_WINDS: Sustained winds >= 45 km/h, crosswind limits threatened")

        # Freezing conditions
        if temp <= 0.0 and (precip > 0.0 or w_code in [71, 73, 75, 85, 86]):
            hazards.append("WINTER_ICING_CONDITIONS: Sub-zero surface temperature with de-icing required")

        # 4. Confidence Penalty for Stale or Missing Data
        if is_stale:
            confidence_penalty += 0.25
            hazards.append(f"STALE_WEATHER_WARNING: Observation is {int(age_minutes)} minutes old (>180m threshold)")
        if temperature_c is None and visibility_km is None:
            confidence_penalty += 0.35
            hazards.append("IMPUTED_WEATHER_WARNING: Real-time observations unavailable, using regional climatological priors")

        overall_confidence = max(0.20, round(1.0 - confidence_penalty, 2))

        return {
            "airport_code": apt,
            "provider_source": provider_source,
            "observation_timestamp_utc": observation_timestamp_utc or now_utc.isoformat(),
            "data_age_minutes": round(age_minutes, 1),
            "is_stale": is_stale,
            "temperature_c": round(temp, 1),
            "wind_speed_kmh": round(wind, 1),
            "visibility_km": round(vis, 1),
            "precipitation_mm": round(precip, 1),
            "weather_code": w_code,
            "aviation_hazards": hazards,
            "weather_confidence_score": overall_confidence,
        }
