"""
FLYO STAGE 8 — FEATURE ENGINEERING V3 MODULE
Reusable, point-in-time leakage-free preprocessor for Indian priority corridors
and global hub disruption and delay modeling.
"""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Tuple

import numpy as np
import pandas as pd

logger = logging.getLogger("FeatureEngineeringV3")

TOP_CARRIERS = ["6E", "AI", "UK", "SG", "QP", "WN", "AA", "DL", "UA"]
TOP_AIRPORTS = ["DEL", "BOM", "BLR", "HYD", "MAA", "CCU", "ATL", "ORD", "DFW", "DEN", "SFO", "JFK"]

class FeatureEngineeringV3:
    """
    Leakage-free point-in-time feature engineering for Stage 8.
    Supports both Indian domestic corridors and global hubs.
    """

    def __init__(self):
        self.priors: Dict[str, Any] = {}
        self.feature_columns: List[str] = []

    def fit(self, train_df: pd.DataFrame):
        logger.info("Fitting training priors strictly on training partition...")
        global_cancel = float(train_df["is_cancelled"].mean())
        completed = train_df[train_df["is_cancelled"] == 0]
        global_delay15 = float(completed["is_delayed_15m"].mean() if not completed.empty else 0.20)
        global_delay45 = float((completed["arr_delay_minutes"] >= 45.0).mean() if not completed.empty else 0.08)
        global_mean_delay = float(completed["arr_delay_minutes"].mean() if not completed.empty else 10.0)

        carrier_priors = train_df.groupby("carrier_code").agg(
            cancel_rate=("is_cancelled", "mean"),
            delay15_rate=("is_delayed_15m", "mean"),
        ).to_dict(orient="index")

        origin_priors = train_df.groupby("origin_airport").agg(
            cancel_rate=("is_cancelled", "mean"),
            delay15_rate=("is_delayed_15m", "mean"),
        ).to_dict(orient="index")

        dest_priors = train_df.groupby("dest_airport").agg(
            cancel_rate=("is_cancelled", "mean"),
            delay15_rate=("is_delayed_15m", "mean"),
        ).to_dict(orient="index")

        route_priors = train_df.groupby(["origin_airport", "dest_airport"]).agg(
            cancel_rate=("is_cancelled", "mean"),
            delay15_rate=("is_delayed_15m", "mean"),
            mean_delay=("arr_delay_minutes", "mean"),
            flight_count=("flight_id", "count"),
        )
        route_dict = {}
        for (orig, dest), r_row in route_priors.iterrows():
            if r_row["flight_count"] >= 5:
                route_dict[f"{orig}_{dest}"] = {
                    "cancel_rate": float(r_row["cancel_rate"]),
                    "delay15_rate": float(r_row["delay15_rate"]),
                    "mean_delay": float(r_row["mean_delay"]) if not np.isnan(r_row["mean_delay"]) else global_mean_delay,
                }

        self.priors = {
            "global_cancel": global_cancel,
            "global_delay15": global_delay15,
            "global_delay45": global_delay45,
            "global_mean_delay": global_mean_delay,
            "carrier_priors": carrier_priors,
            "origin_priors": origin_priors,
            "dest_priors": dest_priors,
            "route_priors": route_dict,
            "median_elapsed": float(train_df["scheduled_elapsed_minutes"].median()),
            "median_distance": float(train_df["distance_miles"].median()),
        }

    def transform(self, df: pd.DataFrame) -> Tuple[pd.DataFrame, pd.Series, pd.Series, pd.Series]:
        X = pd.DataFrame(index=df.index)

        # 1. Temporal & Scheduling
        dep_hour = df["scheduled_dep_time"].apply(lambda t: int(str(t).split(":")[0]) if str(t) and ":" in str(t) else 12)
        arr_hour = df["scheduled_arr_time"].apply(lambda t: int(str(t).split(":")[0]) if str(t) and ":" in str(t) else 14)

        X["day_of_week"] = df["day_of_week"].astype(int)
        X["day_of_month"] = df["day_of_month"].astype(int)
        X["month"] = df["month"].astype(int)
        X["scheduled_dep_hour"] = dep_hour
        X["scheduled_arr_hour"] = arr_hour
        X["scheduled_elapsed_minutes"] = df["scheduled_elapsed_minutes"].fillna(self.priors.get("median_elapsed", 90.0))
        X["distance_miles"] = df["distance_miles"].fillna(self.priors.get("median_distance", 500.0))

        # Peak hours & diurnal congestion
        X["is_dep_morning_peak"] = ((dep_hour >= 6) & (dep_hour <= 9)).astype(int)
        X["is_dep_evening_peak"] = ((dep_hour >= 17) & (dep_hour <= 20)).astype(int)
        X["is_dep_night"] = ((dep_hour >= 21) | (dep_hour <= 5)).astype(int)

        # 2. Seasonality & Region-Specific Weather Risk Indicators
        is_winter = df["month"].isin([12, 1, 2]).astype(int)
        is_monsoon = df["month"].isin([6, 7, 8]).astype(int)
        is_del_hub = ((df["origin_airport"] == "DEL") | (df["dest_airport"] == "DEL")).astype(int)
        is_bom_hub = ((df["origin_airport"] == "BOM") | (df["dest_airport"] == "BOM")).astype(int)

        X["delhi_winter_fog_risk"] = (is_winter & is_del_hub & (dep_hour <= 10)).astype(int)
        X["mumbai_monsoon_convection_risk"] = (is_monsoon & is_bom_hub).astype(int)

        # 3. Weather Features (Point-in-Time available at T-2h)
        X["temp_c"] = df.get("temp_c", 24.0)
        X["wind_speed_kmh"] = df.get("wind_speed_kmh", 12.0)
        X["precip_mm"] = df.get("precip_mm", 0.0)
        X["visibility_km"] = df.get("visibility_km", 10.0)
        X["is_thunderstorm_reported"] = df.get("is_thunderstorm", 0)

        # 4. Historical Empirical Priors
        g_c = self.priors.get("global_cancel", 0.02)
        g_d = self.priors.get("global_delay15", 0.18)
        g_m = self.priors.get("global_mean_delay", 10.0)

        c_p = self.priors.get("carrier_priors", {})
        o_p = self.priors.get("origin_priors", {})
        d_p = self.priors.get("dest_priors", {})
        r_p = self.priors.get("route_priors", {})

        X["carrier_prior_cancel_rate"] = df["carrier_code"].apply(lambda c: c_p[c]["cancel_rate"] if c in c_p else g_c)
        X["carrier_prior_delay_rate"] = df["carrier_code"].apply(lambda c: c_p[c]["delay15_rate"] if c in c_p else g_d)
        X["origin_prior_cancel_rate"] = df["origin_airport"].apply(lambda o: o_p[o]["cancel_rate"] if o in o_p else g_c)
        X["origin_prior_delay_rate"] = df["origin_airport"].apply(lambda o: o_p[o]["delay15_rate"] if o in o_p else g_d)
        X["dest_prior_cancel_rate"] = df["dest_airport"].apply(lambda d: d_p[d]["cancel_rate"] if d in d_p else g_c)
        X["dest_prior_delay_rate"] = df["dest_airport"].apply(lambda d: d_p[d]["delay15_rate"] if d in d_p else g_d)

        routes = [f"{o}_{d}" for o, d in zip(df["origin_airport"], df["dest_airport"])]
        X["route_prior_cancel_rate"] = [r_p[r]["cancel_rate"] if r in r_p else g_c for r in routes]
        X["route_prior_mean_delay"] = [r_p[r]["mean_delay"] if r in r_p else g_m for r in routes]

        # 5. One-Hot Encoding for Carriers
        for carrier in TOP_CARRIERS:
            X[f"carrier_{carrier}"] = (df["carrier_code"] == carrier).astype(int)
        X["carrier_other"] = (~df["carrier_code"].isin(TOP_CARRIERS)).astype(int)

        # 6. One-Hot Encoding for Origins and Destinations
        for apt in TOP_AIRPORTS:
            X[f"orig_{apt}"] = (df["origin_airport"] == apt).astype(int)
            X[f"dest_{apt}"] = (df["dest_airport"] == apt).astype(int)
        X["orig_other"] = (~df["origin_airport"].isin(TOP_AIRPORTS)).astype(int)
        X["dest_other"] = (~df["dest_airport"].isin(TOP_AIRPORTS)).astype(int)

        self.feature_columns = list(X.columns)

        # Targets
        y_cancel = df["is_cancelled"].astype(int) if "is_cancelled" in df.columns else None
        y_disrupt = None
        if "is_cancelled" in df.columns and "arr_delay_minutes" in df.columns:
            y_disrupt = ((df["is_cancelled"] == 1) | (df["arr_delay_minutes"] >= 45.0)).astype(int)
        elif "is_cancelled" in df.columns:
            y_disrupt = df["is_cancelled"].astype(int)

        y_delay = df["arr_delay_minutes"].fillna(0.0) if "arr_delay_minutes" in df.columns else None

        return X, y_cancel, y_disrupt, y_delay
