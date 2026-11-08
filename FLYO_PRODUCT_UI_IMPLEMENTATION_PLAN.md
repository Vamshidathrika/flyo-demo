# Flyo Product UI & Operations Console Implementation Plan

**Document Status:** Approved Engineering Implementation Plan  
**Role:** Principal Product Architect & Senior Frontend Design Engineer  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. UI Audit Findings & The Demo Decommissioning Imperative

The forensic UI/UX audit confirmed that the current frontend in [`flyo-rescue-demo/backend/src/main/resources/static/`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/resources/static/) is an inflexible, hardcoded demonstration script built for an investor pitch, not an airline operations tool:

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                CURRENT UI DEMO ARTIFACT AUDIT                                    │
├───────────────────────┬─────────────────────────────────────────────────┬────────────────────────┤
│ UI Component / View   │ Observed Prototype Code in `app.js`             │ Enterprise Requirement │
├───────────────────────┼─────────────────────────────────────────────────┼────────────────────────┤
│ Passenger Workspace   │ Hardcoded tab name "Ananya" and Case `C1`       │ Dynamic Passenger      │
│                       │ (`const activeId = caseId || "C1"`)             │ Manifest & PNR Tree    │
├───────────────────────┼─────────────────────────────────────────────────┼────────────────────────┤
│ Flight Simulation     │ Static button: `Simulate Cancellation (AI-839)` │ Real-time Inbound Feed │
│ Button                │ bound to mock flight `AI-839`                   │ & Global Flight Search │
├───────────────────────┼─────────────────────────────────────────────────┼────────────────────────┤
│ WhatsApp Interaction  │ Simulated green mockup panel in HTML            │ Real WhatsApp Delivery │
│ Panel                 │ polling synthetic message repository            │ & Two-Way Stream       │
├───────────────────────┼─────────────────────────────────────────────────┼────────────────────────┤
│ Operations Metrics    │ Hardcoded metrics: "82% Rescued", "14 Active"   │ Real-time Aggregated   │
│ Dashboard             │ embedded in static HTML                         │ Disruption KPI Cards   │
├───────────────────────┼─────────────────────────────────────────────────┼────────────────────────┤
│ Rebooking Options     │ Hardcoded static cards (`FL101`, `FL301`)       │ Live Available Seats   │
│ Selection             │ generated from local string matching            │ with Fare & MCT Rules  │
└───────────────────────┴─────────────────────────────────────────────────┴────────────────────────┤
```

> [!CRITICAL]
> **Implementation Sequence Rule:** Do not begin an immediate rewrite of the frontend before stabilizing backend API contracts, JWT authentication, and multi-tenancy. Rebuilding the UI first risks carrying forward old demo assumptions into a new framework.

---

## 2. Target Enterprise Operations Console Architecture

The replacement frontend is an **Airport Operations Control & Disruption Rescue Console** built using **React 18+, TypeScript, Tailwind CSS, and Shadcn UI components**:

```
 ┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
 │                                 FLYO RESCUE DESK OPERATOR CONSOLE                                │
 ├──────────────────────────────────────────────────────────────────────────────────────────────────┤
 │ [Header] Tenant: FCM Travel India | Station: DEL (Indira Gandhi Int'l) | Operator: R. Sharma    │
 ├──────────────────────────────────────────────────────────────────────────────────────────────────┤
 │ [Top Navigation] 1. Disruption Queue (14) | 2. Flight Roster | 3. Manifests | 4. System Health   │
 ├─────────────────────────────────┬────────────────────────────────┬───────────────────────────────┤
 │ LIVE DISRUPTION QUEUE           │ CASE RESCUE WORKSPACE          │ MULTI-SOURCE EVIDENCE PANEL   │
 │ ─ Filter: All Hubs, Severity    │ ─ Passenger: Ananya Sharma     │ ─ Radar Telemetry: Cirium     │
 │ ─ AI-839 (DEL-BOM) CANCELLED    │ ─ PNR: W4X9K2 | Corp: McKinsey │   Flight AI-839 marked CANCEL │
 │   18 Connects | 4 Missed Int'l  │ ─ Sector: HYD -> DEL -> YYZ    │ ─ Destination Weather:        │
 │ ─ 6E-402 (BLR-DEL) DELAY +140m  │ ─ Inbound Delay: +180m         │   DEL: 50m Fog, CAT III active│
 │   8 Tight Connects (<45m)       │ ─ Outward Flight: AC-043 (YYZ) │ ─ Multi-Source Confidence: 98%│
 │ ─ UK-811 (BOM-DEL) DIVERTED     │ ─ Scheduled Buffer: 3h 15m     │   (Confirmed Disruption)      │
 │                                 │ ─ Remaining Buffer: -15m       │                               │
 │                                 │   (MISSED CONNECTION CONFIRMED)│                               │
 ├─────────────────────────────────┼────────────────────────────────┴───────────────────────────────┤
 │ VERIFIED REACCOMMODATION GRID   │ REVENUE & WELFARE COMPLIANCE   │ AUTHORIZED ACTION BAR         │
 │ Rank 1 (Selected):              │ ─ Rebook Penalty: WAIVED (IROP)│ [Reject / Escalate]           │
 │ ─ Route: HYD -> BOM -> DEL      │ ─ Meal Voucher: ISSUED (DGCA)  │ [1-Click Approve Rebook]      │
 │ ─ Carrier: Air India Express    │ ─ Hotel Care: NOT REQUIRED     │  -> Dispatches GDS Exchange   │
 │ ─ Seats Avail: 4 (Class M)      │ ─ Passenger WhatsApp:          │  -> Verifies Confirmed PNR    │
 │ ─ Hub Arrival: 01:40 (7h buffer)│   OTP Verified (14:32:01)      │                               │
 └─────────────────────────────────┴────────────────────────────────┴───────────────────────────────┘
```

---

## 3. Core Enterprise UI Functional Views

### View 1: Secure Operator Login & Station Context Selector
* Authenticates operator via corporate Single Sign-On (OIDC / OAuth 2.0).
* Requires operator to select active operational context: **Organization** (`FCM Travel`, `Air India OCC`) and **Station Hub** (`DEL`, `BOM`, `BLR`, `HYD`, `ALL`).

### View 2: Live Disruption Triage Queue
* Sortable and filterable data grid streaming live flight disruption events.
* Columns: Operational Flight, Origin-Dest, Scheduled Departure, Radar Status, Delay Duration, Affected Passengers Count, Missed Connections Count, Severity Badge (`CRITICAL`, `WARNING`, `MONITOR`).
* Real-time WebSocket connection updating rows dynamically as Cirium or AIDX events arrive.

### View 3: Passenger Manifest & Connection Tree
* Clicking an affected flight opens the passenger manifest.
* Each passenger card displays: Traveler Name, PNR Locator, Cabin Class, Onward Connecting Flights, Transit Airport, Published Airport MCT, Flyo Safety Buffer, and Calculated Risk State (`SAFE`, `TIGHT_LEGAL`, `MISSED_CONFIRMED`).

### View 4: Multi-Source Evidence & Telemetry Inspector
* Provides operators with complete transparency into why a disruption was flagged.
* Displays raw radar off-block timestamps, destination airport METAR/TAF weather readings, visibility in meters, active runway CAT III status, and authoritative confidence percentage (e.g., 98%).

### View 5: Verified Alternative Flight Comparison Grid
* Displays real available rescue flights returned from live GDS availability (`BargainFinderMaxRQ`) or PSS seat inventory.
* Highlights connection buffer to onward international flights, seat counts per booking class, fare difference (0 under involuntary disruption rules), and DGCA passenger care entitlements (meals, hotel).

### View 6: 1-Click Action Bar & Audit Trail
* "Approve Rescue Rebook" button with mandatory confirmation modal.
* Live execution progress spinner showing Saga steps: `VCC_AUTHORIZED` $\to$ `GDS_DISPATCHED` $\to$ `PNR_VERIFIED` $\to$ `TICKET_EXCHANGED`.
* Full cryptographic audit log drawer showing timestamped operator decisions and WhatsApp OTP verification receipts.
