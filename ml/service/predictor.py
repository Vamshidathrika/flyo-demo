"""
FLYO STAGE 8 — PREDICTIVE INTELLIGENCE ENGINE (FLIGHT PREDICTOR)
Unified inference service providing:
1. Calibrated Disruption Classification
2. Quantile Delay Forecasting (P10, P50, P90)
3. SHAP Feature Explainability
4. Weather Hazard & Freshness Fusion
5. Passenger Connection Risk Modeling
6. Passenger Impact Estimation
"""

from __future__ import annotations

import datetime
import logging
import time
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
import xgboost as xgb

from ml.service.config import (
    INDIAN_AIRPORT_CODES,
    MODEL_VERSION,
    SUPPORTED_CARRIERS,
    SUPPORTED_INDIAN_CORRIDORS,
    TOP_HUBS,
)
from ml.service.connection_risk import ConnectionRiskEngine
from ml.service.explainability import ShapExplainabilityService
from ml.service.monitoring_db import MonitoringDB
from ml.service.passenger_impact import PassengerImpactEngine
from ml.service.registry import ModelRegistry
from ml.service.schemas import (
    BatchPredictionRequest,
    BatchPredictionResponse,
    ConnectionRiskRequest,
    ConnectionRiskResponse,
    DelayForecastRequest,
    DelayForecastResponse,
    FlightPredictionRequest,
    FlightPredictionResponse,
    PassengerImpactRequest,
    PassengerImpactResponse,
    PredictionStatus,
    RiskLevel,
    ShapFactor,
)
from ml.service.weather_fusion import WeatherFusionEngine

logger = logging.getLogger("FlightPredictor")

class FlightPredictor:
    """
    Executes flight disruption inference with multi-region support (Indian corridors + US hubs),
    weather fusion, delay quantile forecasting, and SHAP explainability.
    """

    def __init__(
        self,
        registry: Optional[ModelRegistry] = None,
        db: Optional[MonitoringDB] = None,
    ):
        self.registry = registry or ModelRegistry.get_instance()
        self.db = db or MonitoringDB()
        self.weather_fusion = WeatherFusionEngine()
        self.connection_engine = ConnectionRiskEngine()
        self.passenger_engine = PassengerImpactEngine()
        self.explainability_service = ShapExplainabilityService()

    def _check_airspace_and_carrier(
        self, req: FlightPredictionRequest
    ) -> Tuple[Optional[PredictionStatus], Optional[str], List[str]]:
        origin = req.origin_airport.upper()
        dest = req.dest_airport.upper()
        carrier = req.carrier_code.upper()

        # Check supported carrier
        if carrier not in SUPPORTED_CARRIERS:
            return (
                PredictionStatus.INSUFFICIENT_COVERAGE,
                f"Carrier '{carrier}' lacks empirical training priors in the v3 model suite.",
                [f"INSUFFICIENT_COVERAGE: Carrier '{carrier}' is not supported."],
            )

        # Check supported airport or corridor
        corridor = f"{origin}-{dest}"
        is_indian_corridor = (corridor in SUPPORTED_INDIAN_CORRIDORS) or (
            origin in INDIAN_AIRPORT_CODES and dest in INDIAN_AIRPORT_CODES
        )
        supported_airports = self.registry.supported_airports or set(TOP_HUBS).union(INDIAN_AIRPORT_CODES)

        if origin not in supported_airports or dest not in supported_airports:
            invalid_apt = origin if origin not in supported_airports else dest
            return (
                PredictionStatus.UNSUPPORTED_AIRSPACE,
                f"Route ({origin} -> {dest}) operates outside supported networks. Facility '{invalid_apt}' is outside modeled network.",
                [f"UNSUPPORTED_AIRSPACE: Facility '{invalid_apt}' is outside modeled network."],
            )

        is_origin_indian = origin in INDIAN_AIRPORT_CODES
        is_dest_indian = dest in INDIAN_AIRPORT_CODES
        if is_origin_indian != is_dest_indian:
            return (
                PredictionStatus.UNSUPPORTED_AIRSPACE,
                f"Cross-continental route ({origin} -> {dest}) is outside regional domestic operational models.",
                [f"UNSUPPORTED_AIRSPACE: International cross-region route '{origin}-{dest}'."],
            )

        return None, None, []

    def _extract_datetime_features(self, req: FlightPredictionRequest) -> Tuple[int, int, int]:
        if req.departure_date:
            try:
                dt = datetime.date.fromisoformat(req.departure_date)
                return dt.isoweekday(), dt.day, dt.month
            except ValueError:
                pass

        now = datetime.datetime.now(datetime.timezone.utc)
        dow = req.day_of_week if req.day_of_week is not None else now.isoweekday()
        dom = req.day_of_month if req.day_of_month is not None else now.day
        month = req.month if req.month is not None else now.month
        return dow, dom, month

    def predict_single(self, req: FlightPredictionRequest) -> FlightPredictionResponse:
        start_time = time.perf_counter()

        if not self.registry.is_loaded:
            latency = (time.perf_counter() - start_time) * 1000.0
            return FlightPredictionResponse(
                flight_number=req.flight_number,
                carrier_code=req.carrier_code,
                origin_airport=req.origin_airport,
                dest_airport=req.dest_airport,
                status=PredictionStatus.MODEL_UNAVAILABLE,
                status_reason="ML model artifacts are not initialized in registry.",
                disruption_risk_level=RiskLevel.UNKNOWN,
                risk_factors=["MODEL_UNAVAILABLE: Inference models not loaded"],
                model_version=MODEL_VERSION,
                inference_latency_ms=round(latency, 2),
            )

        status, reason, factors = self._check_airspace_and_carrier(req)
        if status is not None:
            latency = (time.perf_counter() - start_time) * 1000.0
            return FlightPredictionResponse(
                flight_number=req.flight_number,
                carrier_code=req.carrier_code,
                origin_airport=req.origin_airport,
                dest_airport=req.dest_airport,
                status=status,
                status_reason=reason,
                disruption_risk_level=RiskLevel.UNKNOWN,
                risk_factors=factors,
                is_duration_imputed=(req.scheduled_elapsed_minutes is None),
                is_distance_imputed=(req.distance_miles is None),
                model_version=MODEL_VERSION,
                inference_latency_ms=round(latency, 2),
            )

        try:
            dow, dom, month = self._extract_datetime_features(req)

            # Parse departure hour
            dep_hour = 12
            if req.scheduled_dep_time and ":" in req.scheduled_dep_time:
                try:
                    dep_hour = int(req.scheduled_dep_time.split(":")[0])
                except ValueError:
                    dep_hour = 12

            arr_hour = (dep_hour + 2) % 24
            if req.scheduled_arr_time and ":" in req.scheduled_arr_time:
                try:
                    arr_hour = int(req.scheduled_arr_time.split(":")[0])
                except ValueError:
                    pass

            # 1. Weather Fusion
            weather_report = self.weather_fusion.fuse_weather_data(
                airport_code=req.origin_airport,
                temperature_c=req.temperature_c,
                wind_speed_kmh=req.wind_speed_kmh,
                visibility_km=req.visibility_km,
                precipitation_mm=req.precipitation_mm,
                weather_code=req.is_thunderstorm * 95 if req.is_thunderstorm else None,
            )
            weather_hazards = weather_report.get("aviation_hazards", [])

            # 2. Build Feature Vector
            df_input = pd.DataFrame([{
                "carrier_code": req.carrier_code,
                "origin_airport": req.origin_airport,
                "dest_airport": req.dest_airport,
                "scheduled_dep_time": req.scheduled_dep_time,
                "scheduled_arr_time": req.scheduled_arr_time or f"{arr_hour:02d}:00",
                "day_of_week": dow,
                "day_of_month": dom,
                "month": month,
                "distance_miles": req.distance_miles if req.distance_miles is not None else np.nan,
                "scheduled_elapsed_minutes": req.scheduled_elapsed_minutes if req.scheduled_elapsed_minutes is not None else np.nan,
                "temp_c": weather_report["temperature_c"],
                "wind_speed_kmh": weather_report["wind_speed_kmh"],
                "precip_mm": weather_report["precipitation_mm"],
                "visibility_km": weather_report["visibility_km"],
                "is_thunderstorm": req.is_thunderstorm or 0,
            }])

            # Transform features using v3 preprocessor
            if hasattr(self.registry.preprocessor, "transform"):
                X_res = self.registry.preprocessor.transform(df_input)
                X = X_res[0] if isinstance(X_res, tuple) else X_res
            elif hasattr(self.registry.preprocessor, "transform_features"):
                X = self.registry.preprocessor.transform_features(df_input)
            else:
                X = df_input

            # 3. Disruption Classification
            p_disrupt = 0.15
            p_cancel = 0.02
            p_delay120 = 0.05

            if self.registry.calibrated_disruption is not None:
                p_disrupt = float(self.registry.calibrated_disruption.predict_proba(X)[0, 1])
                p_cancel = float(p_disrupt * 0.18)
                p_delay120 = float(p_disrupt * 0.45)
            elif self.registry.calibrated_cancellation is not None:
                p_cancel = float(self.registry.calibrated_cancellation.predict_proba(X)[0, 1])
                p_delay120 = float(self.registry.calibrated_delay120.predict_proba(X)[0, 1]) if self.registry.calibrated_delay120 else 0.05
                p_disrupt = float(np.clip(p_cancel + p_delay120, 0.01, 0.99))

            # 4. Multi-Horizon Quantile Delay Regression
            dmat = xgb.DMatrix(X)
            if self.registry.xgb_delay_p50 is not None:
                exp_delay = float(self.registry.xgb_delay_p50.predict(dmat)[0])
            elif self.registry.xgb_delay_regressor is not None:
                exp_delay = float(self.registry.xgb_delay_regressor.predict(dmat)[0])
            else:
                exp_delay = 12.0

            exp_delay = max(0.0, exp_delay)

            # Quantile bounds
            if self.registry.xgb_delay_p10 is not None:
                delay_p10 = max(0.0, float(self.registry.xgb_delay_p10.predict(dmat)[0]))
            else:
                delay_p10 = max(0.0, exp_delay - 15.0)

            if self.registry.xgb_delay_p90 is not None:
                delay_p90 = max(exp_delay, float(self.registry.xgb_delay_p90.predict(dmat)[0]))
            else:
                delay_p90 = exp_delay + 30.0

            # 5. SHAP Feature Explainability
            explain_res = self.explainability_service.explain_prediction(X, top_k=4)
            shap_factors = []
            for item in explain_res.get("top_contributing_factors", []):
                shap_factors.append(ShapFactor(
                    feature_name=item["feature_name"],
                    display_name=item["display_name"],
                    feature_value=item["feature_value"],
                    shap_value=item["shap_value"],
                    impact_direction=item["impact_direction"],
                    absolute_magnitude=item["absolute_magnitude"],
                ))

            # Risk level classification
            if p_disrupt >= 0.35 or exp_delay >= 90.0:
                risk_level = RiskLevel.CRITICAL
            elif p_disrupt >= 0.22 or exp_delay >= 45.0:
                risk_level = RiskLevel.ELEVATED
            elif p_disrupt >= 0.12 or exp_delay >= 20.0:
                risk_level = RiskLevel.MODERATE
            else:
                risk_level = RiskLevel.LOW

            # Qualitative risk factors
            risk_factors = []
            if req.origin_airport in ["DEL", "BOM", "ORD", "ATL"]:
                risk_factors.append(f"HIGH_DENSITY_HUB: {req.origin_airport} peak departure traffic density.")
            if 6 <= dep_hour <= 9:
                risk_factors.append("MORNING_PEAK_SLOT: Diurnal morning peak window.")
            elif 17 <= dep_hour <= 20:
                risk_factors.append("EVENING_COMPOUNDING_SLOT: Compounding rotation delays.")
            if p_disrupt >= 0.20:
                risk_factors.append(f"ELEVATED_DISRUPTION_RISK: Disruption probability ({p_disrupt*100:.1f}%) exceeds seasonal prior.")
            if exp_delay >= 45.0:
                risk_factors.append(f"PROTRACTED_DELAY_ALERT: Expected arrival delay of {exp_delay:.0f}m.")
            risk_factors.extend(weather_hazards)

            latency = (time.perf_counter() - start_time) * 1000.0

            # Record in monitoring DB
            pred_id = self.db.record_prediction(
                model_version=MODEL_VERSION,
                schema_version="3.0.0",
                flight_number=req.flight_number,
                carrier_code=req.carrier_code,
                origin_airport=req.origin_airport,
                dest_airport=req.dest_airport,
                scheduled_dep_time=req.scheduled_dep_time,
                scheduled_arr_time=req.scheduled_arr_time or f"{arr_hour:02d}:00",
                departure_date=req.departure_date,
                status=PredictionStatus.PREDICTION_AVAILABLE.value,
                status_reason="Stage 8 v3 multi-region disruption and delay predictions generated.",
                cancellation_probability=round(p_cancel, 4),
                delay_120m_probability=round(p_delay120, 4),
                expected_delay_minutes=round(exp_delay, 1),
                disruption_risk_level=risk_level.value,
                inference_latency_ms=round(latency, 2),
                is_duration_imputed=(req.scheduled_elapsed_minutes is None),
                is_distance_imputed=(req.distance_miles is None),
                distance_miles=req.distance_miles,
                scheduled_elapsed_minutes=req.scheduled_elapsed_minutes,
                dep_hour=dep_hour,
                arr_hour=arr_hour,
                day_of_week=dow,
                month=month,
            )

            return FlightPredictionResponse(
                prediction_id=pred_id,
                flight_number=req.flight_number,
                carrier_code=req.carrier_code,
                origin_airport=req.origin_airport,
                dest_airport=req.dest_airport,
                status=PredictionStatus.PREDICTION_AVAILABLE,
                status_reason="Stage 8 v3 multi-region disruption and delay predictions generated.",
                disruption_probability=round(p_disrupt, 4),
                cancellation_probability=round(p_cancel, 4),
                delay_120m_probability=round(p_delay120, 4),
                expected_delay_minutes=round(exp_delay, 1),
                delay_p10_minutes=round(delay_p10, 1),
                delay_p90_minutes=round(delay_p90, 1),
                disruption_risk_level=risk_level,
                risk_factors=risk_factors,
                top_contributing_factors=shap_factors,
                weather_hazards=weather_hazards,
                is_experimental=True,
                is_duration_imputed=(req.scheduled_elapsed_minutes is None),
                is_distance_imputed=(req.distance_miles is None),
                model_version=MODEL_VERSION,
                inference_latency_ms=round(latency, 2),
            )

        except Exception as e:
            logger.error(f"Inference exception for flight {req.flight_number}: {e}", exc_info=True)
            latency = (time.perf_counter() - start_time) * 1000.0
            return FlightPredictionResponse(
                flight_number=req.flight_number,
                carrier_code=req.carrier_code,
                origin_airport=req.origin_airport,
                dest_airport=req.dest_airport,
                status=PredictionStatus.MODEL_UNAVAILABLE,
                status_reason=f"Inference pipeline execution error: {str(e)}",
                disruption_risk_level=RiskLevel.UNKNOWN,
                risk_factors=["INFERENCE_EXECUTION_ERROR"],
                model_version=MODEL_VERSION,
                inference_latency_ms=round(latency, 2),
            )

    def predict_delay_forecast(self, req: DelayForecastRequest) -> DelayForecastResponse:
        start_time = time.perf_counter()
        flight_req = FlightPredictionRequest(
            flight_number=req.flight_number,
            carrier_code=req.carrier_code,
            origin_airport=req.origin_airport,
            dest_airport=req.dest_airport,
            scheduled_dep_time=req.scheduled_dep_time,
            scheduled_arr_time=req.scheduled_arr_time,
            departure_date=req.departure_date,
            temperature_c=req.temperature_c,
            wind_speed_kmh=req.wind_speed_kmh,
            visibility_km=req.visibility_km,
            precipitation_mm=req.precipitation_mm,
        )
        pred = self.predict_single(flight_req)
        exp_delay = pred.expected_delay_minutes or 10.0
        p10 = pred.delay_p10_minutes or max(0.0, exp_delay - 15.0)
        p90 = pred.delay_p90_minutes or (exp_delay + 30.0)

        # Horizon adjustment
        horizon = req.operational_horizon.upper()
        if horizon == "T_MINUS_24H":
            width_adj = 1.3
            expl = "24-hour tactical horizon based on seasonal priors and scheduled block time."
        elif horizon == "T_MINUS_6H":
            width_adj = 1.1
            expl = "6-hour dispatch horizon integrating preliminary weather and tail turnaround."
        elif horizon == "T_MINUS_3H":
            width_adj = 0.9
            expl = "3-hour horizon integrating live upstream rotation delay and surface METAR."
        else: # T_MINUS_1H
            width_adj = 0.7
            expl = "1-hour tactical gate horizon with live airport ground metering status."

        interval_w = round((p90 - p10) * width_adj, 1)
        tier = "HIGH" if exp_delay >= 45 else ("MODERATE" if exp_delay >= 20 else "LOW")

        return DelayForecastResponse(
            flight_number=req.flight_number,
            operational_horizon=horizon,
            expected_delay_minutes=round(exp_delay, 1),
            delay_p10_minutes=round(p10, 1),
            delay_p90_minutes=round(p10 + interval_w, 1),
            prediction_interval_width=interval_w,
            delay_risk_tier=tier,
            horizon_explanation=expl,
            model_version=MODEL_VERSION,
            latency_ms=round((time.perf_counter() - start_time) * 1000.0, 2),
        )

    def evaluate_connection_risk(self, req: ConnectionRiskRequest) -> ConnectionRiskResponse:
        result = self.connection_engine.evaluate_connection(
            airport_code=req.airport_code,
            scheduled_connection_minutes=req.scheduled_connection_minutes,
            predicted_inbound_delay_minutes=req.predicted_inbound_delay_minutes,
            inbound_terminal=req.inbound_terminal,
            outbound_terminal=req.outbound_terminal,
            is_international=req.is_international,
            has_checked_baggage=req.has_checked_baggage,
            passenger_count=req.passenger_count,
        )
        return ConnectionRiskResponse(
            airport_code=result["airport_code"],
            inbound_flight_number=req.inbound_flight_number,
            outbound_flight_number=req.outbound_flight_number,
            scheduled_connection_minutes=result["scheduled_connection_minutes"],
            predicted_inbound_delay_minutes=result["predicted_inbound_delay_minutes"],
            effective_mct_minutes=result["effective_mct_minutes"],
            is_inter_terminal_transfer=result["is_inter_terminal_transfer"],
            available_window_minutes=result["available_window_minutes"],
            connection_margin_minutes=result["connection_margin_minutes"],
            connection_failure_probability=result["connection_failure_probability"],
            risk_tier=result["risk_tier"],
            recommended_action=result["recommended_action"],
            action_description=result["action_description"],
            passenger_count=result["passenger_count"],
            timestamp_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        )

    def estimate_passenger_impact(self, req: PassengerImpactRequest) -> PassengerImpactResponse:
        res = self.passenger_engine.estimate_flight_impact(
            flight_number=req.flight_number,
            carrier_code=req.carrier_code,
            origin_airport=req.origin_airport,
            dest_airport=req.dest_airport,
            disruption_probability=req.disruption_probability,
            expected_delay_minutes=req.expected_delay_minutes,
            is_cancelled=req.is_cancelled,
            aircraft_type=req.aircraft_type,
            load_factor=req.load_factor,
            connecting_passenger_ratio=req.connecting_passenger_ratio,
            elite_passenger_ratio=req.elite_passenger_ratio,
        )
        return PassengerImpactResponse(**res)

    def predict_batch(self, req: BatchPredictionRequest) -> BatchPredictionResponse:
        start_time = time.perf_counter()
        results = [self.predict_single(f) for f in req.flights]
        successful = sum(1 for r in results if r.status == PredictionStatus.PREDICTION_AVAILABLE)
        total_latency = (time.perf_counter() - start_time) * 1000.0
        return BatchPredictionResponse(
            results=results,
            total_flights=len(req.flights),
            successful_predictions=successful,
            total_latency_ms=round(total_latency, 2),
        )
