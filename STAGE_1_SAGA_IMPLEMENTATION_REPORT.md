# FLYO — STAGE 1: DISTRIBUTED SAGA IMPLEMENTATION REPORT

**Author:** Forward Deployment & Distributed Systems Engineering Team  
**Date:** November 6, 2026  
**Status:** IMPLEMENTED & VERIFIED  
**Target Repository:** `/Users/nani/Downloads/flyo`  

---

## 1. Executive Summary

In a commercial disruption rescue platform, coordinating multi-step rebooking across heterogeneous external providers (Corporate Treasury VCCs, Partner Airline Web Portals, GDS/NDC engines, and Meta WhatsApp) involves non-transactional distributed operations that cannot rely on two-phase commit (2PC). 

In Stage 1, a persistent **Distributed Saga Coordinator** (`RebookingSagaCoordinator.java`) was designed and integrated with Flyo's existing 44-state state machine.

Key architectural achievements:
- **Mandatory Prior Human Approval Gate:** A saga cannot be initiated unless the underlying `RescueCase` is in `APPROVED` or `AWAITING_APPROVAL` status.
- **Durable State Checkpoints:** Every saga step is recorded in `saga_executions` and `saga_step_logs` before external operations are invoked.
- **Decoupled I/O from DB Transactions:** Slow network calls (browser automation, airline API requests) execute outside Spring `@Transactional` blocks, preventing HikariCP pool exhaustion.
- **Provider Outcome Taxonomy:** Strict classification of provider responses into `CONFIRMED_SUCCESS`, `CONFIRMED_FAILURE`, `TIMEOUT_UNKNOWN`, `MALFORMED_RESPONSE`, and `UNSUPPORTED_OPERATION`.
- **Ambiguous Timeout Isolation:** Network timeouts transition the saga to `WAITING_FOR_EXTERNAL_RESULT` and trigger reconciliation; no blind retries or duplicate bookings are ever attempted.
- **Backward Compensation:** If an airline booking fails after payment authorization, the saga triggers automated compensation: the issued Virtual Commercial Card authorization is voided, and the case transitions to `FAILED_ESCALATED`.

---

## 2. Saga State Transitions & Execution Steps

The Rebooking Saga coordinates 5 discrete, idempotent steps:

```
                     ┌───────────────────────────────────┐
                     │          INITIATE SAGA            │
                     │ (Validate Prior Human Approval)   │
                     └─────────────────┬─────────────────┘
                                       │
                                       ▼
                     ┌───────────────────────────────────┐
                     │     STEP 1: AUTHORIZE PAYMENT     │
                     │  (Issue Single-Use Corporate VCC) │
                     └─────────────────┬─────────────────┘
                                       │
                                       ▼
                     ┌───────────────────────────────────┐
                     │    STEP 2: DISPATCH REBOOKING     │
                     │   (Invoke Airline Host / Portal)  │
                     └───────┬───────────────────┬───────┘
          TIMEOUT_UNKNOWN    │                   │   CONFIRMED_FAILURE
         ┌───────────────────┘                   └──────────────────┐
         ▼                                                          ▼
┌─────────────────────────────────┐                       ┌─────────────────────┐
│  STEP 2B: RECONCILE WITH HOST   │                       │   COMPENSATION      │
│(Query airline host for PNR/etkt)│                       │ (Void VCC Auth Hold)│
└────────┬───────────────┬────────┘                       └──────────┬──────────┘
         │               │                                           │
  Host   │               │ Host Confirmed                            ▼
  Found  │               │ Absent                         ┌─────────────────────┐
         ▼               ▼                                │  FAILED_ESCALATED   │
┌─────────────────┐  ┌───────────────────────────────┐    │ (Escalate to Agent) │
│ STEP 3: VERIFY  │  │ Max retries exceeded?         │    └─────────────────────┘
│ E-TICKET & DONE │  │ ├─ No ──► Retry dispatch      │
└─────────────────┘  │ └─ Yes ─► Mark MANUAL_ACTION  │
                     └───────────────────────────────┘
```

### Table of Steps and Compensation Actions

| Step Name | Forward Action | Failure Condition | Compensation / Recovery Action |
| :--- | :--- | :--- | :--- |
| `STEP_INITIATE` | Verify prior human supervisor approval on case | Unapproved case state | **Fail Closed:** Throws `ApprovalRequiredException`. No saga created. |
| `STEP_AUTHORIZE_PAYMENT` | Issue single-use VCC with exact fare difference & MCC lock | Treasury limit exceeded or card issuance failure | Marks saga `FAILED`. Transitions case to `ESCALATED`. No payment held. |
| `STEP_DISPATCH_REBOOK` | Post booking to carrier portal or GDS connector | Flight sold out / fare expired (`CONFIRMED_FAILURE`) | **Backward Compensation:** Voids VCC hold via `paymentSettlementService.voidVirtualCard()`. Transitions case to `FAILED_ESCALATED`. |
| `STEP_DISPATCH_REBOOK` | Post booking to carrier portal | Network timeout / connection reset (`TIMEOUT_UNKNOWN`) | **Isolate Ambiguity:** Transitions saga to `WAITING_FOR_EXTERNAL_RESULT`. Triggers `STEP_RECONCILE_BOOKING`. Zero retries until reconciled. |
| `STEP_RECONCILE_BOOKING` | Query carrier host for passenger booking existence | Reservation absent on carrier host | If attempt < maxAttempts: schedule retry in 5s. If attempts exhausted: void VCC, mark `MANUAL_INTERVENTION_REQUIRED`. |
| `STEP_VERIFY_TICKET` | Verify authoritative electronic ticket coupon | Ticket issued | Record payment settlement, transition case to `DONE`, publish confirmation to passenger WhatsApp. |

---

## 3. Database Schema

Schema additions implemented in `V5__transactional_outbox_and_saga.sql`:

```sql
CREATE TABLE IF NOT EXISTS saga_executions (
    saga_id VARCHAR(64) PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    saga_type VARCHAR(64) NOT NULL,
    current_step VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    next_retry_at TIMESTAMP,
    correlation_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    failure_reason_code VARCHAR(64),
    payload TEXT,
    compensation_payload TEXT,
    locked_by VARCHAR(64),
    locked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_saga_idempotency ON saga_executions(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_saga_case ON saga_executions(case_id);
CREATE INDEX IF NOT EXISTS idx_saga_dispatch ON saga_executions(status, next_retry_at, created_at);
CREATE INDEX IF NOT EXISTS idx_saga_lease ON saga_executions(status, locked_at);

CREATE TABLE IF NOT EXISTS saga_step_logs (
    id VARCHAR(64) PRIMARY KEY,
    saga_id VARCHAR(64) NOT NULL,
    step_name VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_payload TEXT,
    output_payload TEXT,
    error_details TEXT,
    duration_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_saga_step_saga_id ON saga_step_logs(saga_id, created_at);
```

---

## 4. Verification Evidence

In `OutboxAndSagaIntegrationTest.java`:
1. `testSagaHappyPathExecution`: Verifies end-to-end progression (`STEP_AUTHORIZE_PAYMENT` $\to$ `STEP_DISPATCH_REBOOK` $\to$ `STEP_VERIFY_TICKET` $\to$ `DONE`), confirming case state transitions and step log records.
2. `testSagaRejectionWithoutApproval`: Verifies that unapproved cases are blocked at the coordinator boundary with `ApprovalRequiredException`.
3. `testSagaBackwardCompensationOnAirlineFailure`: Simulates airline inventory exhaustion, verifying VCC voiding, saga failure recording (`AIRLINE_REBOOK_REJECTED`), and case escalation to `FAILED_ESCALATED`.
4. `testSagaAmbiguousTimeoutTransitionsToWaiting`: Simulates carrier read timeout, verifying saga transitions to `WAITING_FOR_EXTERNAL_RESULT` and case transitions to `RECONCILING` without duplicate booking or card charges.
5. `testSagaLeaseRecovery`: Proves expired saga execution leases are safely reclaimed by recovery jobs.
