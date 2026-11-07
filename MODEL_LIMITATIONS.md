# Flyo Predictive Intelligence v1 — Model Limitations & Operational Boundaries

> **Document Type:** Operational Governance, Model Risk Management & Limitations Specification  
> **Applies to:** Flyo Machine Learning Disruption Forecasting v1 (`ml/artifacts/`)  
> **Audience:** Aviation Automation Architects, Operations Leads & Machine Learning Engineers

---

## 1. Geographic & Airspace Boundaries (US Domestic Only)

> [!CAUTION]
> **Models Trained on US BTS Data CANNOT be Used to Predict Indian Domestic Flights.**
> Under no circumstances should the trained models (`xgb_cancellation_v1.json`, `calibrated_cancellation_v1.joblib`, `xgb_delay120_v1.json`, `xgb_delay_regressor_v1.json`) be used to generate disruption predictions for flights departing from or arriving at Indian airports (e.g., DEL, BOM, BLR, HYD, MAA, CCU).

### Technical & Operational Reasons:
1. **Airspace & Regulatory Divergence:** US flight operations are governed by FAA National Airspace System (NAS) ground delay programs, FAA slot controls, and Part 121 crew rest regulations. Indian flight operations are governed by DGCA regulations, Ministry of Civil Aviation guidelines, and Airport Authority of India (AAI) air traffic procedures.
2. **Fleet Utilization & Turnaround Characteristics:** Indian domestic carriers (e.g., IndiGo, Air India, SpiceJet) maintain different turnaround time margins, aircraft block utilization schedules, and route architectures compared to US domestic carriers (e.g., Southwest, Delta, American).
3. **Airport Infrastructure Disparities:** Major US transfer hubs (e.g., ORD, DEN, DFW) feature multiple parallel independent runways and CAT III-B/C instrument landing systems designed for heavy blizzard operations. Hubs like DEL experience severe radiation fog (CAT III low-visibility procedures) with entirely distinct seasonal patterns.
4. **Data Training Representation:** 100% of training records originate from the US DOT Bureau of Transportation Statistics. The models have zero empirical representation of Indian carriers (`6E`, `AI`, `SG`, `UK`) or Indian airport codes.

---

## 2. Seasonal & Meteorological Boundaries

### 2.1 Winter-Only Dynamics
- **Training Period:** January 1 – January 20, 2024.
- **Meteorological Context:** Deep winter conditions across North America characterized by sub-zero Arctic vortex air masses, freezing rain, blizzard runway snow removal cycles, and aircraft de-icing holds.
- **Generalization Failure in Summer/Monsoon:** The model has zero exposure to:
  - High-temperature runway density altitude payload restrictions (summer desert operations).
  - Severe convective supercell squall lines and microbursts (spring Midwest storms).
  - Atlantic hurricane track diversions.
  - Indian Southwest and Northeast monsoon rainfall deluges.
- **Required Action:** Continuous retraining across a multi-season 12-month historical window is mandatory before deploying models into production year-round.

---

## 3. Prediction Horizon Boundaries ($T_{\text{predict}} = \text{Dep } - 2\text{ hours}$)

Flyo's feature extraction boundary enforces $T_{\text{predict}} = \text{Scheduled Departure } - 2\text{ hours}$. This creates unavoidable operational blind spots:

```
T - 2 Hours (Prediction Horizon)                      Scheduled Departure
───────┬───────────────────────────────────────────────────────┬────────►
       │                                                       │
       ▼                                                       ▼
 Observable Inputs:                                      Unobservable Blindspots:
 • Scheduled route & timing                              • Late inbound mechanical failure
 • 2h prior METAR / weather forecast                     • Crew duty time expiration at gate
 • Station prior disruption rates                        • Ramp pushback tractor collision
 • Scheduled turn buffer                                 • ATC ground stop issued at T - 20m
```

### Irreducible Stochastic Variance:
1. **Mechanical Failures:** Unscheduled component failures (e.g., auxiliary power unit faults, avionics warnings) that occur during pre-flight checks 30–45 minutes prior to departure are mathematically impossible to forecast from 2-hour-old schedule and weather variables.
2. **Late-Notice Ground Stops:** FAA / ATC Air Traffic Control System Command Center (ATCSCC) ground delay programs issued dynamically due to sudden runway obstructions or unforecasted wind shifts cannot be predicted hours in advance.

---

## 4. Operational Gating & Separation of Concerns

> [!CRITICAL]
> **Deterministic Ground Truth vs Machine Learning Disruption Risk**
> An ML disruption probability must NEVER be treated as ground truth or used to unilaterally execute rescue operations.

### Strict Architectural Separation:

| Capability | Platform Layer | Authority | Permitted Action |
| :--- | :--- | :--- | :--- |
| **Early Warning Risk Flagging** | Python ML Inference Service | Probabilistic ($P \in [0.0, 1.0]$) | Display warning badge on Operator Dashboard; notify human operator to monitor flight. |
| **Disruption Confirmation** | Flyo Evidence Reconciliation Service | Deterministic (Multi-Source Agreement) | Confirm flight state as `CANCELLED` or `DELAYED` via radar + airline API. |
| **Alternative Flight Discovery** | Flyo Search & Aggregator Engine | Deterministic API Query | Generate feasible alternative itineraries meeting 120-minute minimum buffer. |
| **Option Re-ranking** | Flyo Deterministic Ranking Engine | Mathematical Scoring Algorithm | Rank candidate flights by risk, cost, and connection feasibility. |
| **Passenger Notification & Authorization** | Passenger WhatsApp Service | Verified Cryptographic OTP | Request explicit passenger authorization code. |
| **Virtual Card Issuance & Booking Execution** | Sandbox Payment Adapter + Playwright Worker | Mandatory Human-in-the-Loop Operator Approval Gate | Issue VCC and execute live browser booking only AFTER operator approval. |

### Prohibited Behaviors:
- **NO Autonomous Rebooking on ML Scores:** The system will NEVER trigger Playwright rebooking workflows, purchase tickets, or issue Virtual Credit Cards solely because an ML model predicted a high disruption probability ($P_{\text{cancel}} > 0.80$).
- **NO Hallucinated Confidence:** Confidence scores displayed in the Flyo Operations Console must reflect empirical evidence reconciliation and calibrated probabilities, never arbitrary hardcoded multipliers.
- **NO Replacement of the 120-Minute Safety Buffer:** ML predictions do not relax or replace the mandatory 120-minute connection feasibility threshold.

---

## 5. Ongoing Monitoring & Model Governance

Before Flyo Predictive Intelligence can be promoted to live flight networks:
1. **Concept Drift & Performance Tracking:** Monthly computation of Population Stability Index (PSI) and Brier Score tracking against live radar outcomes.
2. **Regional Adaptation:** Acquisition of local civil aviation datasets (e.g., DGCA historical records for India, Eurocontrol for Europe) with localized feature engineering before regional launch.
3. **Fallback Graceful Degradation:** When weather signals or carrier priors are unavailable, the inference service must transparently report `is_weather_available=0` and default to empirical station priors rather than silent imputation.
