# STAGE 6 — CHAOS ENGINEERING & FAULT INJECTION REPORT

## 1. Executive Summary
A core objective of Stage 6 was validating that Flyo behaves safely during sudden infrastructure degradation, external partner outages, network timeouts, and concurrency spikes. Test suite `Stage6ChaosAndFailureInjectionTest.java` subjected Flyo's core components to 7 realistic chaos failure scenarios.

## 2. Injected Failure Scenarios & System Recovery

### Scenario 1: Airline API Network Timeout During Booking
- **Failure Injected**: Downstream carrier GDS socket timed out after submitting passenger credentials.
- **System Behavior**: The system refused to mark the booking as failed. It marked the saga `WAITING_FOR_EXTERNAL_RESULT`, avoided re-submitting a duplicate booking, reconciled with the airline provider, and escalated ambiguous states to human operators (`MANUAL_INTERVENTION_REQUIRED`).
- **Result**: Zero duplicate bookings created.

### Scenario 2: Duplicate Booking Request Under Concurrency
- **Failure Injected**: Two rapid simultaneous booking dispatch calls triggered for the same rescue case.
- **System Behavior**: The first call initiated the saga; the second was blocked immediately with `IllegalStateException` due to active saga collision checks.
- **Result**: Only one booking saga executed.

### Scenario 3: Database Rollback During Multi-Table Saga Mutation
- **Failure Injected**: Forced an unhandled runtime exception between case state transition and step audit logging.
- **System Behavior**: Spring `@Transactional` rolled back all entity changes atomically. No partial or corrupted state remained in the database.
- **Result**: Zero orphan database records.

### Scenario 4: Worker Process Restart During In-Flight Booking
- **Failure Injected**: Simulated JVM termination while a saga worker held an active lease.
- **System Behavior**: On reboot, `SagaExecutionWorker` detected the expired lease (`locked_at < now - 120s`), claimed the orphan saga, and resumed execution at the appropriate step.
- **Result**: Resumed and completed cleanly without human intervention.

### Scenario 5: Duplicate Payment Gateway Webhook Delivery
- **Failure Injected**: Upstream payment gateway delivered the same `CHARGE_SUCCESS` webhook 3 times in rapid succession.
- **System Behavior**: The unique idempotency index (`idempotency_key`, `tenant_id`) deduplicated the events, returning HTTP 200 cached response for subsequent deliveries without double-crediting.
- **Result**: Exactly one transaction recorded in financial ledger.

### Scenario 6: Concurrent Supervisor Approval Race Condition
- **Failure Injected**: Two supervisors simultaneously clicked "Approve Option" on the same case.
- **System Behavior**: State machine checked initial state atomically; the first approval succeeded (`APPROVED`), while the second encountered an invalid state transition and was safely rejected.
- **Result**: Option approved once; zero race condition anomalies.

### Scenario 7: Production Profile Fail-Closed Security Barrier
- **Failure Injected**: Bootstrapped application under `prod` profile with in-memory H2 database URL and default secrets.
- **System Behavior**: `ProductionSecurityValidator` threw `IllegalStateException` immediately, blocking application startup.
- **Result**: Insecure configuration prevented from starting.

## 3. Test Verification Evidence (`Stage6ChaosAndFailureInjectionTest.java`)
**Result: 7/7 Chaos Tests Passed Cleanly.**
