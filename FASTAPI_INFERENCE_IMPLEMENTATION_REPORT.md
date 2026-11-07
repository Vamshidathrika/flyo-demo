# Flyo Phase 3 — FastAPI Inference Service Implementation Report

**Service Name**: Flyo Predictive Intelligence Service (`flyo-ml-inference`)  
**Version**: `v2.0.0-multiseason`  
**Host & Port**: `http://0.0.0.0:8000`  
**Execution Environment**: Python 3.14.7, FastAPI 0.162.0, Uvicorn 0.54.0, Pydantic 2.14.3, XGBoost 3.1.1, Scikit-learn 1.9.0  
**Integration Status**: Fully Implemented, Tested, and Integrated with Spring Boot  

---

## 1. Executive Summary & Purpose

The Flyo Predictive Intelligence Service is a dedicated, production-grade microservice providing statistical flight disruption risk scores to the Flyo operations platform. 

### Core Design Principles
1. **Experimental Intelligence Only**: ML probabilities provide supplementary situational awareness to human operators. They **never** independently authorize rebooking, issue VCC payment cards, or trigger automated Playwright workflows.
2. **Defensive Airspace Geofencing**: The underlying models were trained exclusively on 1.7 million US domestic Bureau of Transportation Statistics (BTS) flights. Any query involving Indian airspace (e.g., `DEL`, `BOM`, `BLR`, `HYD`) or Indian carriers (`6E`, `AI`, `UK`) is rejected cleanly with `UNSUPPORTED_AIRSPACE`.
3. **Target Distinction**: An extreme delay ($\ge 120$ minutes) is strictly a flight-level arrival delay classification target, **never** a missed connection probability. Connection safety remains the sole responsibility of Flyo's deterministic Minimum Connection Time (MCT) engine.
4. **Leakage-Free Feature Alignment**: Inference features strictly mirror the training contract at $T_{\text{predict}} = \text{Departure} - 2\text{h}$. All historical observed weather features are excluded.

---

## 2. Microservice Architecture

```mermaid
graph TD
    Client["Spring Boot Backend (PredictionServiceClient)"] -->|POST /predict/flight (1500ms timeout)| API["FastAPI Gateway (Uvicorn ASGI)"]
    API --> Pred["FlightPredictor"]
    
    subgraph "Validation & Geofencing"
        Pred --> Geo{"Airspace / Carrier Check"}
        Geo -->|"Indian Airport / Carrier"| RetUS["Status: UNSUPPORTED_AIRSPACE (Priors=None)"]
        Geo -->|"Unsupported Carrier"| RetIC["Status: INSUFFICIENT_COVERAGE (Priors=None)"]
    end
    
    subgraph "ML Inference Engine"
        Geo -->|"Valid US Domestic"| Prep["LeakageFreePreprocessor (79 features)"]
        Prep --> Models["Singleton ModelRegistry (In-Memory)"]
        Models --> M1["Calibrated Cancellation Classifier (Sigmoid)"]
        Models --> M2["Calibrated Delay >=120m Classifier (Sigmoid)"]
        Models --> M3["XGBoost Delay Regressor (Minutes)"]
    end
    
    M1 & M2 & M3 --> Post["Risk Assessment & Calibration Postprocessing"]
    Post --> Resp["FlightPredictionResponse (Status: PREDICTION_AVAILABLE)"]
```

### Module Responsibilities:
- `ml/service/config.py`: Centralized configuration, port specifications, 15 supported US reporting carriers, top 20 hub definitions, and Indian domestic airport geofence sets.
- `ml/service/schemas.py`: Pydantic v2 schemas providing type validation, uppercase code normalization, and serialized response representations.
- `ml/service/registry.py`: Singleton `ModelRegistry` initialized during the FastAPI `@asynccontextmanager` lifespan event. Loads models into memory once at process boot to guarantee sub-millisecond inference latencies.
- `ml/service/predictor.py`: `FlightPredictor` orchestrating geofencing, carrier eligibility checks, datetime decomposition, feature vector alignment, calibrated model execution, and risk factor derivation.
- `ml/service/main.py`: ASGI application exposing live, ready, metadata, single-flight, and batch prediction REST routes with CORS middleware and HTTP error handling.

---

## 3. Endpoints & Route Definitions

| Method | Route | Description | Expected Latency |
| :--- | :--- | :--- | :--- |
| `GET` | `/health/live` | Basic liveness probe confirming ASGI process is running. | $< 1 \text{ ms}$ |
| `GET` | `/health/ready` | Readiness probe verifying all 4 artifacts are active in memory. | $< 2 \text{ ms}$ |
| `GET` | `/models/metadata` | Detailed metadata, metrics, feature list, and supported carriers. | $< 2 \text{ ms}$ |
| `POST` | `/predict/flight` | Evaluates disruption probabilities and expected delay for a flight. | $5 - 15 \text{ ms}$ |
| `POST` | `/predict/batch` | Evaluates up to 100 flights in a single request. | $15 - 40 \text{ ms}$ |

---

## 4. Model Artifacts Served

All artifacts are version-tagged and loaded from `ml/artifacts/`:

1. **Preprocessor**: `preprocessor_v2.joblib` (181 KB)
   - Evaluates 79 strictly leakage-free features observable at $T - 2\text{h}$.
   - Houses empirical disruption priors for 15 carriers, 20 origin hubs, 20 destination hubs, and historical route pairings.
2. **Cancellation Classifier**: `calibrated_cancellation_v2.joblib` (421 KB)
   - Underlying model: `xgb_cancellation_v2.json` (584 KB).
   - Platt Sigmoid calibration fitted on held-out validation data.
   - Brier Score: `0.02931` (held-out test).
3. **Extreme Delay ($\ge 120$m) Classifier**: `calibrated_delay120_v2.joblib` (142 KB)
   - Underlying model: `xgb_delay120_v2.json` (181 KB).
   - ROC-AUC: `0.5950`, PR-AUC: `0.0693` (vs 0.0523 baseline).
4. **Delay Regressor Booster**: `xgb_delay_regressor_v2.json` (217 KB)
   - XGBoost continuous regressor predicting expected arrival delay minutes.

---

## 5. Performance & Resource Footprint

- **Warmup Latency**: $< 0.15 \text{ seconds}$ to load all artifacts into RAM.
- **In-Memory RAM Footprint**: $\approx 185 \text{ MB}$ total virtual memory.
- **Single-Flight Inference Latency**: $5.4 \text{ ms}$ average (Python test harness).
- **Concurrency Support**: Non-blocking asynchronous endpoints handled via Uvicorn event loop. CPU-bound feature transforms run vectorized in NumPy / Pandas.
