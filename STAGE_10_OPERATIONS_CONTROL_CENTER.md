# STAGE 10: PRODUCTION OPERATIONS CONTROL CENTER (OCC) WORKFLOW
**Flyo Disruption Management Platform**  
**Interface & Systems Control Manual:** Operator Oversight, Human-in-the-Loop & Audit Desk

---

## 1. Operations Control Center Overview

The Flyo Operations Control Center (OCC) provides operations managers and airline rescue supervisors with real-time visibility into active disruptions, pending cases, provider health, and manual intervention desks.

```
┌────────────────────────────────────────────────────────────────────────┐
│               FLYO ENTERPRISE OPERATIONS CONTROL CENTER                │
├───────────────────┬───────────────────┬───────────────────┬────────────┤
│  Active Cases: 0  │  Manual Desk: 0   │  Failed Sagas: 0  │ Resolved: 0│
├───────────────────┴───────────────────┴───────────────────┴────────────┤
│ [Provider Health Matrix]                                               │
│  - Amadeus Altéa: SANDBOX VERIFIED   - Stripe Issuing: BLOCKED BY CREDS│
│  - Meta WhatsApp: BLOCKED BY CREDS   - Open-Meteo: LIVE CONNECTED      │
├────────────────────────────────────────────────────────────────────────┤
│ [Live Disruption Queue]                                                │
│  - Empty State: "No active disruptions requiring operator intervention"│
├────────────────────────────────────────────────────────────────────────┤
│ [Connection Risk Queue & Passenger Flight Feasibility Desk]             │
│  - Evaluates live onward connections against Minimum Connecting Times  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Core Operator Workflows

### 1. Disruption Inspection & Flight Impact
- Operators can inspect incoming flight alerts with live METAR weather and multi-source evidence confidence scores.
- Displays affected passenger count and lists individual passenger PNRs.

### 2. Rescue Proposal Review & Human-in-the-Loop Approval
- The system presents ranked alternative flights (`O1`, `O2`, `O3`) with clear fare difference calculations (e.g. `+₹4,200`).
- **Approval Policy**:
  - Below Auto-Approval Threshold (e.g. ₹5,000) and within partner airline: Can be automated.
  - Above Threshold or across uncontracted carriers: Requires named operator approval with authenticated JWT.

### 3. Ambiguous Provider Outcome Resolution
- If an airline reservation host or payment gateway times out during checkout, the case transitions to `MANUAL_INTERVENTION_REQUIRED` or `RECONCILING`.
- The operator desk allows one-click status reconciliation (`AmadeusGdsSandboxClient.retrieveFlightOrder`) before any retry is attempted.

### 4. Financial Ledger & Outbox Inspection
- Real-time audit trail of every tokenized VCC issuance, pre-authorization, capture, and void.
- Operators can void unused virtual cards with a single click.
