# Stage 2: Comprehensive Test Evidence & Verification Report

**Author:** Quality Assurance & Distributed Systems Engineer  
**Date:** November 6, 2026  
**System:** Flyo Autonomous Disruption Recovery Engine  
**Execution Environment:** macOS, Java 26 / OpenJDK 64-Bit, Python 3.11, Maven 3.9+  

---

## 1. Test Suite Summary Table

| Test Suite / Category | File / Module | Tests Executed | Passed | Failed | Skipped | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Stage 2 Integration & Tenants** | `ProviderIntegrationAndTenantTest.java` | 9 | 9 | 0 | 0 | **PASS** |
| **Stage 2 Approval Gate Hardening**| `ApprovalGateEdgeTest.java` | 5 | 5 | 0 | 0 | **PASS** |
| **Stage 1 Outbox & Distributed Saga**| `OutboxAndSagaIntegrationTest.java` | 13 | 13 | 0 | 0 | **PASS** |
| **State Machine & MCT Calculations**| `StateMachineAndMctTest.java` | 4 | 4 | 0 | 0 | **PASS** |
| **Approval Gate Workflow** | `ApprovalGateTest.java` | 3 | 3 | 0 | 0 | **PASS** |
| **Idempotency & Rebook Safety** | `IdempotencyTest.java` | 1 | 1 | 0 | 0 | **PASS** |
| **Audit Trail & WhatsApp Message Flow**| `AuditAndMessageTest.java` | 1 | 1 | 0 | 0 | **PASS** |
| **Payment & Treasury Settlement** | `PaymentSettlementTest.java` | 3 | 3 | 0 | 0 | **PASS** |
| **Security Filters & RBAC** | `SecurityFilterTest.java` | 16 | 16 | 0 | 0 | **PASS** |
| **PostgreSQL Production Schema** | `PostgreSqlProductionIntegrationTest.java`| 4 | 4 | 0 | 0 | **PASS** |
| **Concurrency & Load** | `ConcurrencyAndLoadTest.java` | 2 | 2 | 0 | 0 | **PASS** |
| **Live Multi-Source Inference & Weather**| `LiveInferenceEndToEndIntegrationTest.java`| 6 | 6 | 0 | 0 | **PASS** |
| **Remaining Backend Core Tests** | 19 additional backend test classes | 84 | 84 | 0 | 0 | **PASS** |
| **Subtotal Java Backend** | `mvn test` (31 classes) | **151** | **151** | **0** | **0** | **100% PASS** |
| **Python ML v2 Multi-Season Test Suite**| `unittest discover ml/tests` | 26 | 26 | 0 | 0 | **100% PASS** |
| **TypeScript Agent Typecheck** | `npx tsc --noEmit` | N/A | 0 errors | 0 | 0 | **100% PASS** |
| **TOTAL VERIFIED TEST SUITE** | **Full System Surface** | **177** | **177** | **0** | **0** | **100% PASS** |

---

## 2. Key Stage 2 Test Execution Logs

```
[INFO] Running com.flyo.rescue.ProviderIntegrationAndTenantTest
2026-11-06T14:55:01.396+05:30  INFO [main] c.f.r.ProviderIntegrationAndTenantTest   : Starting ProviderIntegrationAndTenantTest
2026-11-06T14:55:05.104+05:30  INFO [main] o.f.c.i.s.JdbcTableSchemaHistory         : Creating Schema History table "PUBLIC"."flyway_schema_history" ...
2026-11-06T14:55:05.525+05:30  INFO [main] o.f.core.internal.command.DbMigrate      : Successfully applied 6 migrations to schema "PUBLIC", now at version v6
2026-11-06T14:55:15.669+05:30  INFO [main] c.f.r.i.ProviderCapabilityRegistry       : [ProviderRegistry] Provider 'stripe_issuing' status transitioned: CONFIGURED_NOT_VERIFIED -> DISABLED
2026-11-06T14:55:15.729+05:30  INFO [main] c.f.r.i.webhook.WebhookEventProcessor    : [Webhook] Successfully ingested and verified event 'evt_dedup_...' of type 'PAYMENT_SETTLED'
2026-11-06T14:55:15.773+05:30  INFO [main] c.f.r.i.webhook.WebhookEventProcessor    : [Webhook] Idempotent hit: Event 'evt_dedup_...' already processed. Returning cached success.
2026-11-06T14:55:15.864+05:30  WARN [main] c.f.r.i.webhook.WebhookEventProcessor    : [Webhook] Rejected expired webhook timestamp drift: 360s
2026-11-06T14:55:15.879+05:30  WARN [main] c.f.r.i.webhook.WebhookEventProcessor    : [Webhook] Invalid HMAC signature for event: evt_...
2026-11-06T14:55:15.890+05:30  WARN [main] c.f.r.security.DistributedRateLimiter    : [RateLimiter] Rate limit exceeded for key 'rate_limit_test_...' (3 / 3 in 10s window)
2026-11-06T14:55:15.918+05:30  INFO [main] r.i.r.ExternalStateReconciliationService : [Reconciliation] Discrepancy 'DISC-9ffedb8e' resolved for case 'CASE-RECON-5ea9c2'
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.70 s -- in com.flyo.rescue.ProviderIntegrationAndTenantTest
```

```
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  58.702 s
[INFO] Finished at: 2026-11-06T14:58:21+05:30
[INFO] ------------------------------------------------------------------------
```
