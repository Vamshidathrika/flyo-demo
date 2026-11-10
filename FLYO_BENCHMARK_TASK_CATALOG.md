# FLYO RESCUE — BENCHMARK TASK CATALOG (STAGE 11)
**Catalog Location**: `tasks/flyo/`  
**Specification**: Declarative YAML task contracts for automated agent evaluation

---

## 1. Task Index Overview

| # | Task Identifier | Category | Tier | Focus Area | Primary Judges |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | **`seed-isolation`** | Security | `core-isolation` | Positive profile restrictions, production/default seeding safety | `FlyoDataSeederProfileTest`, Grep annotations |
| **2** | **`provider-boundaries`** | Security | `fail-closed-integrations` | Rejection of mock adapters in prod, fail-closed credentials | `ProviderAdaptersIntegrationTest`, `Stage9BAdversarialProductionConfigTest` |
| **3** | **`booking-idempotency`**| Correctness | `distributed-safety` | Concurrent execution locking, duplicate webhook protection | `IdempotencyTest`, `ConcurrencyAndLoadTest` |
| **4** | **`saga-recovery`** | Resilience | `distributed-coordination` | Worker crash recovery, expired leases, uncertain provider results | `Stage6DistributedSagaReliabilityTest`, `OutboxAndSagaIntegrationTest` |
| **5** | **`tenant-isolation`** | Security | `enterprise-multi-tenancy`| Cross-tenant case/booking protection, spoofed header rejection | `Stage3EnterpriseSecurityTest`, `ProviderIntegrationAndTenantTest` |
| **6** | **`pnr-ingestion`** | Robustness | `domain-parsers` | Multi-passenger, multi-segment, overnight flights, malformed input | `PnrIngestionTest` |
| **7** | **`ml-integrity`** | Intelligence | `predictive-safety` | Artifact checksums, unsupported corridor fail-safe, no leakage | Python ML test suite (36 tests), Grep status guard |

---

## 2. Detailed Task Specifications

### 2.1 Task 1: `seed-isolation`
- **File**: `tasks/flyo/seed-isolation.yaml`
- **Objective**: Ensure database seeding is permanently restricted to positive Spring profiles (`dev`, `demo`, `test`) and fails closed under `prod`, `staging`, and default unset profiles.
- **Scope**: `FlyoDataSeeder.java`, `application.properties`, `application-demo.properties`, `FlyoDataSeederProfileTest.java`.
- **Judges**:
  - JUnit 5: `FlyoDataSeederProfileTest` (7 automated tests asserting bean presence and absence).
  - Grep: Validates `@Profile({"dev", "demo", "test"})` and `@ConditionalOnProperty("flyo.seeding.enabled")`.
- **Acceptance Criteria**: Zero seed records created on production/default profile startup.

### 2.2 Task 2: `provider-boundaries`
- **File**: `tasks/flyo/provider-boundaries.yaml`
- **Objective**: Prevent mock and demo adapters from executing in production, and ensure unconfigured external providers throw explicit exceptions rather than fabricating PNRs or virtual cards.
- **Scope**: `AmadeusNdcBookingAdapter.java`, `PartnerPortalPlaywrightAdapter.java`, `StripeIssuingPaymentAdapter.java`, `AviationRadarClient.java`.
- **Judges**:
  - JUnit 5: `ProviderAdaptersIntegrationTest`, `Stage9BAdversarialProductionConfigTest`.
  - Script: `./scripts/verify-production-data-integrity`.
- **Acceptance Criteria**: Missing credentials produce explicit `ProviderNotConfiguredException`; Playwright adapter locked to `@Profile("!prod")`.

### 2.3 Task 3: `booking-idempotency`
- **File**: `tasks/flyo/booking-idempotency.yaml`
- **Objective**: Guarantee that duplicate booking requests, concurrent operator clicks, and replayed webhooks never result in duplicate carrier reservations or multiple payment charges.
- **Scope**: `CaseStateMachineService.java`, `PaymentService.java`, `WebhookEventProcessor.java`.
- **Judges**:
  - JUnit 5: `IdempotencyTest`, `ConcurrencyAndLoadTest`, `FinancialAndBookingSafetyTest`.
- **Acceptance Criteria**: Second call returns identical PNR in <10ms; concurrent threads generate exactly 1 charge.

### 2.4 Task 4: `saga-recovery`
- **File**: `tasks/flyo/saga-recovery.yaml`
- **Objective**: Verify distributed saga orchestrator recovers from worker crashes, expired step leases, and ambiguous external provider outcomes without blind duplicate retries.
- **Scope**: `SagaExecutionWorker.java`, `SagaStepExecution.java`, `SagaCoordinator.java`.
- **Judges**:
  - JUnit 5: `Stage6DistributedSagaReliabilityTest`, `OutboxAndSagaIntegrationTest`.
- **Acceptance Criteria**: Expired step leases reclaimed within threshold; uncertain outcomes trigger reconciliation; compensating transactions execute in reverse order upon failure.

### 2.5 Task 5: `tenant-isolation`
- **File**: `tasks/flyo/tenant-isolation.yaml`
- **Objective**: Enforce multi-tenant data boundaries preventing cross-tenant case reads, updates, payment access, or spoofed `X-Tenant-ID` HTTP headers.
- **Scope**: `TenantFilter.java`, `CaseService.java`, `BookingService.java`, `PaymentService.java`.
- **Judges**:
  - JUnit 5: `Stage3EnterpriseSecurityTest` (18 multi-tenant security tests).
- **Acceptance Criteria**: Header spoofing rejected; cross-tenant case access returns 403 Forbidden; SQL queries include `tenant_id` predicate.

### 2.6 Task 6: `pnr-ingestion`
- **File**: `tasks/flyo/pnr-ingestion.yaml`
- **Objective**: Ingest complex GDS PNR formats across multi-passenger itineraries, multi-segment connecting flights, and overnight schedules without inventing synthetic flights or losing segments.
- **Scope**: `PnrIngestionService.java`, `IngestionController.java`, `PnrIngestionTest.java`.
- **Judges**:
  - JUnit 5: `PnrIngestionTest`.
- **Acceptance Criteria**: Multi-passenger bookings preserved under single booking record; overnight connections maintain timezone integrity; malformed inputs return 400 Bad Request.

### 2.7 Task 7: `ml-integrity`
- **File**: `tasks/flyo/ml-integrity.yaml`
- **Objective**: Ensure Python ML predictive microservice verifies model artifact SHA-256 checksums, handles unsupported airspace corridors fail-safe, and avoids data leakage across chronological datasets.
- **Scope**: `ml/service/main.py`, `ml/service/predictor.py`, `ml/service/monitoring_db.py`, `ml/tests/`.
- **Judges**:
  - Python test runner: `./ml/.venv/bin/python -m unittest discover -s ml/tests` (36 tests).
  - Grep: Verifies `UNSUPPORTED_AIRSPACE` status handling in `predictor.py`.
- **Acceptance Criteria**: Corrupted artifacts halt service; unsupported corridors return calibrated fail-safe status; 36/36 tests pass cleanly.
