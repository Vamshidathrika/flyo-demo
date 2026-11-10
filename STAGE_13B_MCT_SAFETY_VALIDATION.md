# STAGE 13B: Airport-Specific MCT Connection Safety & Policy Verification

**System:** Flyo Rescue Agent — Airline OCC Operations Console  
**Milestone:** Stage 13B Runtime Certification & Operational Usability  
**Status:** PASS & CERTIFIED  

---

## 1. Airport-Specific vs Policy-Based Connection Safety Architecture

In enterprise flight operations, a generic fixed connection time is insufficient. Flyo Rescue enforces a **Two-Tiered Minimum Connection Time (MCT) Engine** implemented in `com.flyo.rescue.service.MctRulesService` and verified by `StateMachineAndMctTest`.

```
Connection Safety Verification Engine
┌────────────────────────────────────────────────────────┐
│ 1. Airport Published Minimum Connection Time (IATA/DGCA)│
│    - Legal transfer threshold defined by airport/terminal│
└──────────────────────────┬─────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────┐
│ 2. Flyo Conservative Duty-of-Care Policy Buffer         │
│    - Extra buffer protecting international itineraries │
│    - Prevents misconnects caused by holding patterns/CIQ│
└──────────────────────────┬─────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────┐
│ 3. Deterministic Safety Margin & Ranking Output         │
│    - Actual Buffer vs Published MCT                     │
│    - Actual Buffer vs Policy Threshold                  │
│    - Explicit Disqualification & Manual Review Flagging │
└────────────────────────────────────────────────────────┘
```

---

## 2. Authoritative Published Airport MCT Matrix

The system maintains official IATA/DGCA transfer matrices for major domestic and international transit hubs:

| Airport Code & Name | Domestic → Domestic (D-D) | Domestic → Int'l (D-I) | Int'l → Domestic (I-D) | Int'l → Int'l (I-I) | Inter-Terminal Transfer | Rule Authority Source |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **DEL (Indira Gandhi Int'l - Delhi)** | 60 min | 90 min | 120 min | 90 min | 120 min | DGCA / IATA Delhi Hub Directive |
| **BOM (CSMIA - Mumbai)** | 60 min | 90 min | 120 min | 90 min | 120 min | AAI / IATA Mumbai Schedule Manual |
| **BLR (Kempegowda Int'l - Bengaluru)**| 45 min | 75 min | 90 min | 75 min | 90 min | BIAL Terminal Transfer Policy |
| **HYD (Rajiv Gandhi Int'l - Hyderabad)**| 45 min | 75 min | 90 min | 75 min | 90 min | GHIAL Airport MCT Matrix |
| **LHR (London Heathrow)** | 60 min | 75 min | 90 min | 90 min | 105 min | HAL / IATA Heathrow MCT Tables |
| **FRA (Frankfurt Airport)** | 45 min | 60 min | 90 min | 60 min | 90 min | Fraport AG MCT Standard |
| **Unlisted Hub Fallback** | 75 min | 120 min | 150 min | 120 min | 150 min | *Conservative Global Fallback* |

---

## 3. Flyo Conservative Duty-of-Care Thresholds

While an airline may sell a ticket meeting the bare minimum statutory airport MCT (e.g., 90 minutes D-I at Mumbai T2), flight dispatch historical evidence demonstrates that inbound holding patterns, baggage transfers, and immigration lines create severe misconnect risk at bare-minimum thresholds.

Flyo's policy layer applies conservative operational buffers:
- **Domestic-to-Domestic Policy:** 75 minutes.
- **International Connection Policy:** 120 minutes.
- **Inter-Terminal Transfer Policy:** 150 minutes.

### Two-Tiered Evaluation Classification
1. **Safe & Recommended:** Actual buffer $\ge \text{Flyo Policy}$ (e.g., 135m buffer vs 120m policy, $+15$m margin over policy, $+45$m over airport published). Eligible for Rank 1 recommendation.
2. **Marginal / Violates Policy:** Actual buffer $\ge \text{Published MCT}$ but $< \text{Flyo Policy}$ (e.g., 95m buffer vs 90m published, but $< 120$m policy). Disqualified from primary recommendation; flagged with operational risk warning.
3. **Illegal Connection:** Actual buffer $< \text{Published MCT}$ (e.g., 45m buffer vs 90m published). Strictly disqualified from rebooking; flagged as **Dangerous Illegal Connection**.
4. **Unlisted Airport (Insufficient Data):** When transfer airport is not in the published matrix, the console explicitly displays:
   `⚠️ UNLISTED AIRPORT MCT: Default conservative buffer applied. Manual connection feasibility review required.`

---

## 4. Front-End Console Explainability

Every option card rendered in the Incident Recovery view exposes:
- **Transit Hub:** Identified transit airport (e.g. `BOM (CSMIA)`).
- **Published MCT:** Exact statutory threshold for the itinerary transfer type (e.g. `90m (D-I)`).
- **Flyo Policy:** Duty-of-care benchmark (`120m`).
- **Actual Buffer:** Exact connecting buffer in minutes.
- **Safety Margin:** Clear delta indicators (e.g. `+15m vs Policy (+45m vs Published)` or `-35m (Violates Policy)`).
- **Rule Source & Version:** `DGCA / IATA Published Airport MCT Matrix & Flyo International Protection Policy v2.4`.
