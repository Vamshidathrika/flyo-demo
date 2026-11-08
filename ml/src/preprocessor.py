import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import joblib
import numpy as np
import pandas as pd

from .config import MAJOR_US_AIRPORTS, PROCESSED_DATA_DIR, WEATHER_DATA_DIR

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("Preprocessor")

# WMO Weather Codes indicating adverse weather (precipitation, snow, thunderstorm)
ADVERSE_WEATHER_CODES = {
    51, 53, 55, 56, 57, # Drizzle / Freezing drizzle
    61, 63, 65, 66, 67, # Rain / Freezing rain
    71, 73, 75, 77,     # Snow / Snow grains
    80, 81, 82,         # Rain showers
    85, 86,             # Snow showers
    95, 96, 99          # Thunderstorms / Severe hail
}

TOP_CARRIERS = ["AA", "DL", "UA", "WN", "B6", "AS", "NK", "F9"]
TOP_AIRPORTS = ["ATL", "ORD", "DFW", "DEN", "LAX", "JFK", "SFO", "SEA", "MIA", "BOS", "EWR", "CLT", "PHX", "IAH", "LGA"]

class FlightDataPreprocessor:
    def __init__(self, weather_dir: Path = WEATHER_DATA_DIR):
        self.weather_dir = weather_dir
        self.weather_cache: Dict[str, Dict] = {}
        self.training_priors: Dict = {}
        self.feature_columns: List[str] = []
        self.imputation_medians: Dict[str, float] = {}

    def load_weather_cache(self, year: int = 2024, month: int = 1):
        for apt in MAJOR_US_AIRPORTS:
            p = self.weather_dir / f"{apt}_{year}_{month:02d}.json"
            if p.exists():
                try:
                    with open(p, "r", encoding="utf-8") as f:
                        self.weather_cache[apt] = json.load(f)
                except Exception as e:
                    logger.warning(f"Could not load weather cache for {apt}: {e}")
        logger.info(f"Loaded {len(self.weather_cache)} airport weather files into preprocessor cache.")

    def _lookup_weather(self, apt: str, f_date: str, hhmm: str) -> Dict[str, Optional[float]]:
        res = {
            "temperature_c": None,
            "weather_code": None,
            "wind_speed_kmh": None,
            "is_adverse": 0,
            "is_freezing": 0,
            "is_high_wind": 0,
            "has_weather": 0
        }
        if not apt or not f_date or not hhmm or apt not in self.weather_cache:
            return res

        try:
            hour = int(hhmm.split(":")[0])
            key = f"{f_date}T{hour:02d}:00"
            w = self.weather_cache[apt].get(key)
            if w:
                temp = w.get("temperature_c")
                code = w.get("weather_code")
                wind = w.get("wind_speed_kmh")
                res["temperature_c"] = temp
                res["weather_code"] = code
                res["wind_speed_kmh"] = wind
                res["has_weather"] = 1
                if temp is not None and temp <= 0.0:
                    res["is_freezing"] = 1
                if wind is not None and wind >= 30.0:
                    res["high_wind"] = 1
                if code is not None and code in ADVERSE_WEATHER_CODES:
                    res["is_adverse"] = 1
        except Exception:
            pass
        return res

    def enrich_with_weather(self, df: pd.DataFrame) -> pd.DataFrame:
        df = df.copy()
        if not self.weather_cache:
            self.load_weather_cache()

        origin_weather = [
            self._lookup_weather(row[0], row[1], row[2])
            for row in zip(df["origin_airport"], df["flight_date"], df["scheduled_dep_time"])
        ]
        dest_weather = [
            self._lookup_weather(row[0], row[1], row[2])
            for row in zip(df["dest_airport"], df["flight_date"], df["scheduled_arr_time"])
        ]

        df["origin_temp_c"] = [w["temperature_c"] for w in origin_weather]
        df["origin_weather_code"] = [w["weather_code"] for w in origin_weather]
        df["origin_wind_speed_kmh"] = [w["wind_speed_kmh"] for w in origin_weather]
        df["origin_is_freezing"] = [w["is_freezing"] for w in origin_weather]
        df["origin_is_high_wind"] = [w["is_high_wind"] for w in origin_weather]
        df["origin_is_adverse"] = [w["is_adverse"] for w in origin_weather]
        df["has_origin_weather"] = [w["has_weather"] for w in origin_weather]

        df["dest_temp_c"] = [w["temperature_c"] for w in dest_weather]
        df["dest_weather_code"] = [w["weather_code"] for w in dest_weather]
        df["dest_wind_speed_kmh"] = [w["wind_speed_kmh"] for w in dest_weather]
        df["dest_is_freezing"] = [w["is_freezing"] for w in dest_weather]
        df["dest_is_high_wind"] = [w["is_high_wind"] for w in dest_weather]
        df["dest_is_adverse"] = [w["is_adverse"] for w in dest_weather]
        df["has_dest_weather"] = [w["has_weather"] for w in dest_weather]

        return df

    def fit_training_priors(self, train_df: pd.DataFrame):
        """
        Calculates empirical priors STRICTLY on the training set (Days 1-20).
        This guarantees zero temporal leakage into validation or test sets.
        """
        logger.info("Computing empirical priors on training split...")
        global_cancel_rate = train_df["is_cancelled"].mean()
        global_delay120_rate = train_df["is_delayed_120m"].mean()
        completed = train_df[train_df["is_cancelled"] == 0]
        global_mean_delay = completed["arr_delay_minutes"].mean()

        # Carrier priors
        carrier_stats = train_df.groupby("carrier_code").agg(
            carrier_cancel_rate=("is_cancelled", "mean"),
            carrier_delay120_rate=("is_delayed_120m", "mean"),
            flight_count=("flight_id", "count")
        ).to_dict(orient="index")

        carrier_delays = completed.groupby("carrier_code")["arr_delay_minutes"].mean().to_dict()

        # Origin priors
        origin_stats = train_df.groupby("origin_airport").agg(
            origin_cancel_rate=("is_cancelled", "mean"),
            origin_delay120_rate=("is_delayed_120m", "mean")
        ).to_dict(orient="index")

        # Destination priors
        dest_stats = train_df.groupby("dest_airport").agg(
            dest_cancel_rate=("is_cancelled", "mean"),
            dest_delay120_rate=("is_delayed_120m", "mean")
        ).to_dict(orient="index")

        # Medians for numerical imputation
        self.imputation_medians = {
            "origin_temp_c": float(train_df["origin_temp_c"].dropna().median() if not train_df["origin_temp_c"].dropna().empty else 5.0),
            "origin_wind_speed_kmh": float(train_df["origin_wind_speed_kmh"].dropna().median() if not train_df["origin_wind_speed_kmh"].dropna().empty else 12.0),
            "dest_temp_c": float(train_df["dest_temp_c"].dropna().median() if not train_df["dest_temp_c"].dropna().empty else 5.0),
            "dest_wind_speed_kmh": float(train_df["dest_wind_speed_kmh"].dropna().median() if not train_df["dest_wind_speed_kmh"].dropna().empty else 12.0),
            "scheduled_elapsed_minutes": float(train_df["scheduled_elapsed_minutes"].median()),
            "distance_miles": float(train_df["distance_miles"].median())
        }

        self.training_priors = {
            "global_cancel_rate": float(global_cancel_rate),
            "global_delay120_rate": float(global_delay120_rate),
            "global_mean_delay": float(global_mean_delay),
            "carrier_stats": carrier_stats,
            "carrier_delays": carrier_delays,
            "origin_stats": origin_stats,
            "dest_stats": dest_stats
        }
        logger.info(f"Priors fit: global_cancel={global_cancel_rate:.4f}, global_delay120={global_delay120_rate:.4f}")

    def transform(self, df: pd.DataFrame) -> Tuple[pd.DataFrame, pd.Series, pd.Series, pd.Series]:
        """
        Transforms raw flight records into a leakage-free feature matrix X and targets y.
        All information used represents strictly what is known at T_predict = Departure - 2h.
        """
        df = df.copy()

        # Parse hours from scheduled strings
        dep_hour = df["scheduled_dep_time"].apply(lambda t: int(str(t).split(":")[0]) if str(t) and ":" in str(t) else 12)
        arr_hour = df["scheduled_arr_time"].apply(lambda t: int(str(t).split(":")[0]) if str(t) and ":" in str(t) else 14)

        X = pd.DataFrame(index=df.index)

        # 1. Temporal & Scheduling Features
        X["day_of_week"] = df["day_of_week"].astype(int)
        X["day_of_month"] = df["day_of_month"].astype(int)
        X["scheduled_dep_hour"] = dep_hour
        X["scheduled_arr_hour"] = arr_hour
        X["scheduled_elapsed_minutes"] = df["scheduled_elapsed_minutes"].fillna(self.imputation_medians.get("scheduled_elapsed_minutes", 120.0))
        X["distance_miles"] = df["distance_miles"].fillna(self.imputation_medians.get("distance_miles", 750.0))

        # Peak hours flags (Morning rush 6-9, Evening rush 16-19)
        X["is_dep_morning_peak"] = ((dep_hour >= 6) & (dep_hour <= 9)).astype(int)
        X["is_dep_evening_peak"] = ((dep_hour >= 16) & (dep_hour <= 19)).astype(int)
        X["is_dep_night"] = ((dep_hour >= 21) | (dep_hour <= 5)).astype(int)

        # 2. Weather Features & Imputations
        X["origin_temp_c"] = df["origin_temp_c"].fillna(self.imputation_medians.get("origin_temp_c", 5.0))
        X["origin_wind_speed_kmh"] = df["origin_wind_speed_kmh"].fillna(self.imputation_medians.get("origin_wind_speed_kmh", 12.0))
        X["origin_is_freezing"] = df["origin_is_freezing"].fillna(0).astype(int)
        X["origin_is_high_wind"] = df["origin_is_high_wind"].fillna(0).astype(int)
        X["origin_is_adverse"] = df["origin_is_adverse"].fillna(0).astype(int)
        X["has_origin_weather"] = df["has_origin_weather"].fillna(0).astype(int)

        X["dest_temp_c"] = df["dest_temp_c"].fillna(self.imputation_medians.get("dest_temp_c", 5.0))
        X["dest_wind_speed_kmh"] = df["dest_wind_speed_kmh"].fillna(self.imputation_medians.get("dest_wind_speed_kmh", 12.0))
        X["dest_is_freezing"] = df["dest_is_freezing"].fillna(0).astype(int)
        X["dest_is_high_wind"] = df["dest_is_high_wind"].fillna(0).astype(int)
        X["dest_is_adverse"] = df["dest_is_adverse"].fillna(0).astype(int)
        X["has_dest_weather"] = df["has_dest_weather"].fillna(0).astype(int)

        # 3. Empirical Training Priors (Leakage-free historical rates)
        global_c_rate = self.training_priors.get("global_cancel_rate", 0.025)
        global_d_rate = self.training_priors.get("global_delay120_rate", 0.048)
        global_m_delay = self.training_priors.get("global_mean_delay", 8.5)

        carrier_stats = self.training_priors.get("carrier_stats", {})
        carrier_delays = self.training_priors.get("carrier_delays", {})
        origin_stats = self.training_priors.get("origin_stats", {})
        dest_stats = self.training_priors.get("dest_stats", {})

        X["carrier_prior_cancel_rate"] = df["carrier_code"].apply(
            lambda c: carrier_stats[c]["carrier_cancel_rate"] if c in carrier_stats else global_c_rate
        )
        X["carrier_prior_delay120_rate"] = df["carrier_code"].apply(
            lambda c: carrier_stats[c]["carrier_delay120_rate"] if c in carrier_stats else global_d_rate
        )
        X["carrier_prior_mean_delay"] = df["carrier_code"].apply(
            lambda c: carrier_delays[c] if c in carrier_delays else global_m_delay
        )

        X["origin_prior_cancel_rate"] = df["origin_airport"].apply(
            lambda o: origin_stats[o]["origin_cancel_rate"] if o in origin_stats else global_c_rate
        )
        X["origin_prior_delay120_rate"] = df["origin_airport"].apply(
            lambda o: origin_stats[o]["origin_delay120_rate"] if o in origin_stats else global_d_rate
        )

        X["dest_prior_cancel_rate"] = df["dest_airport"].apply(
            lambda d: dest_stats[d]["dest_cancel_rate"] if d in dest_stats else global_c_rate
        )
        X["dest_prior_delay120_rate"] = df["dest_airport"].apply(
            lambda d: dest_stats[d]["dest_delay120_rate"] if d in dest_stats else global_d_rate
        )

        # 4. Categorical Encodings (One-Hot for Top Entities)
        for carrier in TOP_CARRIERS:
            X[f"carrier_{carrier}"] = (df["carrier_code"] == carrier).astype(int)
        X["carrier_other"] = (~df["carrier_code"].isin(TOP_CARRIERS)).astype(int)

        for apt in TOP_AIRPORTS:
            X[f"origin_{apt}"] = (df["origin_airport"] == apt).astype(int)
        X["origin_other"] = (~df["origin_airport"].isin(TOP_AIRPORTS)).astype(int)

        for apt in TOP_AIRPORTS:
            X[f"dest_{apt}"] = (df["dest_airport"] == apt).astype(int)
        X["dest_other"] = (~df["dest_airport"].isin(TOP_AIRPORTS)).astype(int)

        # Save feature list
        self.feature_columns = list(X.columns)

        # Targets
        y_cancel = df["is_cancelled"].astype(int)
        y_delay120 = df["is_delayed_120m"].astype(int)
        y_arr_delay = df["arr_delay_minutes"].fillna(0.0)

        return X, y_cancel, y_delay120, y_arr_delay

    def save(self, filepath: Path):
        data = {
            "imputation_medians": self.imputation_medians,
            "training_priors": self.training_priors,
            "feature_columns": self.feature_columns
        }
        joblib.dump(data, filepath)
        logger.info(f"Preprocessor saved to {filepath}")

    @classmethod
    def load(cls, filepath: Path) -> "FlightDataPreprocessor":
        instance = cls()
        data = joblib.load(filepath)
        instance.imputation_medians = data["imputation_medians"]
        instance.training_priors = data["training_priors"]
        instance.feature_columns = data["feature_columns"]
        return instance
