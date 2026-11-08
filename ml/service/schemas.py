from __future__ import annotations

import datetime
from enum import Enum
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field, field_validator

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

class ShapFactor(BaseModel):
    feature_name: str
    display_name: str
    feature_value: float
    shap_value: float
    impact_direction: str
    absolute_magnitude: float

class FlightPredictionRequest(BaseModel):
    flight_number: str = Field(..., description="Flight number, e.g. AA100 or 6E501")
    carrier_code: str = Field(..., description="IATA 2-letter airline code, e.g. AA, DL, WN, 6E, AI, UK")
    origin_airport: str = Field(..., description="IATA 3-letter origin airport code, e.g. ORD, DEL, HYD")
    dest_airport: str = Field(..., description="IATA 3-letter destination airport code, e.g. DFW, BOM, BLR")
    scheduled_dep_time: str = Field(..., description="Scheduled departure time in HH:MM format, e.g. '14:30'")
    scheduled_arr_time: Optional[str] = Field(None, description="Scheduled arrival time in HH:MM format, e.g. '17:15'")
    departure_date: Optional[str] = Field(None, description="Departure date in YYYY-MM-DD format")
    day_of_week: Optional[int] = Field(None, ge=1, le=7, description="Day of week (1=Monday .. 7=Sunday)")
    day_of_month: Optional[int] = Field(None, ge=1, le=31, description="Day of month (1-31)")
    month: Optional[int] = Field(None, ge=1, le=12, description="Month of year (1-12)")
    distance_miles: Optional[float] = Field(None, ge=0, description="Great circle flight distance in miles")
    scheduled_elapsed_minutes: Optional[float] = Field(None, ge=0, description="Scheduled flight duration in minutes")
    temperature_c: Optional[float] = Field(None, description="Surface temperature in Celsius")
    wind_speed_kmh: Optional[float] = Field(None, description="Surface wind speed in km/h")
    visibility_km: Optional[float] = Field(None, description="Visibility in kilometers")
    precipitation_mm: Optional[float] = Field(None, description="Precipitation rate in mm/h")
    is_thunderstorm: Optional[int] = Field(0, description="1 if thunderstorm active, 0 otherwise")

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
    disruption_probability: Optional[float] = Field(
        None, description="Calibrated composite probability of flight cancellation or delay >= 45m [0.0 - 1.0]"
    )
    cancellation_probability: Optional[float] = Field(
        None, description="Calibrated probability of flight cancellation [0.0 - 1.0]"
    )
    delay_120m_probability: Optional[float] = Field(
        None, description="Calibrated probability of flight arrival delay >= 120m [0.0 - 1.0]"
    )
    expected_delay_minutes: Optional[float] = Field(
        None, description="Predicted arrival delay in minutes (P50 Median)"
    )
    delay_p10_minutes: Optional[float] = Field(None, description="10th percentile expected delay (optimistic bound)")
    delay_p90_minutes: Optional[float] = Field(None, description="90th percentile expected delay (pessimistic bound)")
    disruption_risk_level: RiskLevel = RiskLevel.UNKNOWN
    risk_factors: List[str] = Field(default_factory=list)
    top_contributing_factors: List[ShapFactor] = Field(default_factory=list)
    weather_hazards: List[str] = Field(default_factory=list)
    is_experimental: bool = True
    is_duration_imputed: bool = False
    is_distance_imputed: bool = False
    disclaimer: str = (
        "Statistical advisory only. Human supervisor authorization is strictly required "
        "before booking modifications or passenger rebooking execution."
    )
    model_version: str
    inference_latency_ms: float

# Delay Forecasting Models
class DelayForecastRequest(BaseModel):
    flight_number: str
    carrier_code: str
    origin_airport: str
    dest_airport: str
    scheduled_dep_time: str
    scheduled_arr_time: Optional[str] = None
    departure_date: Optional[str] = None
    operational_horizon: str = Field("T_MINUS_2H", description="T_MINUS_24H, T_MINUS_6H, T_MINUS_3H, T_MINUS_1H")
    temperature_c: Optional[float] = None
    wind_speed_kmh: Optional[float] = None
    visibility_km: Optional[float] = None
    precipitation_mm: Optional[float] = None

class DelayForecastResponse(BaseModel):
    flight_number: str
    operational_horizon: str
    expected_delay_minutes: float
    delay_p10_minutes: float
    delay_p90_minutes: float
    prediction_interval_width: float
    delay_risk_tier: str
    horizon_explanation: str
    model_version: str
    latency_ms: float

# Connection Risk Models
class ConnectionRiskRequest(BaseModel):
    airport_code: str
    inbound_flight_number: str
    outbound_flight_number: str
    scheduled_connection_minutes: int
    predicted_inbound_delay_minutes: float
    inbound_terminal: Optional[str] = None
    outbound_terminal: Optional[str] = None
    is_international: bool = False
    has_checked_baggage: bool = True
    passenger_count: int = 1

class ConnectionRiskResponse(BaseModel):
    airport_code: str
    inbound_flight_number: str
    outbound_flight_number: str
    scheduled_connection_minutes: int
    predicted_inbound_delay_minutes: float
    effective_mct_minutes: int
    is_inter_terminal_transfer: bool
    available_window_minutes: float
    connection_margin_minutes: float
    connection_failure_probability: float
    risk_tier: str
    recommended_action: str
    action_description: str
    passenger_count: int
    timestamp_utc: str

# Passenger Impact Models
class PassengerImpactRequest(BaseModel):
    flight_number: str
    carrier_code: str
    origin_airport: str
    dest_airport: str
    disruption_probability: float
    expected_delay_minutes: float
    is_cancelled: bool = False
    aircraft_type: str = "A320"
    load_factor: float = 0.85
    connecting_passenger_ratio: Optional[float] = None
    elite_passenger_ratio: float = 0.12

class PassengerImpactResponse(BaseModel):
    flight_number: str
    carrier_code: str
    origin_airport: str
    dest_airport: str
    aircraft_type: str
    seat_capacity: int
    load_factor: float
    estimated_passengers_total: int
    estimated_connecting_passengers: int
    estimated_point_to_point_passengers: int
    estimated_elite_passengers: int
    vulnerable_connections_count: int
    projected_rebooking_demand: int
    estimated_rescue_workload_hours: float
    impact_severity: str
    cost_exposure_tier: str
    disclaimer: str

# Ingestion & Infrastructure Health
class GroundTruthIngestRequest(BaseModel):
    flight_number: str
    carrier_code: str
    origin_airport: str
    dest_airport: str
    flight_date: str
    is_cancelled: int = Field(..., ge=0, le=1)
    is_delayed_120m: int = Field(..., ge=0, le=1)
    arr_delay_minutes: float
    provenance: str = "BTS_ON_TIME_DATABASE"
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
    service: str = "Flyo Advanced Predictive Intelligence Engine"
    model_version: str
    models_loaded: Dict[str, bool]
    supported_regions: List[str] = Field(default_factory=lambda: ["IN_DOMESTIC", "US_DOMESTIC"])
    uptime_seconds: float

class ModelMetadataResponse(BaseModel):
    model_version: str
    supported_carriers: List[str] = Field(default_factory=list)
    top_hubs: List[str] = Field(default_factory=list)
    feature_count: int
    features: List[str]
    training_period: Optional[str] = None
    validation_period: Optional[str] = None
    evaluation_metrics: Dict[str, Any] = Field(default_factory=dict)
