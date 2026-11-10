# STAGE 13: Navigation and Screen Map Specification

**System:** Flyo Rescue Agent — Airline OCC Operations Console  
**Milestone:** Stage 13  
**Status:** COMPLETE & VERIFIED  

---

## 1. Top-Level Operational Navigation & Global Header

The global header provides persistent situational awareness and RBAC identification across all views:
- **Logo & Title:** `Flyo Rescue Agent &middot; Airline OCC Operations Console`
- **Environment Indicator:** `#demo-mode-badge` — Displays operational mode (`[SANDBOX SIMULATION &middot; TESTBED]`).
- **Operator Identity & RBAC:** `#operator-badge-container` — Displays authenticated operator identity and role (`Senior Dispatcher`, `ROLE_ADMIN` / `ROLE_OPERATOR`).
- **Provider Gateway Status:** `#portal-indicator`, `#portal-status-label` — Live connection status to Amadeus / airline GDS.
- **2FA & Comms Drawer Trigger:** `#btn-toggle-operator-drawer` — Opens off-canvas contextual drawer.

### Primary Four-Tab Navigation Bar
```html
<nav class="nav-tabs">
    <button class="nav-tab active" id="tab-monitor" onclick="switchSectionView('monitor')">Trip Monitor</button>
    <button class="nav-tab" id="tab-ananya" onclick="switchSectionView('ananya')">Incident Recovery</button>
    <button class="nav-tab" id="tab-ops" onclick="switchSectionView('ops')">Operations Control</button>
    <button class="nav-tab" id="tab-logs" onclick="switchSectionView('logs')">Audit Trail</button>
</nav>
```

---

## 2. Screen Specifications

### 2.1 View 1: Trip Monitor (`#view-monitor`) — Primary Landing Page
**Purpose:** Real-time airspace and fleet disruption surveillance for OCC duty dispatchers.

#### Key Sections:
1. **Disruption Summary KPIs:**
   - Total Monitored Flights (`#monitored-count`)
   - Flights with Onward Connection Risks
   - Critical Delays ($> 60$ min)
   - Rescued Itineraries
2. **Operational Filter Console:**
   - Search input (`#flight-search-input`): Case-insensitive match on Flight Number, Route, Passenger, or PNR.
   - Status Filter (`#filter-status-select`): `ALL`, `MONITORED`, `AT_RISK`, `DISRUPTED`, `RECOVERED`.
   - Severity Filter (`#filter-severity-select`): `ALL`, `CRITICAL`, `ELEVATED`, `MODERATE`, `NOMINAL`.
   - Airline Filter (`#filter-airline-select`): `ALL`, `AI` (Air India), `6E` (IndiGo), `UK` (Vistara), `UA` (United), `BA` (British Airways), `LH` (Lufthansa), etc.
   - Reset Filters button (`#filter-reset-btn`).
3. **High-Density Manifest Table (`#bookings-tbody`):**
   - **Column 1: Flight** — Flight callsign with carrier badge (e.g., `AI-839`).
   - **Column 2: Airline** — Full operator name (e.g., `Air India`).
   - **Column 3: Route** — Origin $\to$ Destination airport IATA codes (e.g., `DEL → BOM`).
   - **Column 4: Scheduled Dep** — Baseline timetable timestamp (HH:mm).
   - **Column 5: Estimated Dep / Delay** — Live estimated departure and delay delta in minutes with color-coded severity.
   - **Column 6: Status** — Operational status tag (`DISRUPTED`, `SCHEDULED`, `RESCUED`, etc.).
   - **Column 7: Affected Pax** — Passenger count impacted on this leg.
   - **Column 8: High-Risk Connections** — Count of onward connections violating or approaching Minimum Connection Time (MCT).
   - **Column 9: Disruption Severity** — Calculated risk level (`CRITICAL`, `ELEVATED`, `MODERATE`, `NOMINAL`).
   - **Column 10: Last Update** — Timestamp of last telemetry ingestion from radar/weather provider.
   - **Column 11: Actions** — One-click action buttons (`Inspect Incident`, `Trigger Rescue`).
4. **Dynamic High-Risk Connection Queue:**
   - Derives from live booking manifest and highlights onward flights where the connecting buffer is $< 120$ minutes.

---

### 2.2 View 2: Incident Recovery (`#view-ananya`)
**Purpose:** Disruption case workbench for TMC travel operations leads and dispatchers to resolve affected itineraries.

#### Key Sections:
1. **Dynamic Multi-Case Switcher (`#case-switcher-pills`):**
   - Dynamically generated pills for all open disruption cases (e.g., `Case C1 - Ananya Sharma (DEL-BOM-LHR)`).
2. **Incident Header & Status:**
   - Case Badge (`#case-badge-type`): Disruption classification (`CRITICAL DISRUPTION`, `DELAY`, `MISCONNECT`).
   - Case Heading (`#case-heading`): Passenger name, PNR, and itinerary overview.
   - Case State Badge (`#case-state`): Real-time lifecycle state.
   - Escalate / Reject Action (`#reject-case-btn`).
3. **7-Stage OCC State Machine Stepper:**
   - `Step 1: Detected` (`#step-1`)
   - `Step 2: Impact Assessed` (`#step-2`)
   - `Step 3: Options Generated` (`#step-3`)
   - `Step 4: Awaiting Approval` (`#step-4`)
   - `Step 5: Executing` (`#step-5`)
   - `Step 6: Provider Confirmed` (`#step-6`)
   - `Step 7: Recovered` (`#step-7`)
   - **Exceptional Banner (`#case-exception-banner`):** Explicitly surfaces `BLOCKED`, `RECONCILIATION_REQUIRED`, or `MANUAL_INTERVENTION` states.
4. **Explainable Alternative Flight Options (`#options-list`):**
   - Ranked flight cards enforcing deterministic 120-minute MCT rules:
     - Flight number, airline, departure/arrival timetable.
     - Connection airport and required MCT vs actual buffer (e.g., `2h 15m buffer (>= 120m Safe)`).
     - Explicit disqualification badge when connection buffer $< 120$m.
     - Fare difference tag (e.g., `+₹4,200` or `₹0`).
     - AI / ML delay risk score (e.g., `Risk: LOW (15/100)`).
     - Natural language ranking rationale.
     - Human Approval Button (`#approve-option-btn`).
5. **Approval Banner & Execution Trigger (`#approval-banner`, `#execute-rebook-btn`):**
   - Displays authorized operator name and triggers idempotent rebooking execution via Playwright / GDS NDC adapter.
6. **Corporate Virtual Card & Treasury Settlement Tray (`#vcc-treasury-card`):**
   - PCI-masked card number, authorized amount, merchant category code (MCC 3000), and settlement status.
7. **Integrated Omnichannel Dispatch (`#omnichannel-dispatch-card`):**
   - Live passenger chat thread (`#whatsapp-chat-body`), input box (`#passenger-chat-input`), and send button (`#passenger-send-btn`).

---

### 2.3 View 3: Operations Control (`#view-ops`)
**Purpose:** Infrastructure observability, external provider health, and exception queues.

#### Key Sections:
1. **Operational KPI Summary:**
   - Active Disruption Cases (`#ops-active-count`)
   - Ticketing Desk Manual Queue (`#ops-manual-count`)
   - Failed / Escalated Sagas (`#ops-failed-count`)
   - Successfully Resolved (`#ops-resolved-count`)
2. **Airline, Messaging & Platform Provider Health:**
   - Amadeus GDS Portal (Circuit Breaker status)
   - Meta WhatsApp Cloud API (HMAC-SHA256 signature status)
   - Salesforce Service Cloud (Idempotent External ID sync)
   - PostgreSQL 16 & Redis (Optimistic locking version & distributed keys)
3. **Ticketing Desk Manual Intervention Queue:**
   - Actionable queue for cases experiencing provider timeouts, HTTP 409 conflicts, or ambiguous outcomes.
4. **Regional Airport Disruption Radar:**
   - Weather and congestion tracking for key hub airports (DEL, BOM, BLR, HYD, MAA).
5. **Operational ML Model Health & Governance Panel (`#ops-ml-health-panel`):**
   - Circuit Breaker status badge (`#ml-circuit-badge`) and model version (`#ml-version-badge`).
   - Total Predictions Logged (`#ml-total-predictions`).
   - Inference Latency p95 / p50 (`#ml-latency-p95`, `#ml-latency-p50`).
   - Feature Imputation Rate (`#ml-imputation-rate`).
   - Feature & Prediction PSI Drift Status (`#ml-drift-status`).
   - Evaluated Outcomes & Brier Calibration Score (`#ml-outcomes-evaluated`).
   - Tree-SHAP Global Feature Attribution (`#ml-feature-importance-list`).
   - Live Inference Telemetry Audit Stream (`#ml-inference-tbody`).
   - Collapsed Developer Playground Accordion (for test execution and ground truth ingestion).

---

### 2.4 View 4: Audit Trail (`#view-logs`)
**Purpose:** Cryptographically signed, immutable compliance record for airline regulators and financial auditors.

#### Key Sections:
1. **Audit Event Manifest Table (`#audit-tbody`):**
   - Event Timestamp (ISO 8601).
   - Event Type (`DISRUPTION_DETECTED`, `CASE_APPROVED`, `VCC_ISSUED`, `REBOOK_EXECUTED`, `SETTLED`).
   - Correlation ID & PNR reference.
   - Operator Identity / Agent Actor.
   - Cryptographic Checksum / Hash.
   - Detailed payload and state diff.

---

### 2.5 Contextual Operator & Comms Drawer (`#operator-drawer`)
**Purpose:** Off-canvas slide-out drawer providing 2FA security validation and omnichannel simulation controls without occupying main viewport real estate.

#### Features:
- Slide-over panel toggled via `#btn-toggle-operator-drawer` or `#operator-drawer-overlay`.
- Displays real-time message stream with proof-of-delivery states (`QUEUED`, `SENT`, `DELIVERED`, `READ`).
- Quick-fill 2FA verification code generator for simulated operator authorization flows.
