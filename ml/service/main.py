import logging
import os
import secrets
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from ml.service.alert_engine import AlertEngine
from ml.service.config import HOST, MODEL_VERSION, PORT
from ml.service.drift_detector import DriftDetector
from ml.service.governance import ModelGovernance
from ml.service.monitoring_db import MonitoringDB
from ml.service.performance_evaluator import PerformanceEvaluator
from ml.service.predictor import FlightPredictor
from ml.service.quality_tracker import DataQualityTracker
from ml.service.registry import ModelRegistry
from ml.service.schemas import (
    BatchPredictionRequest,
    BatchPredictionResponse,
    FlightPredictionRequest,
    FlightPredictionResponse,
    GroundTruthIngestRequest,
    GroundTruthIngestResponse,
    HealthResponse,
    ModelMetadataResponse,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s"
)
logger = logging.getLogger("FlyoInferenceService")

ENV = os.getenv("FLYO_ENV", "development").lower()
MONITORING_API_KEY = os.getenv("FLYO_MONITORING_API_KEY", "")
DEFAULT_DEV_KEY = "flyo-monitoring-secret-key-2026"

if not MONITORING_API_KEY:
    if ENV in ("prod", "production"):
        logger.critical("FATAL: FLYO_MONITORING_API_KEY environment variable is required in production environment.")
    else:
        MONITORING_API_KEY = DEFAULT_DEV_KEY

def verify_monitoring_key(x_flyo_monitoring_key: Optional[str] = Header(None, alias="X-Flyo-Monitoring-Key")):
    """Verifies authorization token for internal ML monitoring and outcome ingestion endpoints using constant-time comparison."""
    if not MONITORING_API_KEY:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Monitoring service security key is not configured on server."
        )
    if not x_flyo_monitoring_key or not secrets.compare_digest(x_flyo_monitoring_key, MONITORING_API_KEY):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing X-Flyo-Monitoring-Key authentication header."
        )
    return True

monitoring_db = MonitoringDB()
drift_detector = DriftDetector()
quality_tracker = DataQualityTracker()
performance_evaluator = PerformanceEvaluator()
alert_engine = AlertEngine()
governance = ModelGovernance(db=monitoring_db)
predictor = FlightPredictor(db=monitoring_db)

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: Load ML artifacts into memory & init governance
    logger.info("Initializing Flyo Predictive Intelligence ML service & Monitoring layer...")
    registry = ModelRegistry.get_instance()
    try:
        registry.load_artifacts()
        governance.initialize_v2_governance()
        logger.info(f"Flyo Inference & Monitoring ready on port {PORT}. Version: {MODEL_VERSION}")
    except Exception as e:
        logger.critical(f"FATAL: Failed to load ML artifacts at startup: {e}", exc_info=True)
    yield
    # Shutdown
    logger.info("Flyo Inference Service shutting down...")

app = FastAPI(
    title="Flyo Predictive Intelligence & ML Observability Service",
    description=(
        "Production-grade FastAPI inference microservice and operational monitoring layer for flight disruption risk. "
        "Strictly provides experimental supplementary intelligence and observability metrics."
    ),
    version=MODEL_VERSION,
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# -----------------------------------------------------------------------------
# CORE INFERENCE ENDPOINTS
# -----------------------------------------------------------------------------

@app.get("/health/live", summary="Liveness Probe")
async def health_live() -> Dict[str, str]:
    return {"status": "alive", "service": "flyo-ml-inference"}

@app.get("/health/ready", response_model=HealthResponse, summary="Readiness Probe")
async def health_ready():
    registry = ModelRegistry.get_instance()
    models_status = registry.get_models_status()
    all_ready = all(models_status.values()) and registry.is_loaded

    response_data = HealthResponse(
        status="HEALTHY" if all_ready else "DEGRADED",
        service="Flyo Predictive Intelligence",
        model_version=MODEL_VERSION,
        models_loaded=models_status,
        uptime_seconds=round(registry.get_uptime_seconds(), 2),
    )

    if not all_ready:
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content=response_data.model_dump()
        )
    return response_data

@app.get("/models/metadata", response_model=ModelMetadataResponse, summary="Model Metadata & Performance")
async def get_model_metadata():
    registry = ModelRegistry.get_instance()
    if not registry.is_loaded:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Model artifacts are not loaded in memory"
        )
    return registry.get_metadata()

@app.post("/predict/flight", response_model=FlightPredictionResponse, summary="Predict Single Flight Disruption")
async def predict_flight(request: FlightPredictionRequest):
    return predictor.predict_single(request)

@app.post("/predict/batch", response_model=BatchPredictionResponse, summary="Predict Batch of Flights")
async def predict_batch(request: BatchPredictionRequest):
    return predictor.predict_batch(request)

# -----------------------------------------------------------------------------
# PHASE 4: ML MONITORING & OBSERVABILITY ENDPOINTS
# -----------------------------------------------------------------------------

@app.get("/monitoring/health", summary="ML Monitoring Health & Storage Status", dependencies=[Depends(verify_monitoring_key)])
async def monitoring_health():
    """Operational health of prediction persistence, pending outcome counts, and alert counts."""
    summary = monitoring_db.get_prediction_summary()
    active_alerts = monitoring_db.get_active_alerts()
    return {
        "status": "HEALTHY",
        "service": "Flyo ML Observability Layer",
        "model_version": MODEL_VERSION,
        "database_path": str(monitoring_db.db_path),
        "total_predictions_logged": summary.get("total_predictions", 0),
        "pending_outcomes_count": summary.get("outcome_status_breakdown", {}).get("PENDING_OUTCOME", 0),
        "evaluated_outcomes_count": summary.get("outcome_status_breakdown", {}).get("OUTCOME_AVAILABLE", 0) + summary.get("outcome_status_breakdown", {}).get("EVALUATED", 0),
        "active_alerts_count": len(active_alerts),
        "safety_disclaimer": "Monitoring only. Never initiates autonomous rebooking or model replacement."
    }

@app.get("/monitoring/predictions/summary", summary="Prediction Volume, Status & Latency Breakdown", dependencies=[Depends(verify_monitoring_key)])
async def monitoring_predictions_summary(
    model_version: Optional[str] = Query(None, description="Filter by model version"),
    start_date: Optional[str] = Query(None, description="Start timestamp ISO8601"),
    end_date: Optional[str] = Query(None, description="End timestamp ISO8601")
):
    return monitoring_db.get_prediction_summary(
        model_version=model_version, start_date=start_date, end_date=end_date
    )

@app.get("/monitoring/drift", summary="Population Stability Index & Feature Drift Analysis", dependencies=[Depends(verify_monitoring_key)])
async def monitoring_drift(
    model_version: Optional[str] = Query(None, description="Filter by model version"),
    limit: int = Query(500, ge=10, le=5000, description="Sample window limit")
):
    """Evaluates PSI and KS distribution tests against July 2024 held-out baseline."""
    records = monitoring_db.get_predictions(model_version=model_version, limit=limit)
    drift_result = drift_detector.evaluate_drift(records)

    # Check and record drift alerts if triggered
    drift_alerts = alert_engine.evaluate_drift_alerts(drift_result)
    for al in drift_alerts:
        monitoring_db.record_alert(
            category=al["category"],
            severity=al["severity"],
            metric_name=al["metric_name"],
            metric_value=al["metric_value"],
            threshold_value=al["threshold_value"],
            message=al["message"]
        )

    return drift_result

@app.get("/monitoring/data-quality", summary="Missingness & Median Imputation Frequency", dependencies=[Depends(verify_monitoring_key)])
async def monitoring_data_quality(
    model_version: Optional[str] = Query(None, description="Filter by model version"),
    limit: int = Query(500, ge=10, le=5000)
):
    """Monitors scheduled duration and distance median fallback rates, range violations, and unexpected categoricals."""
    records = monitoring_db.get_predictions(model_version=model_version, limit=limit)
    quality_result = quality_tracker.evaluate_quality(records)

    # Check and record operational/quality alerts
    summary = monitoring_db.get_prediction_summary(model_version=model_version)
    op_alerts = alert_engine.evaluate_operational_alerts(summary)
    for al in op_alerts:
        monitoring_db.record_alert(
            category=al["category"],
            severity=al["severity"],
            metric_name=al["metric_name"],
            metric_value=al["metric_value"],
            threshold_value=al["threshold_value"],
            message=al["message"]
        )

    return quality_result

@app.get("/monitoring/model-performance", summary="Real-World Performance on Evaluated Delayed Outcomes", dependencies=[Depends(verify_monitoring_key)])
async def monitoring_model_performance(
    model_version: Optional[str] = Query(None, description="Filter by model version")
):
    """
    Computes Brier Score, ROC-AUC, PR-AUC, ECE, MAE, and RMSE on matched ground truth outcomes.
    Compares observed performance against baseline metrics.
    """
    matched_records = monitoring_db.get_matched_evaluated_records(model_version=model_version)
    perf_result = performance_evaluator.evaluate_performance(matched_records)

    # Evaluate calibration & regression deterioration alerts
    perf_alerts = alert_engine.evaluate_performance_alerts(perf_result)
    for al in perf_alerts:
        monitoring_db.record_alert(
            category=al["category"],
            severity=al["severity"],
            metric_name=al["metric_name"],
            metric_value=al["metric_value"],
            threshold_value=al["threshold_value"],
            message=al["message"]
        )

    return perf_result

@app.get("/monitoring/alerts", summary="Active System Alerts & Health Status", dependencies=[Depends(verify_monitoring_key)])
async def monitoring_alerts(limit: int = Query(50, ge=1, le=200)):
    """Returns active evidence-based alerts for failures, latency, imputation spikes, drift, and calibration drops."""
    alerts = monitoring_db.get_active_alerts(limit=limit)
    return {
        "active_alerts_count": len(alerts),
        "alerts": alerts,
        "governance_rule": "Alerts provide operator visibility. Never initiate autonomous retraining or rebooking."
    }

@app.get("/monitoring/governance", summary="Model Registry, Checksums & Rollback Readiness", dependencies=[Depends(verify_monitoring_key)])
async def monitoring_governance():
    """Versioned model metadata, SHA-256 artifact checksums, training periods, and v1 rollback readiness."""
    return governance.get_governance_report()

@app.post("/monitoring/outcomes/ingest", response_model=GroundTruthIngestResponse, summary="Ingest Delayed Ground Truth Flight Outcome", dependencies=[Depends(verify_monitoring_key)])
async def ingest_ground_truth_outcome(request: GroundTruthIngestRequest):
    """
    Ingests actual operational flight outcome from BTS or verified flight radar.
    Deduplicates strictly by (carrier, flight_number, origin, dest, flight_date).
    Retroactively matches pending prediction records and updates outcome_status.
    """
    inserted, outcome_id = monitoring_db.ingest_ground_truth(
        flight_number=request.flight_number,
        carrier_code=request.carrier_code,
        origin_airport=request.origin_airport,
        dest_airport=request.dest_airport,
        flight_date=request.flight_date,
        is_cancelled=request.is_cancelled,
        is_delayed_120m=request.is_delayed_120m,
        arr_delay_minutes=request.arr_delay_minutes,
        provenance=request.provenance,
        actual_dep_time=request.actual_dep_time,
        actual_arr_time=request.actual_arr_time
    )

    if not inserted:
        return GroundTruthIngestResponse(
            success=False,
            outcome_id=outcome_id,
            message="Outcome already ingested for this flight and date. Duplicate ignored to preserve data integrity."
        )

    return GroundTruthIngestResponse(
        success=True,
        outcome_id=outcome_id,
        message="Ground truth outcome ingested successfully and matched with pending prediction records."
    )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("ml.service.main:app", host=HOST, port=PORT, reload=False)
