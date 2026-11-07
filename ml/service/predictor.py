import datetime
import logging
import time
from typing import List, Optional, Tuple

import numpy as np
import pandas as pd
import xgboost as xgb

from ml.service.config import INDIAN_AIRPORT_CODES, MODEL_VERSION, SUPPORTED_CARRIERS, TOP_HUBS
from ml.service.monitoring_db import MonitoringDB
from ml.service.registry import ModelRegistry
from ml.service.schemas import (
    BatchPredictionRequest,
    BatchPredictionResponse,
    FlightPredictionRequest,
    FlightPredictionResponse,
    PredictionStatus,
    RiskLevel,
)

logger = logging.getLogger("FlightPredictor")

INDIAN_AIRLINE_CODES = {"6E", "AI", "UK", "SG", "QP", "G8", "IX"}

class FlightPredictor:
    """
    Executes flight disruption inference with geofencing, carrier eligibility checks,
    risk factor extraction, defensive error handling, and persistent observability tracing.
    """

    def __init__(self, registry: Optional[ModelRegistry] = None, db: Optional[MonitoringDB] = None):
        self.registry = registry or ModelRegistry.get_instance()
        self.db = db or MonitoringDB()

    def _check_airspace_and_carrier(
        self, req: FlightPredictionRequest
    ) -> Tuple[Optional[PredictionStatus], Optional[str], List[str]]:
        origin = req.origin_airport.upper()
        dest = req.dest_airport.upper()
        carrier = req.carrier_code.upper()

        # 1. Geofence Check: Indian Airspace or Indian Carriers
        if (
            origin in INDIAN_AIRPORT_CODES
            or dest in INDIAN_AIRPORT_CODES
            or carrier in INDIAN_AIRLINE_CODES
        ):
            return (
                PredictionStatus.UNSUPPORTED_AIRSPACE,
                f"Flight {req.flight_number} ({origin} -> {dest}) operates in Indian domestic or international airspace. "
                f"Flyo v2 ML disruption models were trained exclusively on US domestic flights (BTS). "
                f"Indian airspace is currently unsupported for statistical ML inference.",
                [
                    "UNSUPPORTED_AIRSPACE: Indian airspace is outside the US BTS training corpus.",
                    "SAFETY_DIRECTIVE: Predictive ML is disabled for this route. Rely exclusively on live flight radar and operator review."
                ],
            )

        # 2. International / Non-US Domestic Airspace Check
        supported_airports = getattr(self.registry, "supported_airports", set())
        if supported_airports:
            if origin not in supported_airports or dest not in supported_airports:
                invalid_apt = origin if origin not in supported_airports else dest
                return (
                    PredictionStatus.UNSUPPORTED_AIRSPACE,
                    f"Flight route ({origin} -> {dest}) operates outside US domestic BTS airspace. "
                    f"Airport '{invalid_apt}' is an international or non-BTS facility. "
                    f"Flyo v2 ML models were trained exclusively on US domestic routes.",
                    [
                        f"UNSUPPORTED_AIRSPACE: Route contains non-US or international airport '{invalid_apt}'.",
                        "GEOGRAPHIC_BOUNDARY: Statistical prediction disabled for international or non-BTS airspaces."
                    ],
                )

        # 3. Coverage Check: US Reporting Carriers
        if carrier not in SUPPORTED_CARRIERS:
            return (
                PredictionStatus.INSUFFICIENT_COVERAGE,
                f"Carrier '{carrier}' is not among the 15 US reporting carriers represented in the multi-season training corpus.",
                [
                    f"INSUFFICIENT_COVERAGE: Carrier '{carrier}' lacks empirical training priors in the v2 dataset."
                ],
            )

        return None, None, []

    def _extract_datetime_features(self, req: FlightPredictionRequest) -> Tuple[int, int, int]:
        """Extracts day_of_week (1-7), day_of_month (1-31), month (1-12)"""
        if req.departure_date:
            try:
                dt = datetime.date.fromisoformat(req.departure_date)
                # ISO: Monday=1, Sunday=7
                return dt.isoweekday(), dt.day, dt.month
            except ValueError:
                pass

        # Fallback to provided fields or current UTC date
        now = datetime.datetime.now(datetime.timezone.utc)
        dow = req.day_of_week if req.day_of_week is not None else now.isoweekday()
        dom = req.day_of_month if req.day_of_month is not None else now.day
        month = req.month if req.month is not None else now.month
        return dow, dom, month

    def _assess_risk_factors(
        self,
        req: FlightPredictionRequest,
        p_cancel: float,
        p_delay120: float,
        exp_delay: float,
        dep_hour: int,
    ) -> Tuple[RiskLevel, List[str]]:
        factors = []

        # Scheduled time slot factors
        if 6 <= dep_hour <= 9:
            factors.append("PEAK_MORNING_DEPARTURE: Morning departure window with high network traffic density.")
        elif 16 <= dep_hour <= 19:
            factors.append("PEAK_EVENING_DEPARTURE: Evening departure window subject to compounding upstream delays.")
        elif dep_hour >= 21 or dep_hour <= 5:
            factors.append("LATE_NIGHT_OPERATIONS: Increased risk of crew duty time expiration or overnight curfews.")

        # Hub factors
        if req.origin_airport in TOP_HUBS:
            factors.append(f"ORIGIN_CONGESTION_HUB: {req.origin_airport} is a high-volume hub subject to ground delay programs.")
        if req.dest_airport in TOP_HUBS:
            factors.append(f"DESTINATION_CONGESTION_HUB: {req.dest_airport} is a high-volume arrival hub.")

        # Route-level frequency representation
        route_key = f"{req.origin_airport}_{req.dest_airport}"
        if self.registry and self.registry.preprocessor:
            r_stats = self.registry.preprocessor.training_priors.get("route_stats", {})
            if route_key not in r_stats:
                factors.append(f"INSUFFICIENT_ROUTE_FREQUENCY: Route {route_key} has low historical sample density in BTS training partition; evaluating via marginal airport and carrier priors.")

        # Delay & Cancellation Severity
        if p_cancel >= 0.08:
            factors.append(f"ELEVATED_CANCELLATION_RISK: Calibrated cancellation probability ({p_cancel*100:.1f}%) exceeds seasonal baseline.")
        if p_delay120 >= 0.10:
            factors.append(f"EXTREME_DELAY_RISK: Calibrated probability of flight arrival delay >=120m is {p_delay120*100:.1f}%.")
        if exp_delay >= 45.0:
            factors.append(f"PROTRACTED_ARRIVAL_DELAY: Model projects expected arrival delay of {exp_delay:.0f} minutes.")

        # Connection notice
        factors.append("CONNECTION_DISCLAIMER: Extreme delay >=120m is a single-flight arrival delay target; it does not replace connection feasibility evaluation.")

        # Level assignment
        if p_cancel >= 0.15 or p_delay120 >= 0.25:
            level = RiskLevel.CRITICAL
        elif p_cancel >= 0.08 or p_delay120 >= 0.15 or exp_delay >= 60.0:
            level = RiskLevel.ELEVATED
        elif p_cancel >= 0.04 or p_delay120 >= 0.08 or exp_delay >= 30.0:
            level = RiskLevel.MODERATE
        else:
            level = RiskLevel.LOW

        return level, factors

    def predict_single(self, req: FlightPredictionRequest) -> FlightPredictionResponse:
        start_time = time.perf_counter()

        # Check model registry readiness
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

        # Geofence & coverage check
        status, reason, factors = self._check_airspace_and_carrier(req)
        if status is not None:
            latency = (time.perf_counter() - start_time) * 1000.0
            pred_id = self.db.record_prediction(
                model_version=MODEL_VERSION,
                schema_version="2.0.0",
                flight_number=req.flight_number,
                carrier_code=req.carrier_code,
                origin_airport=req.origin_airport,
                dest_airport=req.dest_airport,
                scheduled_dep_time=req.scheduled_dep_time,
                scheduled_arr_time=req.scheduled_arr_time,
                departure_date=req.departure_date,
                status=status.value,
                status_reason=reason,
                cancellation_probability=None,
                delay_120m_probability=None,
                expected_delay_minutes=None,
                disruption_risk_level=RiskLevel.UNKNOWN.value,
                inference_latency_ms=round(latency, 2),
                is_duration_imputed=(req.scheduled_elapsed_minutes is None),
                is_distance_imputed=(req.distance_miles is None),
                distance_miles=req.distance_miles,
                scheduled_elapsed_minutes=req.scheduled_elapsed_minutes,
                dep_hour=12,
                arr_hour=14,
                day_of_week=1,
                month=1,
            )
            return FlightPredictionResponse(
                prediction_id=pred_id,
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
            # Prepare feature input
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
            }])

            # Leakage-free feature transform
            X = self.registry.preprocessor.transform_features(df_input)

            # Inferences
            p_cancel = float(self.registry.calibrated_cancellation.predict_proba(X)[0, 1])
            p_delay120 = float(self.registry.calibrated_delay120.predict_proba(X)[0, 1])

            dmat = xgb.DMatrix(X)
            exp_delay = float(self.registry.xgb_delay_regressor.predict(dmat)[0])
            exp_delay = max(0.0, exp_delay) # Non-negative expected delay for disruption analysis

            risk_level, risk_factors = self._assess_risk_factors(
                req, p_cancel, p_delay120, exp_delay, dep_hour
            )

            latency = (time.perf_counter() - start_time) * 1000.0
            is_duration_imputed = (req.scheduled_elapsed_minutes is None)
            is_distance_imputed = (req.distance_miles is None)

            # Persist traceable prediction record (Zero PII)
            pred_id = self.db.record_prediction(
                model_version=MODEL_VERSION,
                schema_version="2.0.0",
                flight_number=req.flight_number,
                carrier_code=req.carrier_code,
                origin_airport=req.origin_airport,
                dest_airport=req.dest_airport,
                scheduled_dep_time=req.scheduled_dep_time,
                scheduled_arr_time=req.scheduled_arr_time or f"{arr_hour:02d}:00",
                departure_date=req.departure_date,
                status=PredictionStatus.PREDICTION_AVAILABLE.value,
                status_reason="Disruption probabilities and expected delay calculated from v2 multi-season models.",
                cancellation_probability=round(p_cancel, 4),
                delay_120m_probability=round(p_delay120, 4),
                expected_delay_minutes=round(exp_delay, 1),
                disruption_risk_level=risk_level.value,
                inference_latency_ms=round(latency, 2),
                is_duration_imputed=is_duration_imputed,
                is_distance_imputed=is_distance_imputed,
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
                status_reason="Disruption probabilities and expected delay calculated from v2 multi-season models.",
                cancellation_probability=round(p_cancel, 4),
                delay_120m_probability=round(p_delay120, 4),
                expected_delay_minutes=round(exp_delay, 1),
                disruption_risk_level=risk_level,
                risk_factors=risk_factors,
                is_experimental=True,
                is_duration_imputed=is_duration_imputed,
                is_distance_imputed=is_distance_imputed,
                model_version=MODEL_VERSION,
                inference_latency_ms=round(latency, 2),
            )

        except Exception as e:
            logger.error(f"Inference error for {req.flight_number}: {e}", exc_info=True)
            latency = (time.perf_counter() - start_time) * 1000.0
            return FlightPredictionResponse(
                flight_number=req.flight_number,
                carrier_code=req.carrier_code,
                origin_airport=req.origin_airport,
                dest_airport=req.dest_airport,
                status=PredictionStatus.INVALID_FEATURE_INPUT,
                status_reason=f"Feature transformation or inference exception: {str(e)}",
                disruption_risk_level=RiskLevel.UNKNOWN,
                risk_factors=["ERROR: Failed during feature matrix preparation or model execution"],
                model_version=MODEL_VERSION,
                inference_latency_ms=round(latency, 2),
            )

    def predict_batch(self, batch_req: BatchPredictionRequest) -> BatchPredictionResponse:
        start_time = time.perf_counter()
        results = [self.predict_single(flight) for flight in batch_req.flights]
        total_latency = (time.perf_counter() - start_time) * 1000.0
        success_count = sum(1 for r in results if r.status == PredictionStatus.PREDICTION_AVAILABLE)

        return BatchPredictionResponse(
            results=results,
            total_flights=len(results),
            successful_predictions=success_count,
            total_latency_ms=round(total_latency, 2),
        )
