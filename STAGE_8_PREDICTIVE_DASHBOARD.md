# FLYO STAGE 8 — PHASE 10: PREDICTIVE OPERATIONS DASHBOARD & MONITORING

## 1. Executive Summary

Phase 10 upgrades the Flyo Operations Control Center and ML Observability UI to surface the full suite of Stage 8 predictive intelligence capabilities:
1. **Flight Disruption Risk Leaderboard**: Prioritizes operational flights by calibrated disruption probability and estimated affected passenger counts.
2. **Regional Airport Disruption & Weather Heatmap**: Real-time visual monitoring across the 6 Indian priority hubs (`DEL`, `BOM`, `BLR`, `HYD`, `MAA`, `CCU`) and 4 international gateways (`ORD`, `ATL`, `DFW`, `SFO`).
3. **Passenger Connection-Risk & MCT Queue**: Airport-specific Minimum Connecting Time (MCT) buffers, connection margins, transfer failure probabilities, and automated operational recommendations (`PROACTIVE_REBOOKING_HOLD`, `EXPEDITED_GATE_ESCORT`, `STANDARD_MONITORING`).
4. **Interactive Predictive Engine Workbench**: Supports Indian priority flight presets (`6E-501`, `AI-839`, `UK-927`) and renders TreeSHAP feature attributions, quantile delay intervals (`[P10 - P90]`), and weather hazard badges.

---

## 2. UI Component Architecture

```
+----------------------------------------------------------------------------------------------------+
|                                    Operations Control Center                                      |
|                                                                                                    |
|  +----------------------------------------------------------------------------------------------+  |
|  | Flight Risk Leaderboard                                                                      |  |
|  | Flight   Route    Dep    Disrupt %   Delay [P10-P90]  Tier      Pax   Primary SHAP Factor    |  |
|  | 6E-501   DEL-BOM  07:15    39%       [18m - 62m]      ELEVATED  128   ▲ Delhi Winter Fog     |  |
|  | AI-839   HYD-DEL  18:30    31%       [12m - 50m]      ELEVATED   94   ▲ Thunderstorm Active  |  |
|  | UK-927   BOM-BLR  19:45    26%       [10m - 42m]      MODERATE   72   ▲ Mumbai Monsoon Gusts |  |
|  +----------------------------------------------------------------------------------------------+  |
|                                                                                                    |
|  +----------------------------------------------------------------------------------------------+  |
|  | Regional Disruption & Congestion Heatmap                                                     |  |
|  | [ DEL 🇮🇳 ] 28% Disrupted &middot; CAT-III Fog     [ BOM 🇮🇳 ] 24% Disrupted &middot; Gusts   |  |
|  | [ BLR 🇮🇳 ] 12% Disrupted &middot; Clear           [ HYD 🇮🇳 ]  9% Disrupted &middot; Nominal |  |
|  | [ MAA 🇮🇳 ] 14% Disrupted &middot; Light Rain     [ CCU 🇮🇳 ] 16% Disrupted &middot; Haze    |  |
|  +----------------------------------------------------------------------------------------------+  |
|                                                                                                    |
|  +----------------------------------------------------------------------------------------------+  |
|  | Passenger Connection-Risk & Minimum Connecting Time (MCT) Desk                              |  |
|  | Hub    Inbound / Outbound    Window   MCT   Margin   Fail %    Recommended Action            |  |
|  | BOM    AI-839 -> AI-101       70m     90m   -20m      76%     PROACTIVE_REBOOKING_HOLD       |  |
|  | DEL    6E-501 -> 6E-204       55m     45m   +10m      38%     EXPEDITED_GATE_ESCORT          |  |
|  +----------------------------------------------------------------------------------------------+  |
+----------------------------------------------------------------------------------------------------+
```

---

## 3. Human Operator Governance

All predictive dashboard components enforce Flyo's fundamental governance contract:
- **Advisory Signals Only**: The UI clearly denotes all probabilities and recommendations as statistical advisories.
- **Safety Safeguard**: Autonomous rebooking or booking cancellations remain prohibited.
- **Explainability**: Top SHAP factors provide operators with transparent causal rationale for why a flight is prioritized.
