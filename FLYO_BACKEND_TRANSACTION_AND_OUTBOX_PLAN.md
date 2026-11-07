# Flyo Backend Transaction and Outbox Architecture Plan

**Document Status:** Approved Engineering Architecture Plan  
**Role:** Principal Backend Architect & Distributed Systems Specialist  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Transaction Boundary Audit & The Distributed Rollback Trap

A severe architectural risk in travel disruption software is assuming that an enterprise relational database transaction (`@Transactional`) guarantees distributed consistency across external aviation systems.

### 1.1. The Failure Scenario in Current Flyo Code
In [`CaseService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/CaseService.java) and [`RebookingOrchestrationService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/RebookingOrchestrationService.java):
```java
@Transactional
public RescueCase approveCase(String caseId, String optionId, String operator) {
    // 1. Mutate local database state
    rescueCase.setState("EXECUTION_IN_PROGRESS");
    
    // 2. Execute external side-effects synchronously inline!
    virtualCardService.issueCard(...); // External bank call
    rebookService.executeRebook(...);   // External GDS / Playwright call
    messageService.sendMessage(...);   // External WhatsApp call
    
    // 3. Database commit
    return rescueCaseRepository.save(rescueCase);
}
```
**What happens if step 3 throws a database timeout, or if network drops after step 2?**
* The Spring transaction rolls back the local database changes.
* **The airline has already issued the ticket; the bank has already authorized the funds; the passenger has already received the WhatsApp confirmation!**
* The local database now shows the case as `AWAITING_APPROVAL`, while the real world has executed the booking. If the operator clicks "Approve" again, the passenger is double-booked and double-charged.

---

## 2. Distributed Saga Orchestration Engine

Flyo must decouple local database mutations from external side effects using an **Asynchronous Orchestration Saga with Cryptographic Idempotency Keys**:

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                             RESCUE EXECUTION SAGA LIFECYCLE                            │
 │                                                                                        │
 │  [STEP 1: INTENT]                                                                      │
 │  Operator clicks Approve -> Local DB records `REBOOK_INTENT_LOCKED`                   │
 │  Generates deterministic Saga Idempotency Key: `IDEMP-<caseId>-<optionId>-v1`          │
 │  Writes `RebookExecutionCommand` to durable `outbox_events` table (Single ACID Commit)│
 │                                                                                        │
 │  [STEP 2: OUTBOX DISPATCH]                                                             │
 │  Outbox Dispatcher polls pending event -> Marks status `DISPATCHED`                    │
 │                                                                                        │
 │  [STEP 3: AUTHORIZATION & PAYMENT SAGA STEP]                                           │
 │  Issues single-use VCC with idempotency key -> Records `VCC_AUTHORIZED`                │
 │  (Compensation if later step fails: `VOID_VCC_AUTHORIZATION`)                          │
 │                                                                                        │
 │  [STEP 4: AIRLINE BOOKING SAGA STEP]                                                   │
 │  Dispatches `OrderChange` or `EnhancedAirBook` to PSS/GDS with idempotency key          │
 │  Receives external PSS transaction reference -> Records `BOOKING_DISPATCHED`           │
 │                                                                                        │
 │  [STEP 5: INDEPENDENT VERIFICATION SAGA STEP]                                         │
 │  Queries authoritative PSS/GDS: Confirms PNR status `HK` and e-ticket coupon `OPEN`    │
 │                                                                                        │
 │  [STEP 6: FINALIZATION & NOTIFICATION]                                                 │
 │  Local DB transitions to `REBOOK_CONFIRMED` -> Emits `PassengerNotificationCommand`   │
 │                                                                                        │
 │  [COMPENSATION & ESCALATION BRANCH]                                                    │
 │  If Step 4 fails: Void Step 3 VCC authorization -> Reopen Case -> Alert Supervisor     │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1. Idempotency Key Specification
Every external call requires a deterministic idempotency header:
```
Idempotency-Key = SHA256(tenantId + ":" + caseId + ":" + optionId + ":" + attemptCount)
```
If the network drops mid-flight and Flyo retries the request, the external GDS or payment processor recognizes the key and returns the original cached response rather than creating a duplicate booking.

---

## 3. Transactional Outbox Dispatcher Implementation Plan

In `FLYO_AUDIT_DISCREPANCY_REGISTER.md`, we confirmed that [`OutboxService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/OutboxService.java) currently saves records to `outbox_events` that are never consumed.

### 3.1. Outbox Worker Component Architecture

```java
@Component
public class OutboxEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventDispatcher.class);
    private final OutboxEventRepository outboxRepo;
    private final Map<String, OutboxEventHandler> handlers;

    @Scheduled(fixedDelayString = "${flyo.outbox.poll-interval-ms:2000}")
    @Transactional
    public void processOutboxBatch() {
        // High-concurrency pessimistic lock to prevent duplicate execution across cluster
        List<OutboxEvent> pending = outboxRepo.findTop50PendingForUpdate();

        for (OutboxEvent event : pending) {
            try {
                OutboxEventHandler handler = handlers.get(event.getAggregateType());
                if (handler != null) {
                    handler.handle(event);
                    event.setStatus("COMPLETED");
                    event.setProcessedAt(LocalDateTime.now());
                } else {
                    event.setStatus("NO_HANDLER");
                }
            } catch (Exception ex) {
                int retries = event.getRetryCount() + 1;
                event.setRetryCount(retries);
                event.setErrorMessage(ex.getMessage());
                
                if (retries >= 5) {
                    event.setStatus("DEAD_LETTER");
                    log.error("[Outbox DLQ] Permanent failure on event {}: {}", event.getId(), ex.getMessage());
                    alertSupervisor(event);
                } else {
                    // Exponential backoff interval: 2s, 10s, 30s, 2m, 10m
                    event.setNextRetryAt(calculateBackoff(retries));
                    event.setStatus("PENDING");
                }
            }
            outboxRepo.save(event);
        }
    }
}
```

### 3.2. Database Query for High-Concurrency Locking
To prevent multiple Spring Boot replicas from competing for the same outbox rows, use PostgreSQL `SKIP LOCKED`:

```sql
-- Flyway Repository Implementation
SELECT * FROM outbox_events 
WHERE status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP)
ORDER BY created_at ASC 
LIMIT 50 
FOR UPDATE SKIP LOCKED;
```
This ensures zero contention and guarantees that each event is processed by exactly one worker thread in the cluster.
