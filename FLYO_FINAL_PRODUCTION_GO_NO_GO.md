# Flyo Final Production Go/No-Go Decision & Forensic Verification Report

**Document Status:** Technical Authority & Production Gate Determination  
**Author:** Principal Forward Deployment Engineer & Engineering Director  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Evaluation Date:** November 2026  

---

## 1. Definitive Production Gate Determination

> [!CAUTION]
> **FINAL VERDICT: PRODUCTION NOT AUTHORIZED (NO-GO)**  
> While Flyo contains high-quality deterministic decision logic (MCT rules, multi-source evidence reconciliation, state transitions, and audit hashing), it remains an uncontained demonstration prototype. Consequential REST APIs are anonymously accessible, synthetic card data structures violate PCI-DSS v4.0, the transactional outbox engine is dormant, candidate flights are hardcoded mocks, and disruption ML models exhibit near-zero recall.
> 
> **Implementation of Stages 1 through 6 is STRICTLY FROZEN** pending execution, verification, and sign-off of **Stage 0 (Security & Data Containment)**.

---

## 2. Six-Dimension Forensic Reality Classification

To eliminate ambiguity across prior audits, all system findings are categorized into six mutually exclusive technical classes:

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                             SIX-DIMENSION REALITY CLASSIFICATION                                 │
├──────────────────────────┬───────────────────────────────────────────────────────────────────────┤
│ Classification Dimension │ Scope & Definition                                                    │
├──────────────────────────┼───────────────────────────────────────────────────────────────────────┤
│ 1. Confirmed Source-Code │ Directly observed in Java, Python, SQL, HTML, or YAML source files.   │
│ 2. Verified Runtime      │ Executed and confirmed via live Maven/Python test execution.          │
│ 3. External Vendor Reqs  │ Binding technical & commercial requirements from GDS, PSS, or Cirium. │
│ 4. Unverified Assumptions│ Speculative beliefs that fail upon empirical inspection.              │
│ 5. Customer Prereqs      │ Credentials and network permissions owned exclusively by client.      │
│ 6. Legal / Compliance    │ Statutory mandates requiring specialist legal/compliance review.      │
└──────────────────────────┴───────────────────────────────────────────────────────────────────────┘
```

### Dimension 1: Confirmed Source-Code Findings
* **Anonymous Consequential APIs:** In [`SecurityConfig.java:50-60`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/SecurityConfig.java#L50-L60), all routes under `/api/cases/**`, `/api/bookings/**`, `/api/payments/**`, `/h2-console/**`, and `/actuator/**` are explicitly marked `.permitAll()`.
* **Prohibited CVV & Card Storage:** In [`V1__initial_production_schema.sql:72-85`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/resources/db/migration/V1__initial_production_schema.sql#L72-L85), the `virtual_cards` table defines `card_number VARCHAR(32)`, `expiry VARCHAR(16)`, and `cvv VARCHAR(32)` in persistent storage.
* **Dormant Outbox Dispatcher:** In [`OutboxService.java:31`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/OutboxService.java#L31), `getPendingEvents()` is defined but called nowhere in the entire codebase. Outbox events are written to the database and permanently abandoned.
* **44-State Lifecycle Graph:** In [`CaseStateMachineService.java:33-80`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/CaseStateMachineService.java#L33-L80), the code defines 44 state constants and maps 44 transition keys (25 core lifecycle states, 1 legacy bridge `AWAITING_APPROVAL`, and 18 exceptional states). The 9 states in `RescueCase.java` are a legacy comment.
* **Mock Candidate Flights:** In [`FlightSearchService.java:41-115`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/FlightSearchService.java#L41-L115), candidate recovery options are hardcoded static templates (`FL101`, `FL301`, `FL401`, `FL201`), not live GDS inventory.
* **Stubbed Rebooking Adapters:** In [`AmadeusNdcBookingAdapter.java:48`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/rebook/AmadeusNdcBookingAdapter.java#L48), the adapter throws `UnsupportedOperationException`. [`SabreGdsBookingAdapter.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/rebook/SabreGdsBookingAdapter.java) throws `ProviderNotConfiguredException`. Rebooking defaults via `@Primary` to Playwright driving a local mock HTML page.
* **Dual Database Split:** Spring Boot uses PostgreSQL/H2, while FastAPI ([`monitoring_db.py:27`](file:///Users/nani/Downloads/flyo/ml/service/monitoring_db.py#L27)) uses local file SQLite (`monitoring.db`).

### Dimension 2: Verified Runtime Findings
* **Spring Boot Test Execution:** Confirmed via live execution of `mvn surefire:test` on 2026-11-06: **Exactly 116 tests run across 28 test classes with 0 failures, 0 errors, and 0 skipped**. (Build time: 34.18s).
* **Python ML Test Execution:** Confirmed via live execution of `python -m unittest discover -s ml/tests`: **Exactly 26 tests run across 4 test files with 0 failures and 0 errors**. (Execution time: 0.45s).
* **ML Model Inutility Confirmed:** In [`MODEL_EVALUATION_REPORT.md`](file:///Users/nani/Downloads/flyo/MODEL_EVALUATION_REPORT.md), evaluated on 50,000 summer test flights:
  - Cancellation Model ($y_{\text{cancel}}$): ROC-AUC is **0.5279** (barely above random 0.5000). At operational threshold $P \ge 0.10$, Recall is **0.41%** (6 TP vs 1,475 FN). At $P \ge 0.20$, Recall is **0.00%**. Brier score (**0.02931**) is worse than the naive empirical baseline (**0.02880**).
  - Extreme Delay Model ($y_{\text{delay\_120}}$): ROC-AUC is **0.5950**, Brier score is **0.05075** (worse than empirical baseline of **0.04985**).
* **Geofencing Active:** Python inference correctly intercepts Indian airport/carrier requests and returns `UNSUPPORTED_AIRSPACE`.

### Dimension 3: External Vendor Requirements
* **AIDX is an Exchange Standard, Not a Provider:** IATA AIDX (RP 1797A) is an XML message standard. Flyo cannot "connect to AIDX." Flyo must connect to a specific airline OCC, airport AODB, or authorized commercial distributor (e.g., SITA, Cirium) that exposes AIDX payloads over IBM MQ, Kafka, or Webhooks.
* **GDS Queue & Servicing Access:** Accessing Sabre or Travelport PNR queues requires an authorized Pseudo City Code (PCC), an active subscriber contract, certified Electronic Profile Records (EPR), and network IP allowlisting. GDS queues cannot be polled anonymously.
* **NDC Booking Confirmation Semantics:** Under IATA NDC 21.3, confirmed bookings return a 10-character NDC Order ID (e.g., `ORD-8F92A1`). Navitaire New Skies returns a ticketless Record Locator. **Requiring a 13-digit e-ticket number as the sole confirmation check will cause 100% false-negative execution failures on NDC and LCC channels.**

### Dimension 4: Unverified Assumptions (Debunked)
* **Debunked: "Open-Meteo provides airport METAR/TAF."** Open-Meteo is a global numerical weather prediction (NWP) model API. It does **not** provide aviation-certified METAR observations or Terminal Aerodrome Forecasts (TAF). It serves only as supplementary meteorological context.
* **Debunked: "120 minutes is a universal aviation MCT."** Minimum Connecting Time is station- and itinerary-specific, published by IATA for over 400 airports. 120 minutes is strictly an internal Flyo conservative business policy, not an industry rule.
* **Debunked: "The database audit table is immutable."** PostgreSQL `audit_events` rows can be updated or deleted by any database administrator unless protected by write-once-read-many (WORM) storage, append-only triggers, or cryptographic hash chaining.

### Dimension 5: Customer-Dependent Prerequisites
* **First Client (TMC) Requirements:**
  1. Travel agency must provision an authorized Sabre PCC or Travelport OID with PNR read/write servicing branches.
  2. Agency must authorize Flyo to sweep GDS Queue 50/99 for disrupted PNRs.
  3. Agency must execute a formal Data Processing Agreement (DPA) under India's DPDP Act 2023.
  4. Agency must establish agreed baseline disruption metrics (e.g., historical desk handling time, call abandonment rate) to measure pilot ROI.

### Dimension 6: Legal & Compliance Matters (Requiring Specialist Counsel)
* **PCI-DSS Assessment Boundary:** While Flyo drops CVV and tokenizes card issuing under SAQ A / SAQ A-EP, formal attestation requires a Qualified Security Assessor (QSA) review.
* **India DPDP Act 2023 & 2025 Notified Rules:** Customer is the Data Fiduciary; Flyo is the Data Processor. Enforces standalone itemized consent notices for WhatsApp outreach, right to erasure, and 30-day post-trip data retention limits.
* **DGCA CAR Section 3, Series M, Part IV:** Technical tracking of passenger care thresholds (meals at 2h/3h/4h delay; hotel care for overnight disruptions; full refund option for >6h delay). Extraordinary circumstances (e.g., Delhi fog CAT III) must be recorded in tamper-evident logs to defend against unjustified penalty claims.

---

## 3. Immediate Action Plan: Stage 0 Security & Data Containment

Stage 0 is a strictly scoped, prioritized containment release. It modifies zero business workflows, introduces no new vendor connectors, and focuses 100% on security hardening, data containment, and failing closed.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                STAGE 0 CONTAINMENT MATRIX                                        │
├─────────┬─────────────────────────┬───────────────────────────┬──────────────────────────────────┤
│ Task ID │ Engineering Task        │ Affected Source Files     │ Acceptance Criteria              │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-01  │ Fail-Closed Filter Chain│ `SecurityConfig.java`     │ All consequential endpoints      │
│         │ & JWT Bearer Validation │ `JwtAuthFilter.java`      │ return 401 Unauthorized without  │
│         │                         │ `application.properties`  │ valid signed JWT.                │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-02  │ PCI Schema Remediation  │ `V4__pci_remediation.sql` │ CVV column dropped. Card number  │
│         │ & Card Tokenization     │ `VirtualCard.java`        │ replaced with `card_token_id`.   │
│         │                         │ `VirtualCardService.java` │ Zero CVVs in persistent DB.      │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-03  │ Historical Data & Logs  │ `FlyoDataSeeder.java`     │ Audit confirms 0 real PAN/CVV    │
│         │ Forensic PCI Purge      │ Database wipe / re-seed   │ in database, logs, or exports.   │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-04  │ Disable H2, Actuator, & │ `application-prod.prop`   │ H2 console unmapped in prod.     │
│         │ Production Hardening    │ `MockAirlineController`   │ `/actuator/health` only; all     │
│         │                         │ `SecurityConfig.java`     │ other actuator routes 401.       │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-05  │ Secrets Enforcement     │ `application-prod.prop`   │ App fails to boot if secrets     │
│         │ (Fail-to-Boot on Missing)│ `AviationRadarConfig`    │ match default mock strings.      │
├─────────┼─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ SEC-06  │ FastAPI Monitoring Auth │ `ml/service/main.py`      │ `/monitoring/*` endpoints reject │
│         │ Header Hardening        │ `ml/service/config.py`    │ calls without valid secret key.  │
└─────────┴─────────────────────────┴───────────────────────────┴──────────────────────────────────┘
```

### Stage 0 File-Level Change Specifications

#### Task SEC-01: `SecurityConfig.java`
* **Location:** [`flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/SecurityConfig.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/SecurityConfig.java)
* **Changes:**
  1. Remove `.permitAll()` from lines 50–60.
  2. Restrict public ingress exclusively to `POST /api/whatsapp/webhook` (Meta signature verified), `GET /actuator/health`, and `POST /api/auth/login`.
  3. Require `hasAnyRole("OPERATOR", "SUPERVISOR", "ADMIN")` for all `/api/cases/**`, `/api/bookings/**`, and `/api/alerts/**` endpoints.
  4. Require `hasRole("ADMIN")` for all `/api/payments/**` and `/actuator/metrics` endpoints.
  5. Add `.anyRequest().authenticated()`.
* **Verification Test:** `SecurityPenetrationTest.java` asserting HTTP 401 on unauthenticated calls.

#### Task SEC-02: `V4__pci_remediation_and_tokenization.sql`
* **Location:** `flyo-rescue-demo/backend/src/main/resources/db/migration/V4__pci_remediation_and_tokenization.sql`
* **Changes:**
  ```sql
  ALTER TABLE virtual_cards DROP COLUMN IF EXISTS cvv;
  ALTER TABLE virtual_cards DROP COLUMN IF EXISTS card_number;
  ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS card_token_id VARCHAR(128) NOT NULL;
  ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS last_four VARCHAR(4) NOT NULL;
  ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS card_brand VARCHAR(32) DEFAULT 'Mastercard';
  CREATE UNIQUE INDEX IF NOT EXISTS idx_virtual_cards_token ON virtual_cards(card_token_id);
  ```
* **Verification Test:** Database migration test asserting `cvv` column does not exist in schema metadata.

#### Task SEC-03: Forensic PCI Data & Log Audit
* **Scope:** Inspect all historical Flyway seed scripts, test resources, and logs.
* **Finding:** Zero real cardholder data has ever entered the repository. All existing card rows are synthetic demo strings (`411111******1111`, `CVV 891`) created by [`FlyoDataSeeder.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/FlyoDataSeeder.java).
* **Action:** Update `FlyoDataSeeder.java` to generate tokenized records (`tok_seed_1234`, `last_four: "1111"`) with zero CVV references.

#### Task SEC-04: Production Route Isolation
* **Location:** [`application-prod.properties`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/resources/application-prod.properties) and [`MockAirlineController.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/controller/MockAirlineController.java)
* **Changes:**
  1. Add `@Profile("dev")` to `MockAirlineController.java` to completely unmap `/mock-airline/**` in production.
  2. Set `spring.h2.console.enabled=false` explicitly in `application-prod.properties`.
  3. Expose only `health,info` under `management.endpoints.web.exposure.include`.

---

## 4. Stage 0 Regression Test Suite Requirements

Before Stage 0 can be declared complete, the following automated regression suite must execute with 100% pass rate:

1. **`SecurityFilterRegressionTest`:**
   - `GET /api/cases/C1` without header $\to$ Assert `HTTP 401 Unauthorized`.
   - `POST /api/cases/C1/approve` without header $\to$ Assert `HTTP 401 Unauthorized`.
   - `POST /api/payments/issue-card` without header $\to$ Assert `HTTP 401 Unauthorized`.
   - `GET /h2-console` in prod profile $\to$ Assert `HTTP 404 Not Found`.
2. **`PciTokenizationRegressionTest`:**
   - Attempt to persist an entity containing a CVV field $\to$ Compilation / Schema rejection.
   - Verify `VirtualCardRepository` persists and retrieves `card_token_id` and `last_four`.
3. **`BaselineDomainLogicRegressionTest`:**
   - Execute all 116 Spring Boot domain tests with authenticated mock security context to confirm that state transitions, MCT rules, and evidence reconciliation remain 100% unbroken.
   - Execute all 26 Python ML unit tests to confirm drift and monitoring endpoints remain intact.
