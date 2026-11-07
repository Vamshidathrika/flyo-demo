# Flyo Master Implementation Sequence & Production Transformation Roadmap (Corrected)

**Document Status:** Technical Authority & Project Master Plan  
**Role:** Principal Forward Deployment Engineer & Engineering Director  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026 (Revised & Corrected)  

---

## 1. Executive Implementation Governance & Strategic Tenets

This master implementation sequence reconciles all engineering audits, FDE strategic imperatives, security containment mandates, and aviation operational constraints. It establishes an uncompromised, dependency-ordered transformation plan to transition Flyo from a demonstration prototype into an enterprise integration platform.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                             FLYO 7-STAGE TRANSFORMATION SEQUENCE                                 │
│                                                                                                  │
│  [STAGE 0] ────────> [STAGE 1] ────────> [STAGE 2] ────────> [STAGE 3]                           │
│  Containment &       Backend & Data      First Client        Production Operator                 │
│  P0 Security         Reliability         Integration         Console UI                          │
│  (Days 1–7)          (Days 8–21)         (Days 22–42)        (Days 43–63)                        │
│                                                                                                  │
│                                                                      │                           │
│                                                                      ▼                           │
│  [STAGE 6] <──────── [STAGE 5] <──────── [STAGE 4] <─────────────────┘                           │
│  Controlled Client   Governed AI &       Booking &                                               │
│  Pilot & Scale       Aviation Logic      Communications                                          │
│  (Days 99–120+)      (Days 85–98)        (Days 64–84)                                            │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

> [!CRITICAL]
> **Execution Freeze:** Stages 1 through 6 are strictly frozen. Stage 0 is the immediate, mandatory containment prerequisite. No feature development, client deployment, or external integration may begin until Stage 0 passes all acceptance criteria and regression tests.

---

## 2. Definitive Architectural & Operational Corrections

This revised sequence incorporates twelve vital engineering corrections:

1. **Aviation Weather Clarification:** Open-Meteo is explicitly designated as a **supplementary numerical weather prediction model API**. It is **not** an aviation-certified METAR or TAF provider. Certified METAR/TAF data will be sourced via aviation data providers (Cirium / NOAA Aviation Weather Center / SITA).
2. **AIDX Standard vs. Data Supplier:** IATA AIDX (RP 1797A) is treated strictly as an **XML data exchange standard**. Operational flight data requires connecting to a specific airline OCC, airport AODB, or licensed commercial distributor (SITA, Cirium) that exposes AIDX feeds.
3. **GDS Queue Access Prerequisites:** Accessing agency PNR queues requires an authorized Pseudo City Code (PCC) or Office ID (OID), signed subscriber contracts, certified Electronic Profile Records (EPR), and network IP allowlisting. GDS queues cannot be polled anonymously.
4. **Authoritative MCT Separation:** Flyo strictly separates **Official IATA Station Minimum Connecting Times** (e.g., DEL T3 Domestic-to-International 90 min) from Flyo's **internal conservative connection policy** (120 min buffer for international flights).
5. **Multi-Attribute Booking Verification:** Booking verification requires authoritative confirmation: for NDC, an **NDC Order ID** and confirmed status; for Navitaire, a **ticketless Record Locator**; for legacy GDS, a **PNR locator with confirmed segment status (`HK`/`SS`)** and e-ticket coupon status (`OPEN`/`EXCHANGED`). An e-ticket number is not the sole universal criterion.
6. **Comprehensive PCI-DSS Containment:** Remediation extends beyond dropping the `cvv` column to include forensic auditing of database seeds, application logs, backups, and exports, confirming that zero real cardholder data has ever entered persistent storage.
7. **Reconciled State Machine Architecture:** Reconciled against [`CaseStateMachineService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/CaseStateMachineService.java), which implements a complete graph of **44 state constants** (25 core lifecycle states, 1 legacy bridge `AWAITING_APPROVAL`, and 18 exceptional/compensation states), not 9 or 22 states.
8. **Verified Test Execution Counts:** Confirmed via live execution: **116 Spring Boot tests across 28 test classes** (Surefire execution) and **26 Python ML unit tests across 4 test files**.
9. **Tamper-Evident Audit Hashing:** Database tables are not assumed to be immutable. Flyo implements **cryptographic hash chaining (SHA-256 / Ed25519)** linking each audit record to the previous event to provide verifiable tamper evidence.
10. **Contingent Frontend Modernization:** Modern frontend framework selection (React 18 / TypeScript / Vite) is strictly contingent on completing backend API stabilization and JWT contract finalization. Migration will proceed incrementally.
11. **Documented TMC Integration Prerequisites:** Pilot onboarding requires agency PCC credentials, GDS queue configuration, a signed Data Processing Agreement (DPA) under India's DPDP Act 2023, and mutual IP allowlisting.
12. **Customer-Agreed Pilot Baseline KPIs:** Replaced arbitrary percentages with empirical baseline-vs-pilot metrics (e.g., historical desk handling time, call abandonment rate, and missed connection frequency agreed with the customer).

---

## 3. Stage 0: Security & Payment Containment (Separately Scoped Plan)

**Duration:** Days 1–7  
**Objective:** Eliminate unauthenticated consequential API access, purge synthetic PCI fields, disable debug/mock endpoints, and ensure the backend fails closed.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                STAGE 0 WORK BREAKDOWN & ACCEPTANCE                               │
├─────────┬─────────────────────────┬───────────────────────────┬──────────────────────────────────┤
│ Task ID │ Action Item             │ Target Files              │ Acceptance Criteria              │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-01  │ Fail-Closed Security    │ `SecurityConfig.java`     │ All consequential endpoints      │
│         │ Filter Chain & JWT Auth │ `JwtAuthFilter.java`      │ (`/api/cases/**`, `/bookings/**`,│
│         │                         │                           │ `/payments/**`) return HTTP 401  │
│         │                         │                           │ without valid signed JWT.        │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-02  │ PCI Schema Migration    │ `V4__pci_remediation.sql` │ Database migration drops `cvv`   │
│         │ & Tokenization          │ `VirtualCard.java`        │ and plaintext card number; adds  │
│         │                         │ `VirtualCardService.java` │ `card_token_id` and `last_four`. │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-03  │ Forensic PCI Purge &    │ `FlyoDataSeeder.java`     │ Audit confirms 0 real or mock    │
│         │ Seed Sanitization       │ `backend.log`             │ CVVs exist in database, seeds, or│
│         │                         │                           │ log files.                       │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-04  │ Disable H2, Mock Portals│ `application-prod.prop`   │ H2 console unmapped in prod.     │
│         │ & Actuator Hardening    │ `MockAirlineController`   │ `/mock-airline/**` unmapped.     │
│         │                         │                           │ Actuator exposes `health` only.  │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-05  │ Secret Boot Enforcement │ `AviationRadarConfig`    │ Backend fails to boot if API     │
│         │                         │ `application-prod.prop`   │ secrets match default fallback.  │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-06  │ FastAPI Monitoring Auth │ `ml/service/main.py`      │ `/monitoring/*` endpoints reject │
│         │ Hardening               │ `ml/service/config.py`    │ calls without monitoring key.    │
└─────────┴─────────────────────────┴───────────────────────────┴──────────────────────────────────┘
```

### Stage 0 Regression Test Gate
* `SecurityFilterRegressionTest`: Asserts HTTP 401 on unauthenticated calls to all consequential endpoints; asserts HTTP 404 on H2 console in prod profile.
* `PciTokenizationRegressionTest`: Verifies entity persistence without CVV; asserts token retrieval.
* `BaselineDomainLogicRegressionTest`: Runs all 116 Spring Boot tests with authenticated security context to ensure zero domain logic regression.

---

## 4. Subsequent Implementation Sequence (Stages 1 through 6)

### Stage 1: Backend and Data Reliability (Days 8–21)
* **Outbox Dispatcher:** Implement `OutboxEventDispatcher` polling `outbox_events` with PostgreSQL `FOR UPDATE SKIP LOCKED`, exponential backoff, and Dead-Letter Queue (DLQ).
* **Distributed Saga Engine:** Replace inline external calls with an asynchronous orchestration saga using deterministic SHA-256 idempotency keys.
* **Tamper-Evident Audit Chain:** Implement cryptographic hash chaining linking each `audit_events` record to the prior event hash.
* **Tenant Isolation:** Enforce PostgreSQL Row-Level Security (RLS) on `bookings`, `rescue_cases`, and `rebook_options` bound to thread-local tenant context.

### Stage 2: First Real Client Integration (Days 22–42) — Core FDE Milestone
* **Customer Selection:** Deploy with one Corporate Travel Management Company (TMC).
* **In-VPC Integration Gateway (FIG):** Deploy container appliance inside client network with outbound-only mTLS 1.3 tunnel and client KMS secret storage.
* **Inbound Flight Telemetry:** Connect to licensed commercial flight alert feed (Cirium Flight Status Stream / Airport AODB).
* **Inbound Passenger Itineraries:** Connect to authorized agency GDS Queue Sweeper (Sabre REST APIs / Travelport+ under agency PCC).
* **Read-Only Shadow Run:** Ingest live corporate bookings departing from DEL, BOM, and BLR; run disruption detection in 100% read-only mode.

### Stage 3: Production Operator Console (Days 43–63)
* **Frontend Tech Stack Assessment:** Validate React 18 / TypeScript / Tailwind CSS compatibility.
* **Dynamic Operations Views:** Build Station Context Selector (`DEL`, `BOM`, `BLR`), Live Disruption Triage Queue, Global Flight Explorer, Dynamic Passenger Manifest, and Multi-Source Evidence Panel.
* **Decommission Demo Artifacts:** Permanently remove `app.js` shortcuts, hardcoded Ananya Sharma views, and fake WhatsApp preview frames.

### Stage 4: Booking and Notification Integrations (Days 64–84)
* **Live GDS Seat Availability:** Connect `FlightSearchService` to Sabre `BargainFinderMaxRQ` to return real available seat inventory and fare families.
* **Authorized GDS Exchange Connector:** Implement certified Sabre `AutomatedExchangeRQ` / `EnhancedAirBookRQ` to execute involuntary rebooking under IROP waiver codes.
* **Multi-Attribute Verification:** Verify final booking status via confirmed PNR status (`HK`), ticket coupon status, or NDC Order ID.
* **Live WhatsApp Business Integration:** Connect `MetaCloudWhatsAppAdapter` with approved Meta Business templates and verified 6-digit OTP challenge.

### Stage 5: Governed AI and Aviation Decision Logic (Days 85–98)
* **Authoritative MCT Engine:** Ingest official IATA airport MCT tables for major Indian and global transit hubs; separate published MCT from Flyo conservative policy.
* **Bounded Cognitive AI Agents:** Deploy `EvidenceSummarizerAgent` and `TradeoffExplainerAgent` behind non-bypassable deterministic safety gates (MCT rules, human approval gate).
* **Indian DGCA ML Retraining:** Ingest 24 months of Indian civil aviation flight delay and fog data; train and validate localized XGBoost models; activate Indian airspace in FastAPI service.

### Stage 6: Controlled Client Pilot (Days 99–120+)
* **Phase A: 30-Day Read-Only Shadow Validation:** Measure concordance between Flyo automated recommendations and human agency dispatchers against agreed customer baseline (Target: $\ge 85\%$ concordance).
* **Phase B: Controlled Operational Pilot:** Enable operator 1-click rebooking and passenger WhatsApp OTP confirmations on a single corporate account or hub pair (DEL-BOM).
* **Measurable Customer ROI:** Measure reduction in re-ticketing handling time, zero missed onward international connections, and elimination of DGCA passenger care non-compliance penalties.
