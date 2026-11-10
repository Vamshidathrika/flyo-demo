# STAGE 13C — PASSENGER WORKFLOW INTEGRITY & AUTHORITATIVE BOOKING CERTIFICATION PLAN

**Milestone:** Stage 13C — Passenger Transaction Integrity & Authoritative Booking Certification  
**Authoritative Requirement:** Zero Hardcoded Operational Data Architecture  
**Baseline Commit:** `c06df34`  
**Branch:** `feature/stage-10b-provider-verified-rescue`  

---

## 1. Problem Statement & Root Cause Forensic Summary

Stage 13B verified distributed saga recovery, transactional outbox reliability, and mathematical ML normalization. However, operational evaluation revealed five critical defects in the passenger transaction lifecycle:

1. **Defect 1 — Selected Flight vs Confirmed Flight Mismatch:**
   - *Symptom:* Passenger selected `FL301`, but `FL201` appeared in the confirmation.
   - *Root Cause:* In `rebook.ts`, if exact text match fails, the Playwright agent silently falls back to `button.btn-airline-select.first()`, which selects `FL201`. Furthermore, server-side option selection was not immutably locked against the rescue attempt, allowing option drift.
2. **Defect 2 — Abandoned Rescue Completed via Old OTP:**
   - *Symptom:* Passenger abandoned a rescue attempt (`ABANDON RESCUE`), but subsequent submission of the previously issued OTP successfully executed rebooking.
   - *Root Cause:* `PassengerRescueWorkflowService.handleAbandonRescue()` transitioned case state to `PASSENGER_DECLINED`, but failed to invalidate active OTP challenges in `PassengerOtpChallengeRepository` and `PassengerOtpService`. `PassengerConfirmationService.confirmAndVerify()` did not check whether the case was in an abandoned or declined state.
3. **Defect 3 — Mock/Simulated Booking Represented as Genuine Ticket:**
   - *Symptom:* In test and sandbox profiles, WhatsApp messages stated: `✈️ Your flight rescue is confirmed! ... Airline PNR: M7B3K9 ... E-Ticket Status: ISSUED & VALIDATED ... Baggage Guidance: Checked luggage automatically transfers`.
   - *Root Cause:* `PassengerWhatsAppMessagingService` lacked provider state awareness, emitting authoritative airline confirmation copy regardless of whether the provider was `MOCK`, `SIMULATED`, `SANDBOX_CONFIRMED`, or `LIVE_PROVIDER_CONFIRMED`.
4. **Defect 4 — Duplicate Success Notifications Delivered:**
   - *Symptom:* Passenger received two identical WhatsApp booking confirmation messages within 6 milliseconds.
   - *Root Cause:* Both `RebookingOrchestrationService.java` (line 303) and `PassengerRescueWorkflowService.java` (line 155) independently invoked `messagingService.sendBookingConfirmation()`, lacking an idempotency deduplication guard on outbound delivery.
5. **Defect 5 — Booking Command `B3` Rejected as Unrecognized:**
   - *Symptom:* Sending `STATUS B3`, `DISRUPT B3`, or `B3` via WhatsApp bot resulted in: `Unrecognized command: *B3*`.
   - *Root Cause:* `WhatsAppCommandService` used strict equality checks (`command.equals("STATUS")`) and did not support `STATUS <bookingId>` or direct booking ID token ingress.

In addition, the system must enforce **Zero Hardcoded Operational Data**: eliminating all demo passengers, hardcoded PNRs, hardcoded flight numbers, fake booking counters, and hardcoded fallback strings from production services and UI.

---

## 2. Stage 13C Phased Implementation Decomposition

### Phase 1 — Repository Baseline & Test Count Reconciliation
- Audit baseline commit `c06df34`.
- Reconcile Stage 13B test counts:
  - Production Certification (7) + E2E Rescue (1) + Outbox & Saga (13) + Audit Ledger (4) + Idempotency (5) + Provider/Tenant (7) + Distributed Saga (3) + WhatsApp Passenger (21) + Frontend Observability (8) + Python ML (36) = **105 automated tests**.
  - Document exact accounting of 105 tests across suites in `STAGE_13C_FAILURE_REPRODUCTION.md`.

### Phase 2 — Authoritative Selected-Flight Consistency
- Bind immutable `optionId` and `flightNumber` to `RescueCase` upon selection.
- Enforce strict server-side validation during confirmation: reject if option ID does not match the active challenge.
- Update `rebook.ts` to strictly fail if `Select ${config.flight}` is not found; remove dangerous `first()` button fallback.
- Build TDD reproduction test asserting `FL301` selection cannot produce `FL201`.

### Phase 3 — Attempt-Bound OTP Security & Abandonment Invalidation
- Add `invalidateOtpsForCase(String caseId, String reason)` in `PassengerOtpService`.
- In `PassengerRescueWorkflowService.handleAbandonRescue()`, explicitly invalidate all active OTP challenges.
- In `PassengerConfirmationService.confirmAndVerify()`, verify that case state is strictly `AWAITING_PASSENGER_OTP` or `AWAITING_PASSENGER_CONFIRMATION`; fail with `ATTEMPT_ABANDONED` if case is declined, cancelled, or expired.
- Build TDD regression test reproducing the abandonment exploit and proving rejection.

### Phase 4 — Provider Truthfulness & State Classification
- Introduce explicit enum `ProviderExecutionState`:
  - `MOCK`, `SIMULATED`, `SANDBOX_CONFIRMED`, `LIVE_PROVIDER_CONFIRMED`, `TICKET_ISSUED`, `RECONCILIATION_REQUIRED`, `BLOCKED_BY_EXTERNAL_ACCESS`.
- Separate booking confirmation from ticket issuance.
- Update `PassengerWhatsAppMessagingService`:
  - When provider is `MOCK` or `SIMULATED`, output explicit disclosure:
    `"Simulation completed. No real airline booking or ticket was created."`
  - Remove unverified guarantees (baggage transfer, boarding pass availability) unless verified by provider response.

### Phase 5 — Outbound Notification & Rebooking Idempotency
- Remove duplicate call in `PassengerRescueWorkflowService.java` (retain single authoritative call in `RebookingOrchestrationService`).
- Introduce `outbound_messages` deduplication via message hash / idempotency key to prevent double delivery under retry.

### Phase 6 — Command Parser & Booking Entity Resolution
- Enhance `WhatsAppCommandService`:
  - Support `STATUS`, `STATUS <bookingId>`, `DISRUPT <bookingId>`, `B1`..`B5`, `HELP`, `ABANDON`, `RESCUE`, `CONFIRM <code>`.
  - Resolve booking IDs dynamically from `BookingRepository` rather than hardcoded string matching.
  - Return clear, helpful error if booking ID does not exist in tenant database.

### Phase 7 — Zero Hardcoded Operational Data Elimination
- Search and purge all hardcoded passenger names ("Ananya Sharma", "Vikram Malhotra"), hardcoded PNR fallbacks ("R7X2K9", "M7B3K9"), and hardcoded flight schedules from production backend and frontend code.
- Ensure empty databases display genuine empty states in OCC (Trip Monitor, Heatmap, Telemetry).
- Provide explicit simulation profile (`application-simulation.properties` / `mock-airline/`) cleanly segregated from production.

### Phase 8 — End-to-End Certification & Required Deliverables
- Execute Scenarios A through H and generate all required markdown reports:
  1. `STAGE_13C_FAILURE_REPRODUCTION.md`
  2. `STAGE_13C_OPTION_CONSISTENCY.md`
  3. `STAGE_13C_OTP_SECURITY.md`
  4. `STAGE_13C_PROVIDER_TRUTHFULNESS.md`
  5. `STAGE_13C_IDEMPOTENCY_RESULTS.md`
  6. `STAGE_13C_COMMAND_PARSER_RESULTS.md`
  7. `STAGE_13C_END_TO_END_EVIDENCE.md`
  8. `STAGE_13C_FINAL_READINESS.md`
  9. `ZERO_HARDCODED_OPERATIONAL_DATA_AUDIT.md`
