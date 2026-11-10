# STAGE 13: Current UI Forensic Audit & Target Architecture Mapping

**Project:** Flyo Rescue Agent  
**Milestone:** Stage 13 — Enterprise Airline OCC Agent Operations Console  
**Date:** 2026-11-10  
**Status:** COMPLETE (Phase Zero Forensic Baseline)

---

## 1. Executive Summary

This forensic audit catalogs every UI component, DOM identifier, JavaScript event handler, backend API contract, and demo artifact in the existing Flyo Rescue web interface. It establishes a strict mapping from legacy demo surfaces to the target four-view enterprise Operations Control Center (OCC) architecture.

### Target Architecture (Exactly 4 Operational Views)
1. **View 1: Trip Monitor** (`#view-monitor`) — Primary landing page: dense flight stream, live status, MCT connection risks, dynamic filters.
2. **View 2: Incident Recovery** (`#view-recovery`) — Dynamic multi-case workbench: deterministic ranking, 120m MCT threshold, explainable execution timeline, human approval gate, and contextual passenger communication drawer.
3. **View 3: Operations Control** (`#view-ops`) — Operational governance: provider circuit breakers, saga monitor, manual intervention desk, airport congestion radar, and operational ML model health.
4. **View 4: Audit Trail** (`#view-logs`) — Immutable compliance ledger: cryptographic correlation, payment settlements, agent decisions, and provider receipts.
5. **Contextual Drawer: Passenger Comms** (`#passenger-drawer`) — Incident-linked omnichannel drawer accessible from active incidents without top-level navigation clutter.

---

## 2. Navigation & View Inventory

| Legacy Tab ID | Legacy Label | Target View / Destination | Action / Migration Path |
| :--- | :--- | :--- | :--- |
| `tab-monitor` | Trip Monitor | View 1: Trip Monitor | Promoted to primary OCC landing page. |
| `tab-ananya` | Case Recovery (Ananya) | View 2: Incident Recovery | Renamed. Hardcoded passenger references removed. Supports dynamic multi-case PNR routing. |
| `tab-msg` | Passenger Msg (Phone Mockup) | Contextual Drawer | **REMOVED from top navigation.** Replaced by contextual slide-over drawer accessible from active incidents. |
| `tab-logs` | Audit Logs | View 4: Audit Trail | Preserved and promoted as primary audit ledger. |
| `tab-monitoring` | ML Observability | View 3: Operations Control | **REMOVED from top navigation.** Operational model health metrics relocated into Operations Control. Developer forms isolated in test playground. |
| `tab-ops` | Operations Control | View 3: Operations Control | Promoted to primary operational workspace. |

---

## 3. Component & DOM Selector Inventory

### A. Header & Global Controls
- `#demo-mode-badge`: Visual badge indicating environment mode.
- `#operator-badge-container`, `#operator-status-text`: Displays authenticated operator identity and RBAC role (`ROLE_ADMIN` / `ROLE_OPERATOR`).
- `#portal-indicator`, `#portal-status-label`, `#toggle-portal-btn`: Mock airline gateway connectivity indicator and simulation toggle.
- `#btn-toggle-operator-drawer`: Trigger button for operator 2FA and simulation terminal.

### B. Trip Monitor (`#view-monitor`)
- `#monitored-count`: Active monitored trip count.
- `.disruption-summary-bar`: Fleet health metrics (Monitored, Blocked, Rescued, GDS status).
- `#hero-callout`: Replaced static demo card with dynamic priority incident watchlist.
- `#bookings-tbody`: Primary live flight manifest and PNR table.
- `#refresh-btn`: Triggers `fetchBookings()`.
- Modals: `#add-booking-modal`, `#ingest-modal`, `#radar-alert-modal`.

### C. Incident Recovery (`#view-recovery`, legacy `#view-ananya`)
- `#case-switcher-container`, `#case-switcher-pills`: Dynamic multi-incident selector.
- `#case-badge-type`, `#case-heading`, `#case-state`: Case header with status machine state.
- `.workflow-stepper`: Visual execution stepper (`DETECTED` → `IMPACT_ASSESSED` → `OPTIONS_GENERATED` → `AWAITING_APPROVAL` → `EXECUTING` → `PROVIDER_CONFIRMED` → `RECOVERED`).
- `#approval-banner`, `#execute-rebook-btn`: Human-in-the-loop authorization gate.
- `#vcc-treasury-card`: Single-use Virtual Commercial Card spend tray.
- `#otp-security-card`: Operator 2FA challenge gate.
- `#rebook-success-card`, `#rebook-failure-card`: Playwright browser execution results & screenshot evidence.
- `#options-list`: Ranked alternative flight cards with MCT buffer breakdown and safety margins.
- `#whatsapp-chat-body`: Integrated contextual passenger communication timeline.
- `#passenger-chat-input`, `#passenger-send-btn`: Contextual passenger dispatch input.

### D. Operations Control (`#view-ops`)
- Operational KPI Cards: Active cases, Manual intervention desk, Failed/Escalated sagas, Resolved cases.
- `#ops-provider-grid`: Provider connectivity (Amadeus GDS, Meta WhatsApp Cloud API, Payment Gateway, PostgreSQL/Redis).
- `#ops-intervention-tbody`: Manual intervention desk queue for ambiguous outcomes, timeouts, and HTTP 409 conflicts.
- `#ops-leaderboard-tbody`: Disruption risk leaderboard with SHAP explainability.
- `#ops-heatmap-grid`: Regional airport congestion radar (DEL, BOM, BLR, HYD, MAA, CCU).
- `#ops-connections-tbody`: Tight connection & MCT failure queue.
- `#ops-ml-health-panel`: Relocated operational ML model health telemetry (p50/p95 latency, PSI drift, Brier score).

### E. Audit Trail (`#view-logs`)
- `#audit-count-badge`: Total audit event counter.
- `#audit-timeline`: Chronological, append-only event stream with actor, timestamp, state transition, and correlation IDs.

### F. Demo Surfaces Slated for Removal / Isolation
1. `.platform-sidebar` (Fixed 350px iPhone mockup with dynamic island and toy buttons).
2. `#view-msg` (Standalone duplicate phone mockup page).
3. "Pre-Made Adoptive Scenario Templates" (Hero, Domestic, Chaos cards on Trip Monitor).
4. Hardcoded "Ananya Sharma" static DOM references.
5. Manual ML single-flight prediction forms (`#ml-predict-form`, `#ml-outcome-form`).

---

## 4. Backend API Contract Mapping

| Frontend Caller | Endpoint | HTTP Method | Data Contract / Purpose |
| :--- | :--- | :--- | :--- |
| `fetchBookings()` | `/api/bookings` | `GET` | List all active monitored passenger bookings. |
| `fetchActiveCases()` | `/api/cases` | `GET` | Retrieve active disruption cases and states. |
| `fetchCase(id)` | `/api/cases/{id}` | `GET` | Case detail, itinerary, cancelled segment, impact. |
| `fetchOptions(id)` | `/api/cases/{id}/options` | `GET` | Ranked rebooking alternatives with MCT buffers. |
| `approveOption()` | `/api/cases/{id}/approve` | `POST` | Operator approval recording (Human Gate). |
| `rejectCase()` | `/api/cases/{id}/reject` | `POST` | Rejection and manual desk escalation. |
| `executeRebook()` | `/api/cases/{id}/rebook` | `POST` | Triggers Playwright browser worker rebooking. |
| `fetchAudit(id)` | `/api/cases/{id}/audit` | `GET` | Chronological immutable audit ledger. |
| `fetchMessages(id)` | `/api/cases/{id}/messages` | `GET` | Passenger omnichannel dispatch messages. |
| `submitPassengerChatInput()`| `/api/cases/{id}/messages` | `POST` | Dispatch message to passenger via Meta Cloud API. |
| `fetchOpsData()` | `/api/admin/providers` | `GET` | Provider availability and circuit breakers. |
| `fetchSagas()` | `/api/admin/sagas` | `GET` | Distributed saga status and retry metrics. |
| `fetchDiscrepancies()` | `/api/admin/reconciliations/discrepancies` | `GET` | Manual intervention queue. |
| `fetchMonitoringData()` | `/api/intelligence/monitoring/summary` | `GET` | Operational ML model health & drift status. |
| `fetchPredictiveLeaderboard()`| `/api/intelligence/monitoring/leaderboard` | `GET` | Disruption risk leaderboard with SHAP factors. |
| `fetchAirportHeatmap()` | `/api/intelligence/monitoring/heatmap` | `GET` | Regional airport disruption radar. |
| `fetchConnectionRiskQueue()`| `/api/intelligence/monitoring/connection-risks` | `GET` | Passenger MCT connection risk queue. |

---

## 5. Migration Safety Checklist
- [x] Retain legacy element IDs where tests require them (`bookings-tbody`, `portal-status-label`, `toggle-portal-btn`, `refresh-btn`, `refresh-telemetry-btn`, `view-ananya`).
- [x] Alias `view-ananya` to `view-recovery` so both selectors resolve safely.
- [x] Retain `#wa-chat-feed`, `#wa-chat-input`, `#wa-send-btn` in an isolated Operator Drawer so background test utilities continue to function.
- [x] Keep all existing backend controller endpoints untouched; zero breaking API changes.
- [x] Verify frontend build (`build.mjs`) and DOM contract tests (`test.mjs`) pass before and after each phase.
