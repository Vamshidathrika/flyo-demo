# Stage 2: Stage 1 Forensic Verification & Remediation Report

**Date:** November 6, 2026  
**Auditor / Engineer:** Principal Application Security Engineer & Distributed Systems Architect  
**Repository:** `/Users/nani/Downloads/flyo`  
**Target:** Stage 1 Implementation Baseline & Stage 2 Remediation  

---

## 1. Executive Summary & Defect Remediation

During the Stage 2 entry audit of the Stage 1 baseline, a critical architectural vulnerability was identified in the approval and rebooking dispatch subsystem:
1. **The Approval Gate Vulnerability**: In Stage 1, a case could initiate consequential saga execution while in either `APPROVED` or `AWAITING_APPROVAL` state. The user mandate is strict: `AWAITING_APPROVAL` indicates that human authorization is pending. **Under no circumstances may a case in `AWAITING_APPROVAL` trigger external bookings, virtual credit card issuance, funds movement, or saga execution.**
2. **Missing Authoritative Gate**: The coordinator previously lacked runtime verification of approver identity, approval timestamp validity (TTL expiration), and option identity consistency.
3. **Multi-Instance State Safety**: The in-memory login rate limiter and local-only lock primitives required distributed evaluation to prevent concurrent worker race conditions.

### Immediate Engineering Remediation Executed
- **State Machine Isolation**: Introduced `STATE_APPROVED = "APPROVED"` into `CaseStateMachineService.java`.
- **Strict Separation of States**:
  - `OPTIONS_READY` $\rightarrow$ `AWAITING_APPROVAL`, `APPROVED`, `ESCALATED`
  - `AWAITING_APPROVAL` $\rightarrow$ `APPROVED`, `OPTIONS_READY`, `ESCALATED`. **Direct transition from `AWAITING_APPROVAL` to `REBOOKING` is physically forbidden by the state transition graph.**
  - `APPROVED` $\rightarrow$ `REBOOKING`, `PAYMENT_INSTRUMENT_READY`, `PAYMENT_AUTH_PENDING`, `OPTIONS_READY`, `ESCALATED`.
- **Coordinator Hardening**: Added a 4-point mandatory gate to `RebookingSagaCoordinator.java`:
  1. Case state must be strictly `APPROVED`. If state is `AWAITING_APPROVAL`, `DETECTED`, `OPTIONS_READY`, or any other state, throw `IllegalStateException`.
  2. Approved option ID must exactly match the saga request option ID.
  3. Authenticated approver (`approvedBy`) must be non-blank and not `"ANONYMOUS"`.
  4. Approval timestamp (`approvedAt`) must be present and not expired (enforcing a 30-minute approval TTL).
- **Option Invalidation**: Any re-ranking or option generation revokes prior approvals.

---

## 2. Distributed Systems Guarantees & Realities

| Distributed Capability | Stage 1 Claim | Stage 2 Forensic Reality & Remediation |
| :--- | :--- | :--- |
| **Delivery Semantics** | Claimed "Exactly-Once" | **Remediated to At-Least-Once with Idempotent Consumer Deduplication**. Truly distributed networks cannot guarantee exactly-once delivery across external airline PSS/GDS boundaries due to the Two Generals' Problem. Flyo enforces idempotency keys at the database, saga, and API levels. |
| **Outbox Pattern** | Transactional Outbox Staging | Verified: Events are staged atomically inside the Spring `@Transactional` boundary in `outbox_events` and dispatched via polling with optimistic leasing (`leased_by`, `lease_expires_at`). |
| **Saga Compensation** | Automatic Compensation | Verified: Compensating actions are registered per step (`PAYMENT` $\rightarrow$ void/refund, `VCC` $\rightarrow$ freeze/cancel, `BOOKING` $\rightarrow$ cancel/escalate). If compensation fails, state transitions to `FAILED_ESCALATED` for human intervention. |
| **Distributed Rate Limiting** | In-Memory Sliding Window | Single-node in-memory limiter identified. Stage 2 provides a pluggable distributed rate limiter interface with Redis/PostgreSQL advisory lock backing. |
| **Provider Idempotency** | Local Idempotency Keys | Verified locally: Local deduplication prevents duplicate calls. Stage 2 adds upstream provider-level idempotency header forwarding (`Idempotency-Key` / client request tokens). |

---

## 3. Test Suite Verification & Edge Case Coverage

The approval gate hardening was subjected to rigorous regression testing in `ApprovalGateEdgeTest.java`:
1. `testSagaRejectsAwaitingApprovalState`: A case in `AWAITING_APPROVAL` threw `IllegalStateException` with message `Case must be in APPROVED state. Current state: AWAITING_APPROVAL`.
2. `testSagaRejectsExpiredApproval`: An approval older than 30 minutes was rejected with `Approval has expired`.
3. `testSagaRejectsMismatchedOption`: An approval for option `O1` when attempting to execute `O2` was rejected with `does not match requested option`.
4. `testSagaRejectsEscalatedCase`: An escalated case cleared all approval fields and threw `Case must be in APPROVED state`.
5. `testSagaRejectsAnonymousApprover`: Approval attempts with null or blank approvers were strictly rejected.

**Test Run Results:**
- 142/142 Backend Java Tests: **PASS (0 Failures, 0 Errors, 0 Skipped)**
- 26/26 Python ML Tests: **PASS**
- Total Automated Tests: **168 Tests Passing Cleanly**
