# STAGE 10: REAL-TIME OPERATIONAL DATA INTEGRATION & TELEMETRY
**Flyo Disruption Management Platform**  
**Engineering Specification:** Multi-Source Telemetry, Freshness Tracking & Weather Fusion

---

## 1. Operational Ingestion Surface

Flyo synthesizes four distinct live data streams to detect disruptions, assess flight feasibility, and forecast passenger connection risks:

```
┌─────────────────────────────────┐      ┌─────────────────────────────────┐
│   1. Aviation Radar Telemetry   │      │    2. Open-Meteo Weather API    │
│      FlightAware / Aviationstack│      │       METAR / TAF / Visibility │
└────────────────┬────────────────┘      └────────────────┬────────────────┘
                 │                                        │
                 ▼                                        ▼
         ┌────────────────────────────────────────────────────────┐
         │       Flyo Evidence Reconciliation Service             │
         │       Source, Timestamp & Freshness Metadata Tracker   │
         └────────────────────────┬───────────────────────────────┘
                                  │
                 ┌────────────────┴────────────────┐
                 ▼                                 ▼
┌─────────────────────────────────┐      ┌─────────────────────────────────┐
│   3. Aviation News Feed         │      │    4. ML Prediction Engine      │
│      DGCA / NOTAM / ATFM RSS    │      │       XGBoost (ROC-AUC 0.7307)  │
└─────────────────────────────────┘      └─────────────────────────────────┘
```

---

## 2. Ingestion Source Audit

| Stream Name | Live External Provider | Ingestion Class | Freshness Metadata | Fail-Closed Policy in Prod |
| :--- | :--- | :--- | :--- | :--- |
| **Flight Status** | Aviationstack / FlightAware | `AviationRadarClient` | `freshnessSeconds`, `timestamp` | Returns empty status; never fabricates fake telemetry. |
| **Airport Weather** | Open-Meteo Global Surface API | `OpenMeteoWeatherService` | Live temperature, visibility (m), conditions | Direct free HTTPS API; live in both test and prod. |
| **Aviation News** | DGCA / Civil Aviation Feeds | `AviationNewsIntelligenceService` | Published timestamp, feed hash | Skips silently if connection times out. |
| **Predictive ML** | Flyo Stage 8 ML Engine (FastAPI) | `PredictionServiceClient` | Model version (`v3.0.0-enterprise`), latency ms | Validates airspace coverage; returns `UNSUPPORTED_AIRSPACE` if out of bounds. |

---

## 3. Telemetry Freshness Tracking

Every operational data payload returned to operators or consumers carries authoritative provenance metadata:
```json
{
  "flight": "AI-839",
  "status": "CANCELLED",
  "sourceProvider": "aviationstack",
  "timestamp": "2026-11-08T13:45:00Z",
  "freshnessMinutes": 2.4,
  "isLiveTelemetry": true,
  "weatherContext": {
    "airport": "DEL",
    "visibilityMeters": 150.0,
    "temperatureCelsius": 14.2,
    "condition": "Severe Fog (Cat III LVP)"
  }
}
```

---

## 4. Minimum Connecting Time (MCT) Rules

- **Domestic to Domestic**: Standard MCT = 60 minutes.
- **Domestic to International**: Standard MCT = 90 minutes.
- **International to International**: Standard MCT = 120 minutes.
- **Terminal Transfer Adjustment**: +30 minutes when transferring between Terminal 1/2 and Terminal 3 at Delhi (DEL) or Mumbai (BOM).
