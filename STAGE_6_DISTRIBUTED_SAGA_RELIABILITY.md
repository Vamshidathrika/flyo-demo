# STAGE 6 — DISTRIBUTED SAGA RELIABILITY & CRITICAL RECONCILIATION

## 1. Executive Summary
The `RebookingSagaCoordinator` orchestrates complex multi-step rebooking workflows across heterogeneous external systems (payment virtual credit card issuing, airline GDS portals, ticketing/coupon issuance, passenger notifications, and Salesforce CRM synchronization). Stage 6 hardened this coordinator with persistent lease recovery on restart, transactional outbox events, full optimistic concurrency control, exponential backoff with randomized jitter, dead-letter escalation, and the **Critical Airline Timeout Safety Rule**.

## 2. The Critical Airline Safety Rule
In high-volume airline disruption events, external booking endpoints (APIs or browser-automation portals) frequently time out under peak load.
> **Critical Rule:** Never assume an airline booking failed simply because the HTTP request timed out.

### Safe Outcome Reconciliation Workflow:
1. When an airline dispatch times out, the saga marks its state as `WAITING_FOR_EXTERNAL_RESULT`.
2. An independent reconciliation query investigates carrier records (using PNR, flight number, passenger name, and correlation IDs).
3. If an authoritative confirmation is found, the saga extracts the PNR, confirms e-ticket status, and completes without double-booking.
4. If the carrier definitively confirms no reservation exists, backward compensation is safely initiated (voiding payment VCC and escalating case).
5. If the outcome remains ambiguous or unresolved, the case is transitioned to `MANUAL_INTERVENTION_REQUIRED` with detailed ticketing desk instructions. **Automated blind retries are aborted to prevent duplicate seat purchases.**

## 3. Worker Recovery & Lease Management
- **Startup Recovery Listener (`@EventListener(ApplicationReadyEvent.class)`)**: Upon instance boot or container restart, `SagaExecutionWorker.recoverExpiredLeases()` immediately scans for active sagas whose `locked_at` timestamp exceeds the 120-second lease timeout. Orphaned locks are released and queued for re-execution.
- **Dead-Letter Handling**: If a saga reaches its configured retry limit (`max_attempts = 5`), it transitions to `STATUS_FAILED` with failure code `MAX_RETRIES_EXCEEDED`, voids pending payment authorizations, logs an audit entry, and publishes an outbox dead-letter event.

## 4. Test Verification Evidence (`Stage6DistributedSagaReliabilityTest.java`)
- `testSagaRecoversAfterWorkerCrash()`: Simulated JVM death mid-saga. Verified new worker re-claims expired lease and finishes execution.
- `testSagaAmbiguousBookingOutcomeReconciliation()`: Simulated API timeout during airline submission. Confirmed saga entered `WAITING_FOR_EXTERNAL_RESULT`, performed reconciliation, and avoided duplicate booking attempts.
- `testSagaDeadLetterAfterMaxRetries()`: Verified max attempts enforcement, exponential backoff scheduling, and transition to terminal failure with VCC compensation.
- `testDuplicateSagaCommandPrevention()`: Confirmed that attempting to start a second active saga for the same case fails-closed with `IllegalStateException`.
- `testOptimisticLockConflictDetectionDuringConcurrentStepExecution()`: Verified concurrent worker race conditions are caught by JPA version checks.

**Result: 5/5 Tests Passed Cleanly.**
