# FLYO — STAGE 1: FAILURE RECOVERY & CONCURRENCY TESTS REPORT

**Author:** Distributed Systems QA & Resilience Engineering Team  
**Date:** November 6, 2026  
**Status:** ALL TESTS VERIFIED & PASSING (100% PASS RATE)  
**Target Test Class:** `com.flyo.rescue.OutboxAndSagaIntegrationTest`  

---

## 1. Overview & Test Matrix

Stage 1 mandates verifiable proof of system resilience across concurrency, network timeouts, partner rejections, node crashes, and operator recovery. The dedicated integration test suite `OutboxAndSagaIntegrationTest.java` executes 11 comprehensive failure recovery scenarios.

### Test Execution Matrix

| Test Method | Category | Scenario Description | Expected Outcome | Result |
| :--- | :--- | :--- | :--- | :--- |
| `testOutboxBatchClaimingAndLeaseLocking` | Concurrency | Two concurrent workers attempt to claim the same outbox batch | Worker 1 acquires row lease; Worker 2 skips locked rows via `SKIP LOCKED`; 0 duplicate claims | **PASS** |
| `testOutboxIdempotencyDeduplication` | Idempotency | Dual publish attempts with duplicate `idempotencyKey` | Second call returns existing event entity; DB unique constraint enforced | **PASS** |
| `testOutboxExponentialBackoffAndDeadLetter` | Backoff & DLQ | Worker encounters repeated errors during dispatch | Retry count increments; delay doubles exponentially; 5th failure routes event to `DEAD_LETTER` | **PASS** |
| `testOutboxLeaseExpirationRecovery` | Crash Recovery | Worker claims outbox event and crashes (lease expires > 60s) | Recovery job reclaims abandoned event, resets status to `PENDING`, clears `locked_by` | **PASS** |
| `testSagaHappyPathExecution` | End-to-End Saga | Approved case executes authorization, rebooking dispatch, and ticketing | VCC authorized, booking confirmed, ticket verified, case state `DONE`, step logs recorded | **PASS** |
| `testSagaRejectionWithoutApproval` | Safety Gate | Initiating saga on unapproved case (`PENDING_APPROVAL`) | Fails closed with `ApprovalRequiredException`; 0 external calls; 0 completed sagas | **PASS** |
| `testSagaBackwardCompensationOnAirlineFailure` | Compensation | Airline partner rejects booking (flight sold out) | Coordinator catches `CONFIRMED_FAILURE`, voids VCC hold, sets saga `FAILED`, case `FAILED_ESCALATED` | **PASS** |
| `testSagaAmbiguousTimeoutTransitionsToWaiting` | Ambiguity Isolation | Network socket timeout during rebooking dispatch | Coordinator catches timeout, marks saga `WAITING_FOR_EXTERNAL_RESULT`, case `RECONCILING`; 0 duplicate booking calls | **PASS** |
| `testSagaLeaseRecovery` | Crash Recovery | Saga node crashes mid-execution (lease expires > 120s) | Recovery job reclaims abandoned saga, resets status to `PENDING` for next worker | **PASS** |
| `testAdminMonitoringEndpointsAuthorized` | Admin API | Admin queries `/api/admin/outbox/**` and `/api/admin/sagas/**` | HTTP 200 OK with accurate JSON telemetry and dead-letter event lists | **PASS** |
| `testAdminEndpointsForbiddenForNonAdmin` | Security / RBAC | Non-admin (`PASSENGER` role) attempts to access admin endpoints | HTTP 403 Forbidden fail-closed enforcement | **PASS** |

---

## 2. Test Execution Output

From Surefire test execution:
```
[INFO] Running com.flyo.rescue.OutboxAndSagaIntegrationTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 20.35 s -- in com.flyo.rescue.OutboxAndSagaIntegrationTest
[INFO] Results:
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Combined project backend test execution:
```
[INFO] Results:
[INFO] Tests run: 135, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

---

## 3. Detailed Failure Recovery Scenarios

### Scenario A: Network Timeout During Flight Booking
When an airline host connection hangs or times out after sending card details:
1. `PartnerPortalPlaywrightAdapter` or GDS client throws `SocketTimeoutException`.
2. `RebookingSagaCoordinator` intercepts the timeout without retrying.
3. The outcome is recorded as `TIMEOUT_UNKNOWN`.
4. Saga transitions to `WAITING_FOR_EXTERNAL_RESULT` at step `STEP_RECONCILE_BOOKING`.
5. Case transitions to `RECONCILING`.
6. **Zero duplicate charges occur.** The coordinator does not issue a second VCC or blindly send another booking request.

### Scenario B: Airline Rejection & Backward Compensation
When an airline host rejects a booking (e.g. inventory sold out between ranking and checkout):
1. Provider returns `CONFIRMED_FAILURE` with error `"Flight sold out during checkout"`.
2. Coordinator logs step failure and enters `triggerCompensation(...)`.
3. Saga status transitions to `COMPENSATING`.
4. The compensation handler extracts `virtualCardId` from `compensationPayload` and calls `paymentSettlementService.voidVirtualCard(caseId, cardId)`.
5. Commercial card authorization hold is voided via Treasury adapter.
6. Step log `COMPENSATE_VCC` is written with status `SUCCEEDED`.
7. Saga status transitions to `FAILED` (`AIRLINE_REBOOK_REJECTED`), and case state transitions to `FAILED_ESCALATED` for immediate human operator handover.
