# STAGE 10B: FAILURE INJECTION & RECOVERY AUDIT MATRIX
**Flyo Disruption Management & Automated Passenger Rescue Platform**  
**Audit Date:** November 8, 2026 | **Framework:** Distributed Saga Failure Modes & Chaos Engineering

---

## 1. Adversarial Failure Recovery Principles

In automated airline disruption management, blind retries after ambiguous outcomes can cause catastrophic operational failures: **duplicate flight bookings, double card charges, or passenger seat collisions**.

Flyo Rescue enforces three non-negotiable recovery rules:
1. **Rule of Ambiguity**: Any timeout or disconnect occurring *after* an order payload has left the network boundary is treated as **UNKNOWN / RECONCILIATION_REQUIRED**. The system **never blind-retries** an unverified order.
2. **Backward Compensation First**: If downstream ticketing fails definitively (e.g. inventory sold out), the saga coordinator immediately triggers backward compensation to **VOID** the issued virtual card hold before notifying the passenger.
3. **Truth in Passenger Reporting**: The passenger UI and WhatsApp channel never display `REBOOKED` or `CONFIRMED` until an authoritative GDS PNR has been persisted and reconciled.

---

## 2. Comprehensive 17-Scenario Failure Recovery Matrix

| Scenario # | Injected Adverse Condition | Injection Mechanism | Expected System State | Actual Reconciled State | Automated Recovery Mechanism | Manual Action Required? | Duplicate Risk? | Passenger-Facing Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | **Provider Timeout Before Order Submission** | Mock timeout on Amadeus OAuth / pricing endpoint | `DETECTED` / `OPTIONS_READY` | `OPTIONS_READY` | Amadeus client circuit breaker records failure. Rebooking gate remains closed. | No. Re-tryable by supervisor. | **ZERO** (Payload not sent) | "Alternative options being evaluated" |
| **2** | **Provider Timeout After Submission (Ambiguous)** | Socket timeout after GDS OrderCreate request sent | `REBOOKING` | `MANUAL_INTERVENTION_REQUIRED` | Saga coordinator halts blind retries, logs `UNKNOWN_AMBIGUOUS`, and flags case for manual ticketing desk. | **YES** (Desk verifies GDS PNR presence). | **ZERO** (Retries blocked) | "Rebooking in progress with carrier operations" |
| **3** | **Duplicate Webhook Delivery** | Dispatch identical webhook 5x within 2 seconds | `DETECTED` | `DETECTED` | Idempotency filter checks `webhook_events.idempotency_key`, returns cached HTTP 200 on repeats. | None (Fully automatic). | **ZERO** (Deduplicated) | "Disruption detected" (1 notification only) |
| **4** | **Duplicate Operator Approval Request** | Concurrently submit 2 approvals for Option O2 & O3 | `APPROVED` | `APPROVED` (First wins) | Optimistic locking on `rescue_cases.version` rejects second approval with HTTP 409 Conflict. | None (Second click rejected). | **ZERO** | "Option approved" |
| **5** | **Worker Crash During Active Saga** | Kill backend process during saga step `VCC_ISSUED` | `REBOOKING` | `COMPENSATING` / `ABORTED` | On restart, `SagaExecutionWorker` sweeps orphaned leases (>60s old), voids VCC, and unlocks case. | None (Automatic startup sweep).| **ZERO** | "Rebooking processing delayed" |
| **6** | **Application Restart During Reconciliation** | Kill container during nightly financial reconcile | `SETTLED` | `SETTLED` | Reconciliation job queries `reconciled = false` and resumes processing idempotently. | None. | **ZERO** | Unaffected (Internal ledger) |
| **7** | **Expired Provider OAuth Token** | Invalidate in-memory Amadeus bearer token | `REBOOKING` | `REBOOKING` | Client intercepts HTTP 401, clears atomic reference, re-authenticates proactively, retries order. | None (Transparent retry). | **ZERO** (Token refresh idempotent) | Unaffected |
| **8** | **Fare Change Between Search & Booking** | Carrier raises fare from ₹4,200 to ₹6,500 | `REBOOKING` | `OPTIONS_READY` | Pre-booking repricing detects fare variance; aborts order, marks option expired, re-ranks choices. | Yes (Supervisor reviews new fare diff). | **ZERO** | "Flight price updated by airline" |
| **9** | **Inventory Sold Out Before Order** | Zero bookable seats remaining on GDS host | `REBOOKING` | `COMPENSATING` -> `OPTIONS_READY` | GDS returns `ERR_SEATS_UNAVAILABLE`. Coordinator triggers `voidAuthorization()`, releases VCC. | Yes (Selects alternative flight). | **ZERO** | "Selected flight sold out; loading alternates" |
| **10** | **Payment VCC Authorization Failure** | Tenant spending limit exceeded (₹100k cap) | `OPTIONS_READY` | `FAILED_ESCALATED` | `PaymentSettlementService` throws HTTP 400. Rebooking subprocess never spawned. | Yes (Finance increases credit cap). | **ZERO** | "Processing airline rebooking" |
| **11** | **Payment Settlement Capture Failure** | Bank declines capture on previously authorized VCC| `REBOOKING` | `MANUAL_REVIEW_REQUIRED` | Financial discrepancy engine logs `CARD_SETTLEMENT_FAILED`; alerts treasury desk. | Yes (Operations settles manually). | **ZERO** | "Flight rebooked; receipt pending" |
| **12** | **Airline Booking Cancellation Failure** | Amadeus DELETE order returns HTTP 500 | `DONE` | `MANUAL_INTERVENTION_REQUIRED` | Saga logs compensation failure; escalates to carrier phone desk to cancel reservation. | **YES** (Direct carrier call required). | **ZERO** | "Cancellation pending airline confirmation" |
| **13** | **Refund Pending or Rejected by Carrier** | Airline rejects electronic ticket void request | `SETTLED` | `DISCREPANCY_FLAGGED` | Discrepancy logged in `discrepancy_records`; treasury initiates formal BSP chargeback. | Yes (Dispute specialist handles). | **ZERO** | "Refund initiated with carrier" |
| **14** | **WhatsApp Notification Delivery Failure** | Meta Graph API returns HTTP 503 / network drop | `DONE` | `DONE` (Notification: `FAILED_RETRY`) | Transactional outbox retries message delivery 5x with exponential backoff (1s, 2s, 4s, 8s, 16s). | None (Auto-retried; falls back to SMS).| **ZERO** | Delayed delivery |
| **15** | **Database Transaction Conflict** | Concurrent updates to same `rescue_cases` row | Row update | Row update (Serializable) | JPA `@Version` optimistic locking triggers retry or aborts stale write. | None (Handled in service layer). | **ZERO** | Truthful |
| **16** | **Redis Cache Unavailable** | Redis service stopped (`kill -9 redis-server`) | Throttling | Degraded Fallback | Rate limiter falls back gracefully to in-memory Guava token bucket; logs degraded telemetry. | None (Platform remains functional). | **ZERO** | Unaffected |
| **17** | **Provider Rate Limiting (HTTP 429)** | Amadeus returns HTTP 429 Too Many Requests | `REBOOKING` | `RETRY_QUEUED` | Client inspects `Retry-After` header, pauses thread, backs off before second attempt. | None (Auto-throttled). | **ZERO** | "Carrier system busy; retrying" |

---

## 3. Summary of Failure Engineering Guarantees

1. **Zero Blind Retries on Timeouts**: In all 17 scenarios, no unverified rebooking attempt is ever automatically re-sent to an external airline host without authoritative confirmation of booking absence.
2. **Double-Entry Financial Cleanliness**: When rebooking fails, virtual cards are systematically voided via saga compensation, preventing orphan holds or phantom billing.
3. **Audit Immutability**: Every failure transition, error code, and manual intervention flag is permanently recorded in `saga_step_logs` and `audit_events`.
