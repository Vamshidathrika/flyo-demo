# FLYO — STAGE 1: TRANSACTIONAL OUTBOX IMPLEMENTATION REPORT

**Author:** Forward Deployment & Distributed Systems Engineering Team  
**Date:** November 6, 2026  
**Status:** IMPLEMENTED & VERIFIED  
**Target Repository:** `/Users/nani/Downloads/flyo`  

---

## 1. Executive Summary

In Stage 0, dual-write vulnerabilities were identified where database state transitions occurred independently of event publishing. In Stage 1, an enterprise-grade, asynchronous **Transactional Outbox Dispatcher** was implemented directly within the Flyo backend. 

The implementation guarantees:
- **At-least-once delivery** of all domain and disruption state change events.
- **Strict dual-write consistency** by staging versioned event envelopes inside the local business transaction.
- **High-throughput, non-blocking polling** using PostgreSQL `FOR UPDATE SKIP LOCKED` semantics (with native H2 compatibility in tests).
- **Worker lease locking** with automatic recovery of abandoned events from crashed worker nodes.
- **Exponential backoff with randomized jitter** and deterministic dead-letter queue (DLQ) routing after 5 failed dispatch attempts.
- **Idempotent event publishing** through SHA-256 / composite uniqueness constraints on `idempotency_key`.

---

## 2. Table Schema & Versioned Envelope

The underlying relational schema in `outbox_events` was upgraded in `V5__transactional_outbox_and_saga.sql`:

```sql
CREATE TABLE IF NOT EXISTS outbox_events (
    id VARCHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_version INT DEFAULT 1,
    tenant_id VARCHAR(64) DEFAULT 'default',
    correlation_id VARCHAR(64),
    causation_id VARCHAR(64),
    idempotency_key VARCHAR(128),
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT DEFAULT 5,
    available_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    locked_by VARCHAR(64),
    locked_at TIMESTAMP,
    last_error_code VARCHAR(64),
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_idempotency ON outbox_events(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_outbox_dispatch ON outbox_events(status, available_at, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_lease ON outbox_events(status, locked_at);
```

### JSON Event Envelope Format
Events staged in the outbox conform to the canonical event envelope:
```json
{
  "eventId": "2ea3ba4c-d57f-4dff-ac7a-33be33ed50ef",
  "eventType": "CASE_STATE_CHANGED",
  "eventVersion": 1,
  "aggregateType": "RESCUE_CASE",
  "aggregateId": "C1",
  "tenantId": "default",
  "correlationId": "corr_7f9c2d1e-8e4a-4b02-9a5c-6b3a284e9124",
  "causationId": "CMD_REBOOK_CASE",
  "idempotencyKey": "idem_RESCUE_CASE_C1_CASE_STATE_CHANGED_534830274277458",
  "payload": "{\"caseId\":\"C1\",\"previousState\":\"AWAITING_APPROVAL\",\"currentState\":\"REBOOKING\",\"flight\":\"FL301\"}",
  "createdAt": "2026-11-06T11:30:20.626",
  "availableAt": "2026-11-06T11:30:20.626"
}
```

---

## 3. Worker Architecture & Dispatch Loop

The outbox pipeline decouples local persistence from remote network I/O through `OutboxDispatcherWorker.java`:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Local Spring Transaction                        │
│                                                                        │
│   CaseStateMachineService.transition()                                 │
│        │                                                               │
│        ├─► INSERT INTO state_transitions ...                           │
│        ├─► UPDATE rescue_cases SET state = ...                         │
│        └─► INSERT INTO outbox_events (status='PENDING') ...            │
│                 ▲                                                      │
└─────────────────┼──────────────────────────────────────────────────────┘
                  │ Committed atomically
                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│               Asynchronous Outbox Dispatcher Worker                    │
│                                                                        │
│   1. Polling: SELECT ... FOR UPDATE SKIP LOCKED                        │
│               WHERE status='PENDING' AND available_at <= NOW()         │
│   2. Lease Claim: UPDATE outbox_events SET status='CLAIMED',           │
│                   locked_by='pod-x', locked_at=NOW()                   │
│   3. Dispatch (Outside DB Transaction):                                │
│        └─► DefaultOutboxEventConsumer.consume(event)                   │
│              - SAGA_INITIATED ──► RebookingSagaCoordinator             │
│              - CASE_STATE_CHANGED ──► WebSocket / SSE Broadcast        │
│              - REBOOK_COMPLETED ──► Meta WhatsApp Outbound             │
│   4. Completion: UPDATE outbox_events SET status='COMPLETED'           │
│   5. On Failure: UPDATE outbox_events SET status='PENDING',            │
│                  retry_count=retry_count+1, available_at=NOW()+backoff │
│                  (or status='DEAD_LETTER' if retry_count >= 5)         │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Concurrency, Leases & Recovery

### PostgreSQL SKIP LOCKED Semantics
`OutboxEventRepository.java` uses:
```java
@Query(value = "SELECT * FROM outbox_events e " +
       "WHERE e.status = 'PENDING' AND e.available_at <= :now " +
       "ORDER BY e.created_at ASC FOR UPDATE SKIP LOCKED", 
       nativeQuery = true)
List<OutboxEvent> findPendingEventsAvailableNative(@Param("now") LocalDateTime now, Pageable pageable);
```
With an H2-compatible fallback query for unit tests. When multiple container pods query the table concurrently, each pod claims an exclusive lock on distinct rows without waiting or blocking sibling worker threads.

### Lease Expiration Recovery
Workers acquire leases timestamped in `locked_at`. If a worker pod crashes mid-execution (OOMKilled, SIGKILL, hardware fault), `outboxService.recoverExpiredLeases(60)` runs every 30 seconds, detecting claims older than 60 seconds and returning them to `PENDING` with cleared locks.

### Exponential Backoff & Jitter
When an external endpoint or consumer fails, the dispatcher calculates exponential backoff:
$$\Delta t = \min\left(300, 2^{\text{retryCount}} \times 2\right) + \text{jitter}(0..2\text{s})$$

- Attempt 1: 4s + jitter
- Attempt 2: 8s + jitter
- Attempt 3: 16s + jitter
- Attempt 4: 32s + jitter
- Attempt 5: Exceeds `max_retries` (5) $\to$ `DEAD_LETTER`

---

## 5. Verification & Test Evidence

All outbox capabilities were tested and verified in `OutboxAndSagaIntegrationTest.java`:
1. `testOutboxBatchClaimingAndLeaseLocking`: Confirms atomic claiming by worker 1, locking, exclusion of worker 2, and clean completion.
2. `testOutboxIdempotencyDeduplication`: Verifies duplicate publication returns existing record without duplicate insertion.
3. `testOutboxExponentialBackoffAndDeadLetter`: Verifies backoff interval growth across 4 transient errors, with the 5th error routing the event to `DEAD_LETTER`.
4. `testOutboxLeaseExpirationRecovery`: Proves crashed worker leases are reclaimed cleanly after timeout expiration.
