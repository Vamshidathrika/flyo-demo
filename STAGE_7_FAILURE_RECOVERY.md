# STAGE 7 — DISTRIBUTED FAILURE TESTING & RECOVERY EVIDENCE

## 1. Executive Summary
Resilience under real-world infrastructure failures is essential for enterprise airline operations. Flyo was subjected to 10 fault-injection and chaos failure tests covering application crashes, carrier API timeouts, database rollbacks, webhook replays, and lease expirations.

## 2. Failure Scenarios & Recovery Evidence

| # | Failure Injected | System Behavior Observed | Critical State Preserved? |
| :-: | :--- | :--- | :---: |
| **1** | Airline API timeout after passenger submission | Saga transitioned to `WAITING_FOR_EXTERNAL_RESULT`. Independently polled carrier records before deciding next step. Escalated ambiguous states to `MANUAL_INTERVENTION_REQUIRED`. | **YES** — Zero duplicate bookings created. |
| **2** | Duplicate concurrent booking dispatch | Active saga lock check threw `IllegalStateException` on second request. | **YES** — Only one booking attempt executed. |
| **3** | Database exception mid-saga | Spring `@Transactional` rolled back all entity mutations across all tables. | **YES** — Zero orphan records. |
| **4** | JVM / Pod restart during in-flight booking | On boot, `SagaExecutionWorker` recovered expired lease (`locked_at < now - 120s`) and resumed execution at pending step. | **YES** — Saga resumed and completed cleanly. |
| **5** | Payment provider timeout | Exponential backoff scheduled with randomized jitter (0–3s). Max retries enforced before backward compensation. | **YES** — No hung threads or uncompensated cards. |
| **6** | Duplicate payment webhook replay | Deduplicated by `idx_payment_idem_tenant`. Returned original transaction. | **YES** — Zero double debits. |
| **7** | WhatsApp Cloud API outage | Outbox event marked `FAILED_RETRYABLE` with exponential backoff. Message sent when service restored. | **YES** — No lost passenger notifications. |
| **8** | Salesforce token expiration | Auto-refreshed OAuth2 token via client credentials flow upon encountering HTTP 401. | **YES** — CRM sync completed. |
| **9** | Concurrent supervisor approvals | State machine checked initial state atomically; only first approval succeeded; second rejected. | **YES** — Option approved exactly once. |
| **10**| Cross-tenant data tampering attempt | Rejected with HTTP 404 (OWASP BOLA defense) or HTTP 403 (Header spoofing defense). | **YES** — Zero cross-tenant data leakage. |
