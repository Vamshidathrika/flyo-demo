# Flyo Predictive Intelligence — Inference API Contract

**Protocol**: REST / JSON over HTTP/1.1  
**Default Base URL**: `http://localhost:8000`  
**API Version**: `v2.0.0-multiseason`  

---

## 1. Status Codes & Enums

### Prediction Status (`status`)
| Status Code | Description | Probabilities Populated? |
| :--- | :--- | :--- |
| `PREDICTION_AVAILABLE` | Flight successfully evaluated against trained multi-season ML models. | Yes ($[0.0 - 1.0]$) |
| `UNSUPPORTED_AIRSPACE` | Flight involves Indian domestic airspace or non-US international routes. | No (`null`) |
| `INSUFFICIENT_COVERAGE` | Airline carrier is not one of the 15 US reporting carriers with empirical training priors. | No (`null`) |
| `MODEL_UNAVAILABLE` | Service is offline, degraded, or model artifacts are missing. | No (`null`) |
| `INVALID_FEATURE_INPUT` | Input features failed validation or transformation. | No (`null`) |

### Disruption Risk Level (`disruption_risk_level`)
- `LOW`: Baseline operational reliability ($P_{\text{cancel}} < 0.04$, $P_{\text{delay120}} < 0.08$, $\text{ExpDelay} < 30\text{m}$).
- `MODERATE`: Moderate disruption risk ($P_{\text{cancel}} \ge 0.04$ or $P_{\text{delay120}} \ge 0.08$ or $\text{ExpDelay} \ge 30\text{m}$).
- `ELEVATED`: Elevated disruption risk ($P_{\text{cancel}} \ge 0.08$ or $P_{\text{delay120}} \ge 0.15$ or $\text{ExpDelay} \ge 60\text{m}$).
- `CRITICAL`: Severe disruption risk ($P_{\text{cancel}} \ge 0.15$ or $P_{\text{delay120}} \ge 0.25$).
- `UNKNOWN`: Assigned when prediction cannot be produced (`UNSUPPORTED_AIRSPACE`, `INSUFFICIENT_COVERAGE`, etc.).

---

## 2. Endpoint Specifications

### 2.1 Single Flight Prediction: `POST /predict/flight`

#### Request Schema
```json
{
  "flight_number": "AA100",
  "carrier_code": "AA",
  "origin_airport": "ORD",
  "dest_airport": "DFW",
  "scheduled_dep_time": "14:30",
  "scheduled_arr_time": "17:15",
  "departure_date": "2024-07-15",
  "day_of_week": 1,
  "day_of_month": 15,
  "month": 7,
  "distance_miles": 802.0,
  "scheduled_elapsed_minutes": 165.0
}
```

| Field | Type | Required? | Description |
| :--- | :--- | :--- | :--- |
| `flight_number` | `string` | **Yes** | Flight identifier (e.g. `"AA100"`) |
| `carrier_code` | `string` | **Yes** | IATA 2-letter carrier code (e.g. `"AA"`, `"DL"`, `"WN"`) |
| `origin_airport` | `string` | **Yes** | IATA 3-letter origin code (e.g. `"ORD"`) |
| `dest_airport` | `string` | **Yes** | IATA 3-letter destination code (e.g. `"DFW"`) |
| `scheduled_dep_time` | `string` | **Yes** | Time in `"HH:MM"` format (e.g. `"14:30"`) |
| `scheduled_arr_time` | `string` | Optional | Time in `"HH:MM"` format (e.g. `"17:15"`) |
| `departure_date` | `string` | Optional | ISO Date `"YYYY-MM-DD"` |
| `day_of_week` | `integer` | Optional | $1 = \text{Monday} \dots 7 = \text{Sunday}$ |
| `day_of_month` | `integer` | Optional | $1 \dots 31$ |
| `month` | `integer` | Optional | $1 \dots 12$ |
| `distance_miles` | `float` | Optional | Flight distance in statute miles |
| `scheduled_elapsed_minutes` | `float` | Optional | Scheduled gate-to-gate duration |

#### Example 1: Valid US Domestic Flight Response
```json
{
  "flight_number": "AA100",
  "carrier_code": "AA",
  "origin_airport": "ORD",
  "dest_airport": "DFW",
  "status": "PREDICTION_AVAILABLE",
  "status_reason": "Disruption probabilities and expected delay calculated from v2 multi-season models.",
  "cancellation_probability": 0.0077,
  "delay_120m_probability": 0.0201,
  "expected_delay_minutes": 33.7,
  "disruption_risk_level": "MODERATE",
  "risk_factors": [
    "ORIGIN_CONGESTION_HUB: ORD is a high-volume hub subject to ground delay programs.",
    "DESTINATION_CONGESTION_HUB: DFW is a high-volume arrival hub.",
    "PROTRACTED_ARRIVAL_DELAY: Model projects expected arrival delay of 34 minutes.",
    "CONNECTION_DISCLAIMER: Extreme delay >=120m is a single-flight arrival delay target; it does not replace connection feasibility evaluation."
  ],
  "is_experimental": true,
  "disclaimer": "Experimental intelligence only. Extreme delay >=120m is a flight delay classification target, never a missed connection probability. Human operator approval is strictly required before rebooking.",
  "model_version": "v2.0.0-multiseason",
  "inference_latency_ms": 5.2
}
```

#### Example 2: Indian Domestic Route Response (`UNSUPPORTED_AIRSPACE`)
```json
{
  "flight_number": "6E501",
  "carrier_code": "6E",
  "origin_airport": "DEL",
  "dest_airport": "BOM",
  "status": "UNSUPPORTED_AIRSPACE",
  "status_reason": "Flight 6E501 (DEL -> BOM) operates in Indian airspace. Flyo v2 ML disruption models were trained exclusively on US domestic flights (BTS). Indian domestic airspace is currently unsupported for statistical ML inference.",
  "cancellation_probability": null,
  "delay_120m_probability": null,
  "expected_delay_minutes": null,
  "disruption_risk_level": "UNKNOWN",
  "risk_factors": [
    "UNSUPPORTED_AIRSPACE: Indian domestic airspace is outside the US BTS training corpus.",
    "SAFETY_DIRECTIVE: Predictive ML is disabled for this route. Rely exclusively on live flight radar and operator review."
  ],
  "is_experimental": true,
  "disclaimer": "Experimental intelligence only. Extreme delay >=120m is a flight delay classification target, never a missed connection probability. Human operator approval is strictly required before rebooking.",
  "model_version": "v2.0.0-multiseason",
  "inference_latency_ms": 0.4
}
```

#### Example 3: Unsupported Carrier Response (`INSUFFICIENT_COVERAGE`)
```json
{
  "flight_number": "LH400",
  "carrier_code": "LH",
  "origin_airport": "JFK",
  "dest_airport": "FRA",
  "status": "INSUFFICIENT_COVERAGE",
  "status_reason": "Carrier 'LH' is not among the 15 US reporting carriers represented in the multi-season training corpus.",
  "cancellation_probability": null,
  "delay_120m_probability": null,
  "expected_delay_minutes": null,
  "disruption_risk_level": "UNKNOWN",
  "risk_factors": [
    "INSUFFICIENT_COVERAGE: Carrier 'LH' lacks empirical training priors in the v2 dataset."
  ],
  "is_experimental": true,
  "disclaimer": "Experimental intelligence only. Extreme delay >=120m is a flight delay classification target, never a missed connection probability. Human operator approval is strictly required before rebooking.",
  "model_version": "v2.0.0-multiseason",
  "inference_latency_ms": 0.3
}
```

---

### 2.2 Batch Prediction: `POST /predict/batch`

Accepts `{ "flights": [ ... ] }` (1 to 100 flights) and returns `{ "results": [ ... ], "total_flights": N, "successful_predictions": M, "total_latency_ms": T }`.

---

### 2.3 Health Endpoints

- `GET /health/live`:
  ```json
  { "status": "alive", "service": "flyo-ml-inference" }
  ```
- `GET /health/ready`:
  ```json
  {
    "status": "HEALTHY",
    "service": "Flyo Predictive Intelligence",
    "model_version": "v2.0.0-multiseason",
    "models_loaded": {
      "preprocessor": true,
      "cancellation_model": true,
      "delay120_model": true,
      "delay_regressor": true
    },
    "uptime_seconds": 124.5
  }
  ```
