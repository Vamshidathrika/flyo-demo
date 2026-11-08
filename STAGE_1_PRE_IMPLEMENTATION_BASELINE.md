# FLYO — STAGE 1 PRE-IMPLEMENTATION BASELINE & AUDIT VERIFICATION

**Document Version:** 1.0.0  
**Status:** Baseline Established & Verified  
**Date:** November 6, 2026  
**Engineering Discipline:** Principal Distributed Systems Architect, Senior Spring Boot Engineer, PostgreSQL Database Architect  
**Scope:** Stage 1 Pre-Implementation Audit & Baseline Reconciliation  

---

## 1. Stage 0 Security Hardening Verification

Prior to designing and implementing Stage 1, an independent audit of the repository, source code, database migrations, security configuration, and test suites was conducted.

### 1.1 Control Verification Audit Table

| Control Item | Audit Method & File Target | Verified Finding | Status |
| :--- | :--- | :--- | :--- |
| **1. JWT Authentication** | `SecurityConfig.java`, `JwtAuthenticationFilter.java` | Anonymous access to `/api/cases/**`, `/api/bookings/**`, `/api/payments/**` returns HTTP 401 Unauthorized. Filter enforces HMAC-SHA256 tokens. | **VERIFIED (PASS)** |
| **2. Role Authorization (RBAC)** | `SecurityConfig.java`, `SecurityFilterTest.java` | `/api/payments/**` is restricted strictly to `ROLE_ADMIN`. Users with `ROLE_OPERATOR` receive HTTP 403 Forbidden. | **VERIFIED (PASS)** |
| **3. Mock Airline Isolation** | `MockAirlineController.java` | Controller is marked with `@Profile("!prod")`. Excluded from production container configurations. | **VERIFIED (PASS)** |
| **4. PCI Cardholder Data Purge** | `VirtualCard.java`, `V4__pci_remediation.sql`, `SecurityFilterTest.java` | DDL dropped `cvv` and `card_number`. Entity has zero CVV/PAN fields (verified by reflection throwing `NoSuchFieldException`). Legacy getter annotated with `@JsonIgnore`. | **VERIFIED (PASS)** |
| **5. Migration V4 Integrity** | `V4__pci_remediation.sql` | Executed cleanly against H2 in-memory test database and PostgreSQL 14 DDL parser. Converted existing records to tokenized surrogate format. | **VERIFIED (PASS)** |
| **6. ML Monitoring Auth** | `ml/service/main.py`, `PredictionServiceClient.java` | `secrets.compare_digest` used for constant-time header verification of `X-Flyo-Monitoring-Key`. Fails closed if key is unset in production. | **VERIFIED (PASS)** |
| **7. Login Rate Limiting** | `LoginRateLimiter.java`, `AuthController.java` | 5 consecutive failed attempts trigger a 15-minute lockout with HTTP 429. **Documented Multi-Instance Limitation:** Current implementation uses `ConcurrentHashMap` in JVM memory. In horizontally scaled multi-pod environments, rate limiting is per-pod; requires Redis/distributed cache for cluster-wide enforcement. | **VERIFIED WITH DOCUMENTED LIMITATION** |
| **8. Human Approval & Safety** | `FinancialAndBookingSafetyTest.java`, `CaseService.java` | Rebooking without explicit prior human approval (`AWAITING_APPROVAL`) throws `ApprovalRequiredException`. Failed rebookings transition to `FAILED_ESCALATED` with zero synthetic PNRs. | **VERIFIED (PASS)** |

### 1.2 Payment Provider Connection Reality Check
> [!CRITICAL]
> **Definitive Verification of Payment Tokens:**
> The generated token `tok_sandbox_<uuid>` emitted by `SandboxPaymentAdapter.java` is an internal synthetic surrogate token designed strictly for development and sandbox isolation.
>
> **Flyo is NOT connected to a live external payment gateway (e.g. Stripe Issuing, Marqeta, Adyen, or corporate banking VCC).**
>
> Any claims of production payment processing are invalid until Stage 4 (Live Payment Provider & Gateway Integration).

### 1.3 Test Count Re-conciliation & Arithmetic
A meticulous breakdown of test counts prevents double-counting:

| Suite / Runner | Scope | Executed Test Count | Pass Rate | Notes |
| :--- | :--- | :---: | :---: | :--- |
| **Java Spring Boot Surefire** | 29 Test Classes in `flyo-rescue-demo/backend` | **124** | 100% | Includes all unit, slice, integration, and security tests. |
| *-- Of which: Dedicated Security Tests* | `SecurityFilterTest.java` | *8* | *100%* | *Sub-set of the 124 Java tests above (NOT additional).* |
| **Python ML Microservice Unittest** | 4 Test Modules in `ml/tests` | **26** | 100% | Independent Python test suite executing via `ml/.venv`. |
| **Total Automated Tests** | **Executable Test Suite** | **150** | **100%** | **124 Java + 26 Python = 150 Automated Tests.** |
| **TypeScript Static Check** | `flyo-rescue-demo/agent` (`tsc --noEmit`) | N/A | Clean (0 err) | Static compilation verification; **not an automated test**. |
| **Playwright Agent Scripts** | Browser automation in `agent/` | Manual / On-Demand | N/A | Scripted browser workflows against mock portal. |

---

## 2. Existing Outbox & State Transition Architecture

### 2.1 Outbox Schema (`V1__initial_production_schema.sql`)
The initial schema defines `outbox_events` as:
```sql
CREATE TABLE IF NOT EXISTS outbox_events (
    id VARCHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    error_message TEXT
);
CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox_events(status, created_at);
```

### 2.2 Existing Event Producers
- Currently, only `CaseStateMachineService.transition()` writes to `outbox_events`.
- Every case transition emits `eventType: "CASE_STATE_CHANGED"`, with payload:
  `{"caseId":"...","fromState":"...","toState":"...","actor":"..."}`.
- **Critical Architectural Gap:** There is **NO** background polling worker, message dispatcher, or queue publisher listening to `outbox_events`. `OutboxService.getPendingEvents()` is never called by any active component. Events accumulate in `PENDING` state indefinitely.

### 2.3 Existing Rebooking & Payment Execution Flow
In `RebookingOrchestrationService.executeRebookingWorkflow()`:
- The entire rebooking execution runs inside a single Spring `@Transactional` boundary.
- **Severe Reliability Flaw:** It invokes `rebookService.executePlaywrightRebook(...)` (an external/slow Playwright automation call) **inside the open database transaction**.
- If the browser automation or airline provider takes 15–30 seconds, the database connection is held open, consuming HikariCP pool slots.
- If a timeout occurs after the airline issued a booking, the transaction rolls back, abandoning the local state while the airline has already created a PNR.
- There is no persistent saga state machine to track multi-step progress, no retry scheduler with exponential backoff, and no distributed lease locking for concurrent dispatchers.

---

## 3. Existing State Machine Integration (44 States)

In [`CaseStateMachineService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/CaseStateMachineService.java), Flyo defines 44 state constants covering the complete disruption recovery lifecycle:
- Core operational flow:
  `DETECTED` $\to$ `VALIDATING_IMPACT` $\to$ `IMPACT_CONFIRMED` $\to$ `SEARCHING_ALTERNATIVES` $\to$ `RANKING_OPTIONS` $\to$ `OPTIONS_READY` $\to$ `AWAITING_APPROVAL` $\to$ `APPROVED` $\to$ `COMMUNICATING_PASSENGER` $\to$ `OFFER_SENT` $\to$ `PASSENGER_ACCEPTED` $\to$ `SECURING_PAYMENT` $\to$ `PAYMENT_INSTRUMENT_READY` $\to$ `REBOOKING` $\to$ `VERIFYING_BOOKING` $\to$ `VERIFYING_TICKET` $\to$ `DONE`.
- Exceptional flows:
  `REBOOKING_FAILED`, `FAILED_ESCALATED`, `ESCALATED`, `PAYMENT_FAILED`, `EXPIRED`, `CANCELLED_BY_OPERATOR`.

Stage 1 distributed saga orchestration must **integrate seamlessly** with these 44 states rather than creating an uncoordinated competing state machine.

---

## 4. Identified Stage 1 Reliability Gaps to Remediate

1. **Transactional Outbox Dispatcher Missing:** No scheduler, no PostgreSQL `SKIP LOCKED` concurrency, no lease management, no backoff/jitter, and no dead-letter queue.
2. **Outbox Schema Lacks Distributed Envelope Attributes:** Missing `event_version`, `tenant_id`, `correlation_id`, `causation_id`, `idempotency_key`, `available_at`, `locked_by`, and `locked_at`.
3. **Monolithic Open Transactions on External I/O:** `executeRebookingWorkflow` blocks HikariCP connections during remote network operations.
4. **No Saga State Persistence:** Multi-step operations (`SECURING_PAYMENT` $\to$ `REBOOKING` $\to$ `VERIFYING_BOOKING`) cannot resume or recover if JVM terminates mid-flight.
5. **No Ambiguous Response Reconciliation:** Network timeouts on rebooking or payment are not reconciled prior to retry, creating double-booking risk.
6. **No Idempotency Guards on Sagas:** Duplicate approval clicks or repeated webhook deliveries can spawn duplicate operations.

---

## 5. Stage 1 Execution Blueprint

- **Step A:** Baseline established (this document).
- **Step B:** Architecture, Sequence Flows, and Saga State Machine design (`STAGE_1_ARCHITECTURE_AND_SEQUENCE_FLOWS.md`).
- **Step C:** Flyway Migration `V5__transactional_outbox_and_saga.sql` (additive, non-destructive, PostgreSQL/H2 compatible).
- **Step D:** Outbox Dispatcher Implementation (`OutboxDispatcherWorker.java`, `FOR UPDATE SKIP LOCKED`, exponential backoff, dead-lettering).
- **Step E:** Persistent Saga Orchestrator (`RebookingSagaService.java`, step tracking, compensation handling, recovery from JVM restart).
- **Step F:** External Integration Boundaries & Idempotent Adapters (`ExternalProviderResponse.java`, reconciliation contracts).
- **Step G:** Observability & Operator Recovery API (`SagaMonitoringController.java`, `/api/admin/outbox/**`, `/api/admin/sagas/**`).
- **Step H:** Comprehensive Failure-Recovery & Concurrency Verification Test Suite.
