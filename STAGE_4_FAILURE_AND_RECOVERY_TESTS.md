# FLYO — STAGE 4 FAILURE AND RECOVERY TEST REPORT
## 24 Mandatory Adversarial Scenarios — Empirical Verification & Evidence

**Classification:** Flyo QA & Systems Resilience Report  
**Version:** 4.0.0-PROD-STAGE4  
**Date:** November 2026  
**Test Suite:** `flyo-rescue-demo/backend/src/test/java/com/flyo/rescue/Stage4EndToEndEnterpriseWorkflowTest.java`  
**Execution Result:** 24 Tests Run, 24 Passed, 0 Failed, 0 Skipped (100% Green)

---

## 1. Executive Summary

To validate production robustness against real-world airline disruptions and edge cases, 24 adversarial scenarios were executed. Each scenario tests a failure mode, security boundary, concurrency condition, or recovery pattern.

---

## 2. Complete 24 Adversarial Scenarios Breakdown

| # | Scenario Name | Injected Fault / Boundary Condition | System Behavior & Invariant Enforced | Test Method | Result |
| :- | :--- | :--- | :--- | :--- | :--- |
| **1** | Valid disruption creates exactly one case | Standard cancellation alert on active booking | Creates case, aggregates weather, ranks options $\to$ `OPTIONS_READY`. | `testValidDisruptionCreatesExactlyOneCase` | **PASS** |
| **2** | Duplicate disruption is idempotent | Same flight cancellation sent twice | Second alert recognizes existing case; exactly 1 case created. | `testDuplicateDisruptionDoesNotCreateDuplicateCases` | **PASS** |
| **3** | Stale disruption flagged | Disruption timestamp $> 300\text{s}$ old | Freshness verified; payload metadata flags stale telemetry. | `testStaleDisruptionFlaggedInEvidence` | **PASS** |
| **4** | Unauthenticated webhook rejected | Webhook delivered without HMAC signature | Rejected immediately with HTTP 401 Unauthorized. | `testUnauthenticatedWebhookRejected` | **PASS** |
| **5** | Tampered webhook signature rejected | HMAC payload tampered with in transit | Verification fails; event discarded with HTTP 401. | `testTamperedWebhookRejected` | **PASS** |
| **6** | Stale webhook timestamp rejected | Timestamp drift $= 360\text{s} > 300\text{s}$ tolerance | Rejected with HTTP 400 Bad Request. | `testStaleWebhookRejected` | **PASS** |
| **7** | Duplicate webhook replay cached | Identical webhook payload replayed | Returns cached HTTP 200 without creating duplicate events. | `testDuplicateWebhookReplayCached` | **PASS** |
| **8** | Unauthorized operator approval denied | Operator lacking supervisor role attempts approval | Denied with HTTP 403 Forbidden via Spring Security. | `testUnauthorizedApprovalDenied` | **PASS** |
| **9** | Concurrent conflicting approvals rejected | Operator 1 approves Opt-A, Operator 2 approves Opt-B | Opt-A transitions to `APPROVED`; Opt-B throws `IllegalStateTransitionException`. | `testConcurrentConflictingApprovals` | **PASS** |
| **10**| Duplicate saga initiation idempotent | `initiateRebookingSaga` called twice for same option | Returns exact existing `saga_id` without duplicate worker jobs. | `testDuplicateExecutionReturnsExistingSaga` | **PASS** |
| **11**| Provider timeout produces unknown outcome | Carrier gateway throws `SocketTimeoutException` | Saga enters `WAITING_FOR_EXTERNAL_RESULT` at `RECONCILE_BOOKING`. | `testProviderTimeoutProducesUnknownOutcome` | **PASS** |
| **12**| Late provider response reconciles | Recheck queries carrier host; booking exists | Resolved ambiguous state; extracts PNR $\to$ advances to ticket check. | `testLateProviderResponseReconciliation` | **PASS** |
| **13**| Reconciliation confirms absent booking | Recheck confirms NO reservation on airline host | Saga triggers backward compensation, releases VCC, escalates case. | `testReconciliationConfirmsAbsentBooking` | **PASS** |
| **14**| Unresolved ambiguity flags manual desk | Both booking dispatch and reconciliation fail | Saga marks `MANUAL_INTERVENTION_REQUIRED`; halts automated retry. | `testUnresolvedAmbiguityFlagsManualDesk` | **PASS** |
| **15**| Expired approval rejected | Approval timestamp $= 45\text{m}$ old ($> 30\text{m}$ TTL) | `initiateRebookingSaga` throws `ApprovalRequiredException`. | `testExpiredApprovalRejected` | **PASS** |
| **16**| Option mismatch rejected | Approver approved Opt-1, but worker dispatched Opt-2 | Rejected with `ApprovalRequiredException`. | `testOptionMismatchRejected` | **PASS** |
| **17**| Ticket verification failure halts completion | PNR exists, but Amadeus ticket coupon is `VOID` | Saga transitions case to `MANUAL_INTERVENTION_REQUIRED`. | `testTicketVerificationFailurePreventsCompletion` | **PASS** |
| **18**| Messaging failure keeps booking intact | WhatsApp API throws connection error during notice | Passenger message marked `FAILED_PENDING_RETRY`; booking kept `DONE`.| `testMessagingFailureDoesNotRollBackBooking` | **PASS** |
| **19**| CRM sync failure retries independently | Salesforce endpoint returns HTTP 503 | `crm_sync_status = 'PENDING_RETRY'`; confirmed booking preserved. | `testCrmSyncFailureRetriesIndependently` | **PASS** |
| **20**| Payment failure prevents false settlement | Fare adjustment ($\text{₹}150,000$) exceeds spending limit | VCC creation rejected; saga marks `FAILED`, voids authorization. | `testPaymentFailurePreventsFalseSettlement` | **PASS** |
| **21**| ML service failure degrades safely | FastAPI ML service down / unreachable | Engine falls back to deterministic safety rules & MCT calculation. | `testMlFailureGracefulDegradation` | **PASS** |
| **22**| Database connection loss recovers | Database temporarily unavailable during polling | Outbox worker lease expires; another worker resumes cleanly. | `testDatabaseSafeDegradation` | **PASS** |
| **23**| Conflicting radar & weather reconciled | Radar says ON_TIME, but airport weather reports storm | Disruption marked pending confirmation; no premature rebooking. | `testConflictingProviderEvidence` | **PASS** |
| **24**| Cross-tenant data isolation verified | Tenant Alpha operator requests Tenant Beta case | Tenant security filter blocks access with HTTP 403 / TenantException. | `testTenantIsolationStrictlyEnforced` | **PASS** |

---

## 3. Test Execution Proof
```
[INFO] Running com.flyo.rescue.Stage4EndToEndEnterpriseWorkflowTest
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 14.38 s -- in com.flyo.rescue.Stage4EndToEndEnterpriseWorkflowTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```
