# FLYO — STAGE 4 OUTBOX & SAGA RELIABILITY REPORT
## Transactional Outbox, Persistent Saga Coordinator & Lease Recovery

**Classification:** Flyo Distributed Systems Specification  
**Version:** 4.0.0-PROD-STAGE4  
**Date:** November 2026  
**Audited Location:** `flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/saga/`

---

## 1. Reliability Architecture Overview

Flyo eliminates distributed dual-write inconsistencies between PostgreSQL database operations and external APIs (Airline Portals, Payment Gateways, WhatsApp, CRM) via the **Transactional Outbox Pattern** paired with a **Persistent Distributed Saga Coordinator**.

```
Database Transaction Boundary
┌────────────────────────────────────────────────────────┐
│  UPDATE rescue_cases SET state = 'APPROVED' ...        │
│  INSERT INTO saga_executions (status='RUNNING', ...)   │
│  INSERT INTO outbox_events (event_type='SAGA_INIT')    │
└────────────────────────────────────────────────────────┘
                          │
             Polling / Dequeue Worker
                          ▼
            [Lease Lock Acquired: worker-1]
                          │
               Execute Saga Step 1 (VCC)
                          │
               Execute Saga Step 2 (Booking)
                          │
                          ├─────────────────► [Timeout] ──► Step 2B (Reconcile)
                          ▼                                     │
               Step 3A (Verify E-Ticket) ◄──────────────────────┘
                          │
                          ├─────────────────► [Ticket Void] ─► State: MANUAL_INTERVENTION
                          ▼
               Step 3B (Settle VCC)
                          │
               Step 3C (State: DONE)
                          │
               Step 3D (WhatsApp) & Step 3E (CRM Sync)
                          │
               Release Lease Lock & Publish Outbox Event
```

---

## 2. Worker Concurrency & Lease Recovery

### 2.1. Distributed Lease Semantics
- **Worker Locking:** When a worker picks up an outbox event or saga step, it executes an atomic optimistic update:
  ```sql
  UPDATE saga_executions
  SET status = 'RUNNING', locked_by = :workerId, locked_at = NOW(), attempt_count = attempt_count + 1
  WHERE saga_id = :sagaId AND (locked_by IS NULL OR locked_at < :leaseExpirationThreshold);
  ```
- **Lease Timeout:** Defaults to 60 seconds.
- **Zombie Worker Recovery:** If worker-1 crashes or experiences a JVM freeze, the lease expires. Worker-2 re-claims the saga without duplicate step execution due to step idempotency keys.

### 2.2. Retry & Exponential Backoff Policy
- **Transient Failures (HTTP 502/503/504, Socket Timeout):** Retried with exponential backoff:
  $$\text{Delay} = 3 \times 2^{\text{attempt}} \text{ seconds (Attempts 1 to 3)}$$
- **Max Attempts:** 3. Upon exhaustion, saga transitions to `triggerCompensation()`.
- **Non-Retryable Failures (Card Limit Exceeded, Passenger Name Mismatch):** Fails immediately without retry, triggering backward compensation.

---

## 3. Backward Compensation & Rollback Mechanics

When consequential execution fails permanently:
1. **Virtual Card Revocation:** If a VCC was authorized in Step 1, `paymentSettlementService.voidAuthorization(caseId, cardId, amount, reason)` voids the authorization immediately to free corporate credit lines.
2. **Case Escalation:** The rescue case is transitioned to `FAILED_ESCALATED` with full root-cause diagnostic details persisted in `error` and `manual_next_step`.
3. **Outbox Notification:** A durable `SAGA_FAILED` event is staged in `outbox_events` to notify monitoring systems and operator consoles.
4. **Audit Trail:** Immutable record written to `state_transitions` and `audit_events`.

---

## 4. Empirical Reliability Test Evidence

From `Stage4EndToEndEnterpriseWorkflowTest` and `OutboxAndSagaIntegrationTest`:
- **Scenario 10 (Idempotency):** Duplicate saga initiation for the same approved option returns the exact existing saga instance (`s1.getSagaId() == s2.getSagaId()`).
- **Scenario 11 (Ambiguous Timeout):** When airline gateway times out, saga enters `WAITING_FOR_EXTERNAL_RESULT` at step `RECONCILE_BOOKING` without duplicate booking commands.
- **Scenario 12 (Late Reconciliation):** Carrier host reconciliation finds existing booking and smoothly recovers saga into `DONE`.
- **Scenario 17 (Ticket Validation Failure):** Voided e-ticket halts saga into `MANUAL_INTERVENTION_REQUIRED`.
- **Scenario 18 (Messaging Resilience):** WhatsApp messaging failure does not roll back confirmed booking.
- **Scenario 20 (Payment Limit Failure):** ₹150,000 fare delta exceeds spending limit; saga marks `FAILED`, triggers compensation, and prevents false settlement.
