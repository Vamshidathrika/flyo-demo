# FLYO STAGE 5 — BOOKING EXECUTION & TICKET VERIFICATION ARCHITECTURE

**Classification:** Safety, Ticketing & Financial Governance Document  
**Target:** Airline Booking Execution, Idempotency, and Anti-Duplicate-Booking Controls  
**Scope:** `RebookingSagaCoordinator`, `AmadeusGdsSandboxClient`, `AmadeusAlteaAdapter`  
**Status:** Certified Architecture & Contract Verified  
**Date:** November 2026  

---

## 1. Booking Safety Controls & Approval Enforcement

Booking execution commits airline inventory and issues corporate payment instruments. To guarantee that no unauthorized or duplicate flight order can occur, Flyo enforces 5 mandatory safety gates in `RebookingSagaCoordinator`:

1. **Explicit Human Approval State:** Rebooking dispatch is rejected with `ApprovalRequiredException` unless the case state is explicitly `APPROVED`. States such as `AWAITING_APPROVAL` or `REBOOKING` are strictly disallowed.
2. **Authenticated Approver Identity:** An authenticated operator or supervisor username (e.g. `supervisor_alpha`) must be recorded. `anonymousUser` or null identities are rejected.
3. **Approval Expiry Window (30-minute TTL):** Due to airline seat inventory and dynamic fare volatility, approvals older than 30 minutes are automatically invalidated, requiring supervisor re-approval.
4. **Option ID Immutability:** The option ID submitted for booking dispatch must exactly match `rescueCase.approvedOptionId`.
5. **Durable Idempotency Key:** Every saga execution requires an idempotency key structured as `rebook_saga_{caseId}_{optionId}`. Duplicate API dispatches return the existing saga instance immediately.

---

## 2. Elimination of Duplicate Booking Vulnerability

### The Forensic Discovery
In Stage 4, `RebookingSagaCoordinator.reconcileWithCarrier()` contained a severe vulnerability:
```java
// Stage 4 Flawed Logic:
Optional<RescueCase> cOpt = rescueCaseRepository.findById(caseId);
if (cOpt.isPresent() && cOpt.get().getNewPnr() != null) {
    return ExternalProviderResponse.success(...);
}
return ExternalProviderResponse.failure("Carrier", "PNR_NOT_FOUND", ...);
```
If a network timeout occurred during `POST /v1/booking/flight-orders`, `newPnr` had not yet been written to the local database. The reconciliation step interpreted this as `CONFIRMED_ABSENT` and scheduled an automated retry. If the carrier host actually created the booking during the timeout, the retry would execute a second booking, resulting in **two distinct PNRs and duplicate corporate card charges for a single passenger**.

### The Stage 5 Remediation
1. **Ambiguity Preservation:** When an external call times out and PNR is unconfirmed, the outcome is marked `UNKNOWN` / `AMBIGUOUS`.
2. **Ban on Blind Retries:** Automated retries after network timeouts are forbidden.
3. **Fail-Closed Escalation:** The saga coordinator immediately halts automated execution, transitions the case to `MANUAL_INTERVENTION_REQUIRED`, and instructs the human operator:
   > *"Booking outcome ambiguous after provider timeout. Dispatched to manual ticketing desk to prevent duplicate booking. Operator must verify carrier host before re-attempting booking."*
4. **Empirical Test Verification:** Verified in `Stage5EnterpriseVerificationAndProviderIntegrationTest.testAmbiguousOutcomePreventsBlindRetry`.

---

## 3. Independent Electronic Ticket & Coupon Verification

An airline booking reference (PNR) represents an inventory reservation; it does not prove that an electronic ticket has been issued.

In Stage 5:
- **Two-Step Verification:** `RebookingSagaCoordinator` separates `STATE_VERIFYING_BOOKING` from `STATE_VERIFYING_TICKET`.
- **Authoritative Coupon Status:** `TicketVerificationProvider` checks that the ticket coupon is active (`OPEN` or `CHECKED_IN`).
- **Failure Isolation:** If a ticket coupon is `VOID`, `REFUNDED`, or `EXCHANGED`, saga execution does not proceed to `STATE_DONE`. The case transitions to `MANUAL_INTERVENTION_REQUIRED` to alert the manual ticketing desk, preventing passengers from arriving at the airport with an un-ticketed reservation.
