# Flyo Rescue Agent — WhatsApp Recovery Workflow Automated Test Report

**Environment:** macOS (Local Engine)  
**Database:** PostgreSQL (H2 Test Engine & Flyway v2 Schema)  
**Test Suite:** JUnit 5 + Spring Boot Test + Mockito  
**Playwright Agent:** TypeScript 5.9 + tsx v4.23 + Chromium Headless Shell 156.0  
**Overall Status:** **94 / 94 Tests Passing (100% Clean Execution)**

---

## 1. Test Execution Summary

| Test Class | Scenarios Covered | Tests Run | Failures | Errors | Result |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `WhatsAppPassengerRescueWorkflowTest` | End-to-End Passenger WhatsApp Flow (Scenarios A–P + E2, J, K, L) | 20 | 0 | 0 | **PASSED** |
| `WhatsAppCommandBotTest` | Operator Commands, Interactive Actions & Quick Replies | 8 | 0 | 0 | **PASSED** |
| `FlightAlertTest` | Flight Disruption Radar Ingress & Deduplication | 2 | 0 | 0 | **PASSED** |
| `ApprovalGateTest` | State Machine Transitions & Audit Trail | 1 | 0 | 0 | **PASSED** |
| `RankingEngineTest` | Multi-Factor Deterministic Flight Ranking | 1 | 0 | 0 | **PASSED** |
| `FinancialAndBookingSafetyTest` | Financial safety, voiding, idempotency keys | 5 | 0 | 0 | **PASSED** |
| `PaymentSettlementTest` | VCC lifecycle, ledger, spending caps | 4 | 0 | 0 | **PASSED** |
| `Regression & Security Suite` | Migrations, OTP hashing, Rate limits, OpenApi docs | 53 | 0 | 0 | **PASSED** |
| **Total** | **Full System Regression** | **94** | **0** | **0** | **100% PASSED** |

---

## 2. Detailed Verification of WhatsApp Recovery Scenarios (Part 21 Verification Scenarios)

### Scenario A: Zero Additional Fare (₹0) Recovery
- **Workflow:** Flight cancelled &rarr; Passenger alerts dispatched &rarr; `[ RESCUE MY TRIP ]` &rarr; Options displayed &rarr; Option 1 (₹0 fare difference) selected &rarr; Mandatory single-use passenger consent OTP verified.
- **Payment Invariant:** Verified that `PaymentSettlementService.getActiveCardForCase("C1")` is empty (`cardOpt.isEmpty() == true`). **No Virtual Corporate Card (VCC) was issued and payment gateway was completely bypassed.**
- **Post-Verification Execution:** `REVALIDATING_OPTION` &rarr; `REBOOKING` &rarr; `VERIFYING_BOOKING` &rarr; `VERIFYING_TICKET` &rarr; `DONE`.
- **Authoritative Confirmation:** Extracted PNR `M7B3K9`. Sent final confirmation message detailing complimentary ₹0 recovery, e-ticket issuance, and luggage auto-transfer.

### Scenario B: Positive Additional Fare (+₹4,200) Recovery
- **Workflow:** Flight cancelled &rarr; Passenger alerts dispatched &rarr; `[ RESCUE MY TRIP ]` &rarr; Options displayed &rarr; Option 2 (+₹4,200 fare difference) selected &rarr; Itinerary preview & fare difference accepted &rarr; Mandatory single-use passenger consent OTP verified.
- **Payment Lifecycle:** Verified `verifyAgencySpendingLimit("C1", 4200)` passed &rarr; State transitioned to `VERIFYING_AGENCY_APPROVAL` &rarr; `PAYMENT_INSTRUMENT_READY` (single-use VCC issued and locked for exact amount ₹4,200) &rarr; `REBOOKING` &rarr; `PAYMENT_AUTH_PENDING` &rarr; `PAYMENT_AUTHORIZED` &rarr; `PAYMENT_CAPTURED`.
- **Post-Verification Execution:** `REBOOKING` with VCC credentials &rarr; `VERIFYING_BOOKING` &rarr; `VERIFYING_TICKET` &rarr; Financial settlement recorded on VCC &rarr; `DONE`.
- **Authoritative Confirmation:** Extracted PNR `R7X2K9`. Sent final confirmation message detailing confirmed PNR, settled corporate payment, and 24h check-in boarding pass note.

### Scenario C: Passenger Abandons Rescue (`[ ABANDON RESCUE ]`)
- **Workflow:** Passenger taps `[ ABANDON RESCUE ]`.
- **Outcome:** State transitions directly to `PASSENGER_DECLINED`. Outstanding OTP challenges invalidated. Case closed cleanly.
- **Safety Boundary:** Original booking record is retained without unverified cancellation or auto-refund. Agency operations desk alerted.

### Scenario D: Dynamic Multi-Passenger Isolation
- **Verification:** Tested with dynamic passenger **Vikram Malhotra** (`+919811223344`, booking `B-DYN-1`).
- **Outcome:** Verified zero hardcoded values. All routing, WhatsApp sender detection, option ranking, and state machine isolation operate dynamically per phone number.

### Scenario E: Security — Invalid OTP & Lockout Gate
- **Verification:** Submitted 5 consecutive invalid OTP attempts (`999999`, `999998`, ...).
- **Outcome:** Attempt 1–4 decremented counter with `OTP_FAILED`. Attempt 5 triggered `LOCKED_OUT` &rarr; `ESCALATED`.
- **Lockout Policy:** Phone locked for 30 minutes; subsequent submissions with valid OTP rejected.

### Scenario E2: Security — OTP Expiry Window & Auto-Regeneration
- **Verification:** OTP challenge elapsed beyond the 10-minute validity threshold.
- **Outcome:** State transitions to `OTP_EXPIRED` &rarr; `AWAITING_PASSENGER_OTP`. Old code is invalidated; fresh challenge is generated; passenger retries with new code and successfully completes rebooking (`DONE`).

### Scenario F: Booking Failure & Payment Reconciliation
- **Verification:** Mock airline rebooking provider fails after positive-fare VCC authorization.
- **Outcome:** Case transitions to `PAYMENT_RECONCILIATION_REQUIRED` and `BOOKING_FAILED`. Transaction is not blindly retried; flagged for corporate treasury reversal.

### Scenario G: Fare Change Detection & Fresh Passenger Consent
- **Verification:** OTP challenge armed for ₹4,200. Revalidation detects fare changed to ₹5,500.
- **Outcome:** OTP verification returns `FARE_CHANGED`. Case transitions to `FARE_CHANGED`, preventing unauthorized charges until fresh passenger approval is granted.

### Scenario H: Duplicate Disruption Ingress Protection
- **Verification:** Received identical cancellation radar event for booking `B1` twice.
- **Outcome:** First event notified passenger and created case. Second duplicate event was idempotently suppressed by deduplication filter.

### Scenario I: Agency Spending Limit Compliance
- **Verification:** Tested option with ₹75,000 fare difference against ₹50,000 policy threshold.
- **Outcome:** `verifyAgencySpendingLimit` returned `false`. Fare difference exceeding policy limit is blocked from automated booking.

### Scenario J: Application Restart Recovery & Idempotency
- **Verification:** Simulated backend restart while case is in active state (`AWAITING_PASSENGER_OTP`).
- **Outcome:** State, selected option, and passenger phone number are accurately restored from database. Subsequent rebooking attempts on completed cases execute idempotently without duplicating bookings or payment transactions.

### Scenario K: Confirmed Failure Voids Card Authorization
- **Verification:** Carrier reports definitive failure (seat inventory sold / locked).
- **Outcome:** Uncaptured VCC authorization is immediately voided with card issuer (`voidAuthorization`), transitioning card status to `CANCELLED` (`PAYMENT_VOIDED`). Case transitions to `BOOKING_FAILED`.

### Scenario L: Timeout Outcome Triggers Host Inquiry & Reconciliation
- **Verification:** Rebooking provider times out (504 Gateway Timeout / network drop).
- **Outcome:** Platform avoids blind retries. State transitions `PAYMENT_RECONCILIATION_REQUIRED` &rarr; `RECONCILING`. Host inquiry verifies reservation state using idempotency key (`caseId`). When no ticket exists, authorization is voided and case is marked `BOOKING_FAILED`.

### Scenario M: Airline Involuntary Disruption Waiver (Rule 240 / DGCA CAR)
- **Verification:** Applied involuntary carrier disruption waiver.
- **Outcome:** Additional fare difference waived from ₹4,200 to ₹0. System dynamically routes booking through zero-fare Path A, bypassing VCC issuance entirely.

### Scenario N: Option Expiration Closed-Loop Auto-Refresh
- **Verification:** Selected option availability window expires prior to OTP submission.
- **Outcome:** Closed loop executes `OPTION_EXPIRED` &rarr; `REFRESHING_ALTERNATIVES` &rarr; `SEARCHING_ALTERNATIVES` &rarr; `OPTIONS_READY` &rarr; `AWAITING_OPTION_SELECTION`. Passenger is seamlessly returned to active option selection without dead-end termination.

### Scenario O: Closed-Loop OTP Retry Recovery
- **Verification:** Passenger enters wrong code on first attempt (`000000`), then submits correct OTP on second attempt.
- **Outcome:** Attempt 1 records `OTP_FAILED` and returns to `AWAITING_PASSENGER_OTP` with retry guidance. Attempt 2 validates successfully, locking consent and completing rebooking (`DONE`).

### Scenario P: Non-Hardcoded Dynamic Recovery (Vikram Malhotra)
- **Verification:** Created fresh booking `B-VIK-2` for Vikram Malhotra (`+919811223344`).
- **Outcome:** Complete end-to-end flow: Ingress cancellation &rarr; "RESCUE MY TRIP" &rarr; Option selection &rarr; Dynamic OTP verification &rarr; Authoritative ticketing (`DONE`). Confirmed zero hardcoding to Ananya.

### Scenario Q: Concurrent Passenger Callbacks Race Protection
- **Verification:** Sent 4 simultaneous `RESCUE_MY_TRIP` callbacks concurrently using thread pool.
- **Outcome:** All callbacks processed safely without database deadlocks or corrupted state transitions. Active case progressed cleanly to `AWAITING_OPTION_SELECTION`.

---

## 3. Playwright Agent Browser Verification

- **Command:** `npx tsx agent/verify-acceptance-ui.js` & `npx tsx agent/verify-mobile-mockup.js`
- **Result:** Automated headless Chrome session successfully verified:
  1. Airline portal checkout navigation.
  2. Passenger form injection and VCC card processing.
  3. Confirmation extraction yielding new airline PNR `R7X2K9`.
  4. WhatsApp mobile mockup interactive rendering and message alignment.
