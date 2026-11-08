# Flyo Client Integration Implementation Roadmap

**Document Status:** Phased Engineering Execution Roadmap  
**Author:** Principal Forward Deployment Engineer & Head of Engineering  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Roadmap Architecture & Strategic Milestones

This roadmap defines the engineering execution plan to transition Flyo from its current demonstration baseline into an integration-first enterprise aviation rescue platform.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                 PHASED IMPLEMENTATION ROADMAP                                    │
│                                                                                                  │
│  [PHASE 1] ────────> [PHASE 2] ────────> [PHASE 3] ────────> [PHASE 4] ────────> [PHASE 5]       │
│  Security, PCI       Flyo Customer      Live Telemetry      Indian ML Pipeline  Shadow Run       │
│  & Multi-Tenancy     Gateway (FIG)      & GDS Adapters      & DGCA Retrain      & UAT Gate       │
│  (Weeks 1–3)         (Weeks 4–6)        (Weeks 7–10)        (Weeks 11–13)       (Weeks 14–17)    │
│                                                                                 │                │
│                                                                                 ▼                │
│                                                                           [PHASE 6]              │
│                                                                           Controlled Production  │
│                                                                           Live Pilot (Weeks 18+) │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Phase-by-Phase Engineering Execution Plan

### Phase 1: Security Hardening, PCI Remediation & Multi-Tenant Core (Weeks 1–3)

#### Core Deliverables
1. **Flyway Migration `V4__enterprise_multitenancy_and_pci_remediation.sql`:**
   - Add `tenant_id VARCHAR(64) NOT NULL` to `bookings`, `rescue_cases`, `rebook_options`, `audit_events`.
   - Enable PostgreSQL Row Level Security (RLS) on all core tables.
   - **Remediate PCI-DSS Vulnerability:** Drop `cvv` column from `virtual_cards`. Truncate `card_number` to `last_four VARCHAR(4)`. Replace with `card_token_id VARCHAR(128)`.
2. **CADM Core Java Interfaces:**
   - Define canonical interfaces in `com.flyo.rescue.cadm.*`: `CadmFlightLeg`, `CadmPassengerJourney`, `CadmDisruptionEvent`, `CadmReaccommodationOffer`.
3. **Tenant Context Interceptor:**
   - Implement `TenantContextFilter` in Spring Security to enforce tenant boundary isolation on every HTTP request and JPA query.

#### Verification Gate
* `PostgreSqlProductionIntegrationTest` updated and passing with 100% RLS tenant isolation.
* Automated static analysis confirms zero plaintext CVV references across all backend source files.

---

### Phase 2: Flyo Customer Integration Gateway (FIG) Container (Weeks 4–6)

#### Core Deliverables
1. **Gateway Appliance Packaging:**
   - Containerized Go/Java micro-app packaged via Docker and Helm chart (`charts/flyo-gateway`).
2. **PII Masking & Tokenization Module:**
   - Client-side tokenization engine mapping passenger names and phone numbers to tenant-isolated UUID tokens.
3. **Outbound mTLS Tunnel Agent:**
   - Establishes persistent gRPC/TLS 1.3 streaming connection from client VPC to Flyo Intelligence Cloud.
4. **Offline Buffer & Circuit Breaker:**
   - Embedded RocksDB queue buffering flight events during network WAN interruptions.

#### Verification Gate
* Chaos network partition test: simulate 30-minute WAN drop between Gateway and Cloud; verify 100% zero-data-loss event replay upon reconnection.

---

### Phase 3: Live Operational Telemetry & GDS Connectors (Weeks 7–10)

#### Core Deliverables
1. **Cirium Streaming Telemetry Connector:**
   - Implement `CiriumFlightTelemetryAdapter` ingesting live B2B flight status, gate changes, and delay alerts.
2. **Sabre GDS Production REST Connector:**
   - Replace stubbed logic in `SabreGdsBookingAdapter` with authenticated REST calls to Sabre `GetReservation` (read) and `EnhancedAirBook` / `AutomatedExchange` (write).
3. **Dynamic Live Availability Search Service:**
   - Deprecate hardcoded `FL101` / `FL301` mock flights in `FlightSearchService`. Connect to live GDS low-fare search (`BargainFinderMaxRQ`) to return genuine available seats.

#### Verification Gate
* End-to-end integration test against Sabre Certified Test Environment (PCC sandbox) proving dynamic PNR retrieval and seat availability lookup.

---

### Phase 4: Localized Indian Airspace ML Disruption Pipeline (Weeks 11–13)

#### Core Deliverables
1. **DGCA Airspace Historical Corpus Ingestion:**
   - Ingest 24 months of Indian civil aviation delay statistics, airport winter fog schedules (DEL, BOM, BLR, HYD), and seasonal airline on-time performance (OTP).
2. **Indian XGBoost Disruption Model Training:**
   - Train and validate localized disruption models (`model_v3_ind_delays.json` and `model_v3_ind_cancellations.json`).
3. **Airspace Geofence Activation in FastAPI Service:**
   - Update `ml/service/predictor.py` to route Indian carrier codes (`AI`, `6E`, `UK`, `QP`) and airport codes (`DEL`, `BOM`, `HYD`) to the validated Indian model instead of returning `UNSUPPORTED_AIRSPACE`.

#### Verification Gate
* Model evaluation report demonstrating ROC-AUC > 0.82 and Precision > 0.85 on Indian historical validation split.

---

### Phase 5: Client Shadow Deployment & UAT Certification (Weeks 14–17)

#### Core Deliverables
1. **Deploy FIG into First Client (Corporate TMC) Staging Network:**
   - Deploy Gateway into customer VPC; connect to client's Sabre/Travelport agency queue.
2. **Passive Read-Only Shadow Operation:**
   - Ingest live real-world corporate bookings departing from DEL, BOM, and BLR.
   - Run disruption detection and rebooking ranking in 100% read-only shadow mode.
3. **Weekly Dispatcher Concordance Audit:**
   - Measure concordance between Flyo automated recommendations and human agency dispatcher choices. Target: >= 85% agreement.

#### Verification Gate
* 30 consecutive days of zero operational incidents, zero false-cancellation alerts, and written sign-off from Client Operations Lead.

---

### Phase 6: Controlled Production Write-Back & Pilot Operations (Weeks 18+)

#### Core Deliverables
1. **Enable Operator Console 1-Click Rebooking:**
   - Authorized agency operators review pre-ranked Flyo options and click "Approve Rebook" to dispatch live GDS exchange orders.
2. **Enable Passenger WhatsApp OTP Challenge:**
   - Stranded corporate travelers receive verified WhatsApp rescue messages and authorize ticket changes via 6-digit OTP.
3. **Production SLA & Monitoring Dashboard:**
   - Prometheus metrics and Grafana dashboards tracking end-to-end rebooking latency (< 2.5s), WhatsApp delivery rate (> 98%), and GDS exchange success rate (> 99%).

#### Verification Gate
* First 100 live corporate passengers successfully rescued with verified new PNRs, ticket exchanges, and zero manual desk escalations.
