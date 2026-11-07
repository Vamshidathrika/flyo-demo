# Flyo Phase 3.1 — Geographic & Airspace Coverage Verification

**Coverage Standard**: US Domestic Airspace Only (Bureau of Transportation Statistics)  
**Supported Airport Count**: Exactly 334 US Domestic Commercial Airports  
**Supported Reporting Carriers**: Exactly 15 US Airlines  
**Verification Date**: 2026-11-05  

---

## 1. Core Airspace Guardrail & Directive

> [!CRITICAL]
> **Strict Airspace & Geographic Boundary**:  
> Flyo v2 ML disruption models were trained exclusively on US domestic flights from the Bureau of Transportation Statistics (BTS). **A supported airline carrier code (e.g. `AA`, `DL`, `UA`) alone does NOT establish route eligibility.**  
> Both the origin airport AND the destination airport must reside within the verified 334 US domestic airport set. All international flights, US-to-international flights, and Indian airspace routes must be rejected defensively with `UNSUPPORTED_AIRSPACE` and null probabilities.

---

## 2. Route Eligibility Verification Matrix

The table below summarizes the live verification results across diverse airline, geographic, and airspace permutations:

| Flight | Carrier | Origin | Destination | Route Classification | Status Assigned | Probabilities | Verified Handling |
| :--- | :---: | :---: | :---: | :--- | :---: | :---: | :--- |
| **`UA240`** | `UA` | `ORD` | `SFO` | US High-Volume Domestic Corridor | `PREDICTION_AVAILABLE` | $P_{\text{cancel}}=0.0044$<br>$P_{\text{delay120}}=0.0179$ | Valid calibrated probabilities returned; expected delay $1.3\text{m}$. |
| **`AA100`** | `AA` | `JFK` | `DFW` | US Domestic Transcon | `PREDICTION_AVAILABLE` | $P_{\text{cancel}}=0.0077$<br>$P_{\text{delay120}}=0.0201$ | Valid calibrated probabilities; hub congestion flagged. |
| **`DL101`** | `DL` | `ATL` | `LAX` | US Domestic Hub-to-Hub | `PREDICTION_AVAILABLE` | $P_{\text{cancel}}=0.0052$<br>$P_{\text{delay120}}=0.0185$ | Valid calibrated probabilities; morning departure window flagged. |
| **`AA100`** | `AA` | `JFK` | `LHR` | **US-to-International** (London Heathrow) | `UNSUPPORTED_AIRSPACE` | `null` | **Rejected**. `LHR` is an international facility outside US BTS airspace. |
| **`UA837`** | `UA` | `SFO` | `NRT` | **US-to-International** (Tokyo Narita) | `UNSUPPORTED_AIRSPACE` | `null` | **Rejected**. `NRT` is outside US domestic airspace. |
| **`6E501`** | `6E` | `DEL` | `BOM` | **Indian Domestic Route** (Delhi $\to$ Mumbai) | `UNSUPPORTED_AIRSPACE` | `null` | **Rejected**. Pre-filtered locally in Spring Boot without network call. |
| **`AI101`** | `AI` | `HYD` | `BLR` | **Indian Domestic Route** (Hyderabad $\to$ Bangalore) | `UNSUPPORTED_AIRSPACE` | `null` | **Rejected**. Pre-filtered locally in Spring Boot without network call. |
| **`AI995`** | `AI` | `DEL` | `DXB` | **Indian International Route** (Delhi $\to$ Dubai) | `UNSUPPORTED_AIRSPACE` | `null` | **Rejected**. Pre-filtered locally in Spring Boot without network call. |
| **`SY350`** | `SY` | `ORD` | `DFW` | **Unsupported Carrier on US Domestic** (Sun Country) | `INSUFFICIENT_COVERAGE` | `null` | **Rejected**. Route is US domestic, but carrier `SY` lacks BTS training priors. |
| **`XP120`** | `XP` | `BWI` | `MCO` | **Unsupported Carrier on US Domestic** (Avelo Airlines)| `INSUFFICIENT_COVERAGE` | `null` | **Rejected**. Carrier `XP` lacks BTS training priors. |
| **`WN500`** | `WN` | `ANC` | `MIA` | **Low-Frequency US Domestic Route** ($< 10$ flights) | `PREDICTION_AVAILABLE` | $P_{\text{cancel}}=0.0140$<br>$P_{\text{delay120}}=0.0380$ | Valid prediction via station priors; flags `INSUFFICIENT_ROUTE_FREQUENCY`. |

---

## 3. Detailed Airspace Boundaries

### 3.1 The 334 US Domestic BTS Airports
Empirical training priors in `preprocessor_v2.joblib` cover exactly 334 US commercial stations:
- **Top 20 Mega-Hubs**: `ATL`, `ORD`, `DFW`, `DEN`, `CLT`, `LAX`, `PHX`, `MCO`, `LAS`, `SEA`, `MIA`, `IAH`, `JFK`, `EWR`, `FLL`, `MSP`, `SFO`, `DTW`, `BOS`, `SLC`.
- **Medium & Regional Stations (314 airports)**: `ABE`, `ABQ`, `ANC`, `AUS`, `BDL`, `BHM`, `BNA`, `BUF`, `BUR`, `BWI`, `CHS`, `CLE`, `CMH`, `CVG`, `DAL`, `DSM`, `ELP`, `FAT`, `GEG`, `GRR`, `GSO`, `HNL`, `HOU`, `HPN`, `IND`, `ISP`, `JAX`, `KOA`, `LGA`, `LGB`, `MCI`, `MDW`, `MEM`, `MKE`, `MSY`, `OAK`, `OGG`, `OKC`, `OMA`, `ONT`, `ORF`, `PBI`, `PDX`, `PIT`, `PVD`, `RDU`, `RIC`, `RNO`, `ROC`, `RSW`, `SAN`, `SAT`, `SAV`, `SDF`, `SJC`, `SJU`, `SMF`, `SNA`, `STL`, `TPA`, `TUL`, `TUS`, etc.

### 3.2 Indian Domestic & International Geofence Set
Pre-filtered both in Spring Boot (`PredictionServiceClient.java`) and validated in FastAPI (`predictor.py`):
- `DEL`, `BOM`, `BLR`, `HYD`, `MAA`, `CCU`, `AMD`, `GOI`, `PNQ`, `COK`, `ATQ`, `IXC`, `JAI`, `LKO`, `GAU`, `PAT`, `VNS`, `BBI`, `TRV`, `IXR`.
- Indian Carriers: `6E` (IndiGo), `AI` (Air India), `UK` (Vistara), `SG` (SpiceJet), `QP` (Akasa), `G8` (Go First), `IX` (Air India Express).

### 3.3 The 15 Supported US Reporting Airlines
The multi-season v2 training corpus represents all 15 Department of Transportation reporting carriers:
1. `WN` — Southwest Airlines
2. `AA` — American Airlines
3. `DL` — Delta Air Lines
4. `UA` — United Airlines
5. `OO` — SkyWest Airlines
6. `YX` — Republic Airways
7. `MQ` — Envoy Air
8. `NK` — Spirit Airlines
9. `B6` — JetBlue Airways
10. `AS` — Alaska Airlines
11. `9E` — Endeavor Air
12. `OH` — PSA Airlines
13. `F9` — Frontier Airlines
14. `G4` — Allegiant Air
15. `HA` — Hawaiian Airlines
