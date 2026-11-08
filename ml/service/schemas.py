from enum import Enum
from typing import Dict, List, Optional, Any
from pydantic import BaseModel, Field, field_validator
import datetime

class PredictionStatus(str, Enum):
    PREDICTION_AVAILABLE = "PREDICTION_AVAILABLE"
    INSUFFICIENT_COVERAGE = "INSUFFICIENT_COVERAGE"
    UNSUPPORTED_AIRSPACE = "UNSUPPORTED_AIRSPACE"
    MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"
    INVALID_FEATURE_INPUT = "INVALID_FEATURE_INPUT"

class RiskLevel(str, Enum):
    LOW = "LOW"
    MODERATE = "MODERATE"
    ELEVATED = "ELEVATED"
    CRITICAL = "CRITICAL"
    UNKNOWN = "UNKNOWN"

class FlightPredictionRequest(BaseModel):
    flight_number: str = Field(..., description="Flight number, e.g. AA100 or 6E501")
    carrier_code: str = Field(..., description="IATA 2-letter airline code, e.g. AA, DL, WN, 6E")
    origin_airport: str = Field(..., description="IATA 3-letter origin airport code, e.g. ORD, DEL")
    dest_airport: str = Field(..., description="IATA 3-letter destination airport code, e.g. DFW, BOM")
    scheduled_dep_time: str = Field(..., description="Scheduled departure time in HH:MM format, e.g. '14:30'")
    scheduled_arr_time: Optional[str] = Field(None, description="Scheduled arrival time in HH:MM format, e.g. '17:15'")
    departure_date: Optional[str] = Field(None, description="Departure date in YYYY-MM-DD format")
    day_of_week: Optional[int] = Field(None, ge=1, le=7, description="Day of week (1=Monday .. 7=Sunday)")
    day_of_month: Optional[int] = Field(None, ge=1, le=31, description="Day of month (1-31)")
    month: Optional[int] = Field(None, ge=1, le=12, description="Month of year (1-12)")
    distance_miles: Optional[float] = Field(None, ge=0, description="Great circle flight distance in miles")
    scheduled_elapsed_minutes: Optional[float] = Field(None, ge=0, description="Scheduled flight duration in minutes")

    @field_validator("carrier_code", "origin_airport", "dest_airport")
    @classmethod
    def normalize_uppercase(cls, v: str) -> str:
        return v.strip().upper() if v else v

class BatchPredictionRequest(BaseModel):
    flights: List[FlightPredictionRequest] = Field(..., min_length=1, max_length=100)

class FlightPredictionResponse(BaseModel):
    prediction_id: Optional[str] = None
    flight_number: str
    carrier_code: str
    origin_airport: str
    dest_airport: str
    status: PredictionStatus
    status_reason: Optional[str] = None
    cancellation_probability: Optional[float] = Field(
        None, description="Calibrated probability of flight cancellation [0.0 - 1.0]"
    )
    delay_120m_probability: Optional[float] = Field(
        None, description="Calibrated probability of flight arrival delay >= 120m [0.0 - 1.0]. Note: NOT a missed connection probability."
    )
    expected_delay_minutes: Optional[float] = Field(
        None, description="Predicted arrival delay in minutes"
    )
    disruption_risk_level: RiskLevel = RiskLevel.UNKNOWN
    risk_factors: List[str] = Field(default_factory=list)
    is_experimental: bool = True
    is_duration_imputed: bool = False
    is_distance_imputed: bool = False
    disclaimer: str = (
        "Experimental intelligence only. Extreme delay >=120m is a flight delay classification target, "
        "never a missed connection probability. Human operator approval is strictly required before rebooking."
    )
    model_version: str
    inference_latency_ms: float

class GroundTruthIngestRequest(BaseModel):
    flight_number: str
    carrier_code: str
    origin_airport: str
    dest_airport: str
    flight_date: str = Field(..., description="Flight date in YYYY-MM-DD format")
    is_cancelled: int = Field(..., ge=0, le=1)
    is_delayed_120m: int = Field(..., ge=0, le=1)
    arr_delay_minutes: float = Field(..., description="Actual arrival delay in minutes")
    provenance: str = Field("BTS_ON_TIME_DATABASE", description="Source attribution")
    actual_dep_time: Optional[str] = None
    actual_arr_time: Optional[str] = None

class GroundTruthIngestResponse(BaseModel):
    success: bool
    outcome_id: str
    message: str

class BatchPredictionResponse(BaseModel):
    results: List[FlightPredictionResponse]
    total_flights: int
    successful_predictions: int
    total_latency_ms: float

class HealthResponse(BaseModel):
    status: str
    service: str = "Flyo Predictive Intelligence"
    model_version: str
    models_loaded: Dict[str, bool]
    uptime_seconds: float

class ModelMetadataResponse(BaseModel):
    model_version: str
    models: Dict[str, Any]
    supported_carriers: List[str]
    top_hubs: List[str]
    unsupported_airspaces: List[str]
    metrics: Dict[str, Any]
    feature_count: int
    feature_names: List[str]
