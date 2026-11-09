# STAGE 10: OPERATIONAL SAFETY, FAULT TOLERANCE & FAILURE RECOVERY
**Flyo Disruption Management Platform**  
**Resilience Engineering Audit:** Failure Modes, Circuit Breakers & Distributed Recovery

---

## 1. Failure Scenarios and Tested Mitigations

| Failure Mode | Injected Fault | System Response | Verification Suite |
| :--- | :--- | :--- | :--- |
| **Airline API Timeout** | HTTP 504 Gateway Timeout on OrderCreate | Circuit breaker increments error count; case marks `RECONCILING`; prohibits blind retry. | `AmadeusGdsSandboxClientTest` |
| **Duplicate Provider Response** | Replay of same GDS order response | Idempotency filter detects hit; returns cached response without secondary booking. | `WebhookEventProcessorTest` |
| **PNR Ingestion Duplication** | Re-posting identical GDS PNR line | In-place update of existing booking; logs `PNR_UPDATED_IDEMPOTENT`. | `PnrIngestionTest` |
| **Booking Timeout after Provider Success** | Transport drops after Amadeus allocates seat | Saga lease recovery worker polls `/flight-orders/{id}`; confirms booking. | `Stage6SagaDistributedReliabilityTest` |
| **Delayed Ticket Issuance** | Carrier issues PNR but queues ticket | Case transitions to `VERIFYING_TICKET`; polls with exponential backoff. | `AmadeusAlteaAdapterTest` |
| **Payment Authorization Failure** | VCC declined (insufficient balance) | Order submission aborted; case halts in `PAYMENT_FAILED`; unlocks human intervention. | `Stage6PaymentAndReconciliationTest` |
| **Webhook Replay Attack** | Re-submitting intercepted webhook payload | HMAC timestamp verification rejects payload if older than 300 seconds. | `Stage3EnterpriseSecurityTest` |
| **Worker JVM Crash** | Unclean process termination during saga | Redis/PostgreSQL distributed lock lease expires; surviving worker re-leases and completes. | `Stage7ProductionVerificationTest` |
| **Database Connection Loss** | Transient PostgreSQL socket failure | HikariCP pool automatically recovers with validation query (`SELECT 1`). | `Stage9BEmptyProductionDatabaseTest` |

---

## 2. Distributed Saga State Machine Invariant Rules

1. **No Blind Retries**: Any transaction with an ambiguous provider outcome must first execute a state reconciliation read query.
2. **Exponential Backoff with Jitter**:
   $$T_{\text{wait}} = \min(T_{\text{max}}, T_{\text{base}} \times 2^{\text{attempt}}) \pm \text{jitter}$$
3. **Dead Letter Queue (DLQ)**: After 5 failed retry attempts, transactions are quarantined in `outbox_events` with `status = 'DEAD_LETTER'` and trigger an operator PagerDuty/Slack escalation.
