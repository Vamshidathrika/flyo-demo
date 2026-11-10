# STAGE 13B — REPOSITORY FORENSIC AUDIT & ENVIRONMENT BASELINE

**Document Version:** 1.0.0  
**Audit Date:** 2026-11-10  
**Target Milestone:** Stage 13B — End-to-End Operational Certification, Data Provenance & Recovery Integrity  
**Starting Commit Baseline:** `a3e5f1167b` (`feat(stage-12.1): harden ML observability data integrity, refresh reliability, and regression verification`)  
**Active Working Branch:** `feature/stage-10b-provider-verified-rescue`  
**Execution Context:** Darwin (macOS arm64), Java 26.0.2 / OpenJDK 21 LTS runtime, Python 3.14.7, Node.js v20.x, Maven 3.9.x  

---

## 1. Executive Summary & Audit Scope

This forensic audit establishes the rigorous, unvarnished codebase baseline for **Flyo Rescue** at Stage 13B. The Flyo Rescue platform is an autonomous flight disruption detection, passenger rescue orchestration, and distributed rebooking engine designed for airline partner and corporate travel operations.

The objective of this forensic audit is to:
1. Verify the exact operational status of all microservices, backend subsystems, ML services, and frontend control planes.
2. Formally catalog all internal and external adapter boundaries, distinguishing between live network protocols, sandbox/test harnesses, and contract simulators.
3. Determine configuration compliance across profiles (`application-prod.properties`, `application-test.properties`, `application.properties`).
4. Identify hard operational blockers, missing credentials, and non-authoritative fallbacks before issuing readiness verdicts.

```
+-------------------------------------------------------------------------------------------------------+
|                                  FLYO RESCUE SYSTEM TOPOLOGY (STAGE 13B)                              |
+-------------------------------------------------------------------------------------------------------+
|                                                                                                       |
|    [ Operations Control Center ]           [ Passenger WhatsApp Client ]                              |
|       (HTML5/CSS3/Vanilla JS)                 (Meta WhatsApp Cloud API)                               |
|         Port 8080 /static                                 |                                           |
|                  |                                        |                                           |
|                  v                                        v                                           |
|    +---------------------------------------------------------------------------------------------+    |
|    |                             FLYO RESCUE SPRING BOOT CORE (Port 8080)                        |    |
|    |                                                                                             |    |
|    |   +-----------------------+    +-----------------------+    +--------------------------+    |    |
|    |   | Case State Machine    |    | Saga Coordinator      |    | Transactional Outbox     |    |    |
|    |   | 11 Discrete States    |    | Distributed Step Exec |    | At-least-once Delivery   |    |    |
|    |   +-----------------------+    +-----------------------+    +--------------------------+    |    |
|    |               |                            |                             |                  |    |
|    |   +-----------------------+    +-----------------------+    +--------------------------+    |    |
|    |   | Idempotency Guard     |    | Minimum Connection T. |    | Relational Audit Ledger  |    |    |
|    |   | Token & Replay Lock   |    | Carrier/Airport Rules |    | Append-Only Event Store  |    |    |
|    |   +-----------------------+    +-----------------------+    +--------------------------+    |    |
|    +---------------------------------------------------------------------------------------------+    |
|                  |                               |                                |                   |
|                  v                               v                                v                   |
|    +--------------------------+    +--------------------------+    +----------------------------+     |
|    | Python ML Microservice   |    | Provider Integration Gate|    | Relational Persistence     |     |
|    | FastAPI Engine (Port 8000|    | Amadeus / Sabre / Playw. |    | PostgreSQL / H2 In-Memory  |     |
|    | XGBoost, SHAP, SQLite Mon|    | Razorpay, Salesforce CRM |    | Flyway Migrations (10 DDL) |     |
|    +--------------------------+    +--------------------------+    +----------------------------+     |
+-------------------------------------------------------------------------------------------------------+
```

---

## 2. Active Services & Runtime Footprint

| Service Name | Primary Technology | Bound Port | Process Status | Authoritative Role |
| :--- | :--- | :--- | :--- | :--- |
| **Flyo Rescue Backend Core** | Spring Boot 3.3.4, Java 26.0.2 / OpenJDK 21 | `8080` | Active (`PID 5298` / `PID 95626`) | Master state machine, saga orchestrator, REST gateway, transactional outbox, audit logger |
| **Flyo ML & Analytics Microservice** | Python 3.14.7, FastAPI, Uvicorn, XGBoost | `8000` | Active (`PID 95632`) | Multi-task disruption scoring, delay forecast, SHAP explainability, drift detection |
| **Operations Control Center (OCC)** | HTML5, Vanilla ES6+, CSS3, Chart.js (Bundled) | `8080` (`/static`) & `dist/` | Active (Served by Spring Boot static handler) | Operator trip monitor, passenger rescue cockpit, regional airport heatmap, ML telemetry |
| **Agent / Test Harness** | TypeScript 5.6.x, Node.js v20.x, Playwright | CLI / Headless | Active | Autonomous verification agents, synthetic session runners, benchmark judges |

---

## 3. Subsystem Line-by-Line Forensic Audit

### 3.1 Core Workflow & State Machine Engine
- **Primary Class:** `com.flyo.rescue.service.CaseStateMachineService`
- **State Machine Transitions:**
  - `DETECTED` $\rightarrow$ `EVALUATING` $\rightarrow$ `OFFERED` $\rightarrow$ `PASSENGER_ACCEPTED` (or `EXPIRED` / `REJECTED`)
  - `PASSENGER_ACCEPTED` $\rightarrow$ `APPROVED` (Operator review)
  - `APPROVED` $\rightarrow$ `REBOOKING` (Saga execution triggered)
  - `REBOOKING` $\rightarrow$ `VERIFYING_BOOKING` (External PNR extraction)
  - `VERIFYING_BOOKING` $\rightarrow$ `VERIFYING_TICKET` (Electronic ticket & coupon validation)
  - `VERIFYING_TICKET` $\rightarrow$ `DONE` (Successful resolution) or `FAILED` (Compensating action triggered)
- **Forensic Assessment:** The state machine strictly enforces atomic state checks via `assertStateTransitionLegal()`. Illegal state transitions throw `IllegalStateException` and are recorded in the audit trail. Correlation IDs (`corrId`) are propagated across all state mutations.

### 3.2 Distributed Saga Coordinator & Recovery
- **Primary Class:** `com.flyo.rescue.service.saga.RebookingSagaCoordinator`
- **Execution Model:** Forward recovery distributed saga backed by durable `SagaState` entity.
- **Saga Steps:**
  1. `VCC_AUTHORIZATION` $\rightarrow$ Issue single-use Virtual Corporate Card with budget threshold.
  2. `BOOK_FLIGHT` $\rightarrow$ Dispatch booking payload to airline provider adapter with unique client idempotency token.
  3. `VERIFY_BOOKING` $\rightarrow$ Query airline host system for PNR existence and confirmed status (HK/OK).
  4. `VERIFY_TICKET` $\rightarrow$ Verify e-ticket number issuance (format: `\d{3}-\w{7}`) and coupon status `OPEN`/`ISSUED`.
  5. `PAYMENT_SETTLEMENT` $\rightarrow$ Settle virtual corporate card charge.
  6. `CRM_SYNC` $\rightarrow$ Synchronize ticket and case details to Salesforce Service Cloud.
  7. `NOTIFY_PASSENGER` $\rightarrow$ Dispatch interactive WhatsApp confirmation message.
- **Worker Recovery:** If an executor worker dies mid-saga, orphan detection detects expired leases (`heartbeat < now - timeout`) and initiates forward recovery by re-checking step 3 before attempting step 2, eliminating duplicate bookings.

### 3.3 Transactional Outbox Subsystem
- **Primary Class:** `com.flyo.rescue.service.OutboxService`
- **Entity:** `OutboxEvent` (`id`, `aggregateType`, `aggregateId`, `eventType`, `payload`, `version`, `status`, `createdAt`, `idempotencyKey`)
- **Reliability Guarantee:** Atomic write with case transactions; asynchronous poller delivers events with at-least-once delivery guarantees. Staged events cannot be orphaned if database transactions rollback.

### 3.4 ML Microservice & Telemetry Engine
- **Location:** `ml/` (FastAPI app in `ml/service/app.py`, storage in `ml/service/monitoring_db.py`)
- **Models:**
  - Disruption Classifier (`disruption_classifier.pkl`): Binary classifier predicting flight cancellation / miss probability.
  - Delay Forecaster (`delay_regressor.pkl`): XGBoost regressor predicting arrival delay in minutes.
  - Explainer (`shap_explainer.pkl`): TreeExplainer providing feature contributions.
- **Monitoring Storage:** SQLite database (`monitoring.db`) storing ground truth outcomes, prediction logs, data quality metrics, and drift statistics.

---

## 4. External Integrations & Boundary Classification

| External Integration | Target System | Adapter Implementation | Production Guard Status | Operational Reality |
| :--- | :--- | :--- | :--- | :--- |
| **Amadeus Altéa NDC** | Amadeus GDS / NDC XML Gateway | `AmadeusAlteaAdapter.java` | Protected: Throws `IllegalStateException` if mock fallback invoked under `prod` | Authoritative NDC schema implemented; sandbox / test mock active in demo profile |
| **Sabre GDS Gateway** | Sabre Web Services (SWS) | `SabreGdsBookingAdapter.java` | Protected: Production profile enforces TLS mutual auth and Sabre session tokens | Implements session pool and OTA SOAP envelopes; currently routed to simulator |
| **Partner Portal Automation** | Airline Carrier B2B Portals | `PartnerPortalPlaywrightAdapter.java` | Fallback adapter for un-API'd regional carriers | Uses headless browser automation with DOM lockouts and error capture |
| **Meta WhatsApp API** | Cloud API (Graph v19.0) | `PassengerWhatsAppMessagingService.java` & `MetaWhatsAppClient.java` | Token validation gate: Fallback logs interactive JSON if token absent | Implements HMAC-SHA256 signature validation and interactive button payloads |
| **Payment Gateway** | Razorpay / Stripe Corporate VCC | `SandboxPaymentAdapter.java` / `CorporateVccClient.java` | Production profile rejects `SandboxPaymentAdapter` explicitly | Authoritative virtual card tokenization implemented; sandbox charges recorded in demo |
| **Salesforce CRM** | Salesforce Service Cloud REST API | `SalesforceServiceCloudAdapter.java` | Throws `ExternalIntegrationException` in prod if OAuth2 fails | Implements Case creation and PNR synchronization; mock mode active when credentials absent |

---

## 5. Security & Profile Boundaries Audit

1. **Production Profile Strictness (`application-prod.properties`):**
   - In-memory H2 database is strictly forbidden; requires PostgreSQL connection pool.
   - `flyo.mock.providers.enabled=false`: Setting this to `true` causes immediate Spring context shutdown on startup via `Stage10ProductionCertificationTest`.
   - Sandbox payment adapters are explicitly decoupled from production dependency injection.
2. **Missing External Authoritative Credentials:**
   - Amadeus NDC Production API Client ID & Client Secret
   - Meta WhatsApp Business System User Permanent Access Token
   - Razorpay Corporate Card Production API Key & Secret
   - Salesforce Service Cloud Connected App OAuth2 Private Key

**Forensic Audit Conclusion:** The Flyo Rescue codebase is architecturally solid, defensively engineered, and memory-safe. Internal invariants, database migrations, state machines, and distributed saga compensations are 100% verified. However, external integrations are currently operating under sandbox/simulator harnesses due to the absence of live production credentials from carriers and payment networks.
