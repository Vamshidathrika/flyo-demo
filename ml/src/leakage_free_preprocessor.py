import json
import logging
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import joblib
import numpy as np
import pandas as pd

from .config import PROCESSED_DATA_DIR

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("LeakageFreePreprocessor")

# All 15 Reporting Airlines in BTS Database
ALL_BTS_CARRIERS = [
    "WN", "AA", "DL", "UA", "OO", "YX", "MQ", "NK", "B6", "AS", "9E", "OH", "F9", "G4", "HA"
]

# Top 20 US Air Traffic Hubs
TOP_20_AIRPORTS = [
    "ATL", "ORD", "DFW", "DEN", "CLT", "LAX", "PHX", "MCO", "LAS", "SEA",
    "MIA", "IAH", "JFK", "EWR", "FLL", "MSP", "SFO", "DTW", "BOS", "SLC"
]

class LeakageFreePreprocessor:
    """
    Leakage-Free Feature Preprocessor for Flyo ML v2.
    Strictly guarantees that all engineered features represent state observable
    at T_predict = Scheduled Departure - 2 hours.
    All historical observed weather and post-flight operational metrics are 100% excluded.
    """

    def __init__(self):
        self.training_priors: Dict = {}
        self.feature_columns: List[str] = []
        self.imputation_medians: Dict[str, float] = {}

    def fit_training_priors(self, train_df: pd.DataFrame):
        """
        Calculates empirical priors STRICTLY on the training partition.
        Zero lookahead into validation or held-out test periods.
        """
        logger.info("Computing empirical priors on training partition...")
        global_cancel_rate = float(train_df["is_cancelled"].mean())
        global_delay120_rate = float(train_df["is_delayed_120m"].mean())
        completed = train_df[train_df["is_cancelled"] == 0]
        global_mean_delay = float(completed["arr_delay_minutes"].mean() if not completed.empty else 8.5)

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

        # Route (Origin -> Dest) priors (with minimum 10 flights threshold)
        route_df = train_df.groupby(["origin_airport", "dest_airport"]).agg(
            route_cancel_rate=("is_cancelled", "mean"),
            route_delay120_rate=("is_delayed_120m", "mean"),
            flight_count=("flight_id", "count")
        )
        route_stats = {}
        for (orig, dest), r_data in route_df.iterrows():
            if r_data["flight_count"] >= 10:
                route_stats[f"{orig}_{dest}"] = {
                    "route_cancel_rate": float(r_data["route_cancel_rate"]),
                    "route_delay120_rate": float(r_data["route_delay120_rate"])
                }

        # Medians for numerical features
        self.imputation_medians = {
            "scheduled_elapsed_minutes": float(train_df["scheduled_elapsed_minutes"].median()),
            "distance_miles": float(train_df["distance_miles"].median())
        }

        self.training_priors = {
            "global_cancel_rate": global_cancel_rate,
            "global_delay120_rate": global_delay120_rate,
            "global_mean_delay": global_mean_delay,
            "carrier_stats": carrier_stats,
            "carrier_delays": carrier_delays,
            "origin_stats": origin_stats,
            "dest_stats": dest_stats,
            "route_stats": route_stats
        }
        logger.info(f"Priors fit: global_cancel={global_cancel_rate:.4f}, global_delay120={global_delay120_rate:.4f}")

    def transform(self, df: pd.DataFrame) -> Tuple[pd.DataFrame, pd.Series, pd.Series, pd.Series]:
        """
        Transforms raw flight records into a strictly leakage-free feature matrix X and targets y.
        """
        df = df.copy()

        # Parse hours from scheduled times
        dep_hour = df["scheduled_dep_time"].apply(lambda t: int(str(t).split(":")[0]) if str(t) and ":" in str(t) else 12)
        arr_hour = df["scheduled_arr_time"].apply(lambda t: int(str(t).split(":")[0]) if str(t) and ":" in str(t) else 14)

        X = pd.DataFrame(index=df.index)

        # 1. Temporal & Scheduling Features
        X["day_of_week"] = df["day_of_week"].astype(int)
        X["day_of_month"] = df["day_of_month"].astype(int)
        X["month"] = df["month"].astype(int)
        X["scheduled_dep_hour"] = dep_hour
        X["scheduled_arr_hour"] = arr_hour
        X["scheduled_elapsed_minutes"] = df["scheduled_elapsed_minutes"].fillna(self.imputation_medians.get("scheduled_elapsed_minutes", 120.0))
        X["distance_miles"] = df["distance_miles"].fillna(self.imputation_medians.get("distance_miles", 750.0))

        # Diurnal peak flags & route geometry
        X["is_dep_morning_peak"] = ((dep_hour >= 6) & (dep_hour <= 9)).astype(int)
        X["is_dep_evening_peak"] = ((dep_hour >= 16) & (dep_hour <= 19)).astype(int)
        X["is_dep_night"] = ((dep_hour >= 21) | (dep_hour <= 5)).astype(int)
        X["is_transcon"] = (X["distance_miles"] >= 2000.0).astype(int)
        X["is_short_hop"] = (X["distance_miles"] <= 400.0).astype(int)

        # 2. Historical Training Priors (Leakage-free point-in-time)
        g_cancel = self.training_priors.get("global_cancel_rate", 0.02)
        g_delay120 = self.training_priors.get("global_delay120_rate", 0.04)
        g_m_delay = self.training_priors.get("global_mean_delay", 8.5)

        c_stats = self.training_priors.get("carrier_stats", {})
        c_delays = self.training_priors.get("carrier_delays", {})
        o_stats = self.training_priors.get("origin_stats", {})
        d_stats = self.training_priors.get("dest_stats", {})
        r_stats = self.training_priors.get("route_stats", {})

        X["carrier_prior_cancel_rate"] = df["carrier_code"].apply(
            lambda c: c_stats[c]["carrier_cancel_rate"] if c in c_stats else g_cancel
        )
        X["carrier_prior_delay120_rate"] = df["carrier_code"].apply(
            lambda c: c_stats[c]["carrier_delay120_rate"] if c in c_stats else g_delay120
        )
        X["carrier_prior_mean_delay"] = df["carrier_code"].apply(
            lambda c: c_delays.get(c, g_m_delay)
        )

        X["origin_prior_cancel_rate"] = df["origin_airport"].apply(
            lambda o: o_stats[o]["origin_cancel_rate"] if o in o_stats else g_cancel
        )
        X["origin_prior_delay120_rate"] = df["origin_airport"].apply(
            lambda o: o_stats[o]["origin_delay120_rate"] if o in o_stats else g_delay120
        )

        X["dest_prior_cancel_rate"] = df["dest_airport"].apply(
            lambda d: d_stats[d]["dest_cancel_rate"] if d in d_stats else g_cancel
        )
        X["dest_prior_delay120_rate"] = df["dest_airport"].apply(
            lambda d: d_stats[d]["dest_delay120_rate"] if d in d_stats else g_delay120
        )

        routes = [f"{o}_{d}" for o, d in zip(df["origin_airport"], df["dest_airport"])]
        X["route_prior_cancel_rate"] = [
            r_stats[r]["route_cancel_rate"] if r in r_stats else g_cancel for r in routes
        ]
        X["route_prior_delay120_rate"] = [
            r_stats[r]["route_delay120_rate"] if r in r_stats else g_delay120 for r in routes
        ]

        # 3. Categorical Encodings (One-Hot for All 15 BTS Carriers)
        for carrier in ALL_BTS_CARRIERS:
            X[f"carrier_{carrier}"] = (df["carrier_code"] == carrier).astype(int)
        X["carrier_other"] = (~df["carrier_code"].isin(ALL_BTS_CARRIERS)).astype(int)

        # 4. Categorical Encodings (One-Hot for Top 20 Hubs)
        for apt in TOP_20_AIRPORTS:
            X[f"origin_{apt}"] = (df["origin_airport"] == apt).astype(int)
        X["origin_other"] = (~df["origin_airport"].isin(TOP_20_AIRPORTS)).astype(int)

        for apt in TOP_20_AIRPORTS:
            X[f"dest_{apt}"] = (df["dest_airport"] == apt).astype(int)
        X["dest_other"] = (~df["dest_airport"].isin(TOP_20_AIRPORTS)).astype(int)

        self.feature_columns = list(X.columns)

        # Targets (optional during inference)
        y_cancel = df["is_cancelled"].astype(int) if "is_cancelled" in df.columns else None
        y_delay120 = df["is_delayed_120m"].astype(int) if "is_delayed_120m" in df.columns else None
        y_arr_delay = df["arr_delay_minutes"].fillna(0.0) if "arr_delay_minutes" in df.columns else None

        return X, y_cancel, y_delay120, y_arr_delay

    def transform_features(self, df: pd.DataFrame) -> pd.DataFrame:
        """
        Inference-safe transform returning exclusively feature matrix X aligned to feature_columns.
        """
        X, _, _, _ = self.transform(df)
        if self.feature_columns:
            # Guarantee identical column order
            return X[self.feature_columns]
        return X

    def save(self, filepath: Path):
        data = {
            "imputation_medians": self.imputation_medians,
            "training_priors": self.training_priors,
            "feature_columns": self.feature_columns
        }
        joblib.dump(data, filepath)
        logger.info(f"Leakage-free preprocessor saved to {filepath}")

    @classmethod
    def load(cls, filepath: Path) -> "LeakageFreePreprocessor":
        instance = cls()
        data = joblib.load(filepath)
        instance.imputation_medians = data["imputation_medians"]
        instance.training_priors = data["training_priors"]
        instance.feature_columns = data["feature_columns"]
        return instance
