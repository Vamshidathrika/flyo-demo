# FLYO RESCUE — STAGE 13C: PASSENGER WORKFLOW DEFECT REPRODUCTION

## Executive Summary
This document provides exact reproduction traces, root-cause analyses, and regression test proofs for the five critical passenger-facing workflow defects identified in the Flyo Rescue operations transcript.

Prior to Stage 13C, these defects permitted option selection drift, resurrection of abandoned rescue cases using stale credentials, untruthful mock airline claims, duplicate interactive messaging, and command parsing failures.

---

## Defect Inventory & Root Cause Matrix

| Defect ID | Description | Root Cause | File & Line Reference | Regression Test |
| :--- | :--- | :--- | :--- | :--- |
| **DEFECT-1** | Flight Selection Drift: Selected FL301, confirmed FL201 | Fallback in Playwright agent script `rebook.ts` silently defaulted to `button.btn-airline-select.first()` when exact flight text matching was ambiguous. | `flyo-rescue-demo/agent/rebook.ts:54-68` | `OriginalPassengerWorkflowDefectsRegressionTest#testDefect1_Fl301Selected_MustConfirmFl301AndNeverFl201` |
| **DEFECT-2** | Abandoned Rescue Completed via Old OTP | `PassengerRescueWorkflowService.handleAbandonRescue` set status to `PASSENGER_DECLINED` but did not invalidate active OTP challenges in `PassengerOtpChallengeRepository`. `PassengerConfirmationService` did not check case state before verifying OTP. | `PassengerConfirmationService.java:48-62`, `PassengerOtpService.java:94-112`, `PassengerRescueWorkflowService.java:185-195` | `OriginalPassengerWorkflowDefectsRegressionTest#testDefect2_AbandonedRescueFollowedByOldOtp_MustBeRejected` |
| **DEFECT-3** | Mock Airline Represented as Live Airline Ticket | `PassengerWhatsAppMessagingService` emitted unconditional statements such as `"Your flight rescue is confirmed!"` and `"E-Ticket Status: ISSUED & VALIDATED"` without environment or provider verification checks. | `PassengerWhatsAppMessagingService.java:180-218` | `OriginalPassengerWorkflowDefectsRegressionTest#testDefect3_MockProviderMustNeverClaimAuthoritativeLiveTicketing` |
| **DEFECT-4** | Duplicate Outbound Success Notifications | Concurrent dispatch occurred from both `RebookingOrchestrationService.executeRebookingWorkflow` and `PassengerRescueWorkflowService.handleOtpSubmission` within 6 milliseconds, lacking a distributed message deduplication key. | `RebookingOrchestrationService.java:303`, `PassengerRescueWorkflowService.java:155`, `PassengerWhatsAppMessagingService.java:45-56, 172-185` | `OriginalPassengerWorkflowDefectsRegressionTest#testDefect4_ConcurrentSuccessDispatchesMustDeduplicateAndNeverDoubleDeliver` |
| **DEFECT-5** | Passenger Command `B3` Rejected as Unrecognized | `WhatsAppCommandService` parsed commands using strict equality against `STATUS`, requiring `STATUS <bookingId>` with `parts.length > 1`, rejecting bare `B3` or `STATUS B3`. | `WhatsAppCommandService.java:65-98, 142-168` | `OriginalPassengerWorkflowDefectsRegressionTest#testDefect5_CommandParserMustAcceptB3AndSingleTokenBookingCommands` |

---

## Detailed Failure Traces & Fix Verification

### Defect 1: Selected Flight vs Confirmed Flight Mismatch
* **Observed Failure in Live Run**: Passenger selected Option 2 (FL301, BOM transit, ₹4,200). The automated browser worker clicked the first available `.btn-airline-select` button on `select.html`, which was FL201 (direct ₹0). The system then confirmed FL201 to the passenger, creating an operational disaster.
* **Remediation**:
  1. Updated `rebook.ts` to strictly require `#select-${config.flight.toLowerCase()}-btn` or `button:has-text("Select ${config.flight}")`.
  2. Removed `btn-airline-select.first()` fallback; Playwright throws a hard exception if the specific flight selector is missing.
  3. Added an explicit confirmation assertion on `review.html` asserting `#display-flight` strictly equals `config.flight`.
  4. Backend verification in `RebookingOrchestrationService` validates `completed.getApprovedOptionId()` matches `option.getFlight()` before executing provider settlement.
* **Test Verification**:
  ```
  [INFO] Running com.flyo.rescue.OriginalPassengerWorkflowDefectsRegressionTest
  [INFO] testDefect1_Fl301Selected_MustConfirmFl301AndNeverFl201 PASSED
  ```

### Defect 2: Abandoned Rescue Resumed via Old OTP
* **Observed Failure in Live Run**: Passenger typed `ABANDON`. Case transitioned to `PASSENGER_DECLINED`. 4 minutes later, passenger or attacker submitted the previous OTP `849201`. The backend accepted the code and executed rebooking on an abandoned case.
* **Remediation**:
  1. Added `PassengerOtpService.invalidateOtpsForCase(caseId, reason)` which purges ephemeral caches and sets DB challenge status to `INVALIDATED`.
  2. `PassengerRescueWorkflowService.handleAbandonRescue` immediately triggers `invalidateOtpsForCase`.
  3. `PassengerConfirmationService.confirmAndVerify` now inspects case state prior to evaluating codes; if state is `ABANDONED`, `PASSENGER_DECLINED`, `DONE`, `ESCALATED`, or `FAILED`, it immediately halts with `ATTEMPT_ABANDONED` without evaluating the hash.
* **Test Verification**:
  ```
  [INFO] testDefect2_AbandonedRescueFollowedByOldOtp_MustBeRejected PASSED
  ```

### Defect 3: Mock Provider Misrepresented as Live Ticket
* **Observed Failure in Live Run**: The system told the passenger that flight FL301 was booked and ticketed on Air India, with baggage automatically routed to YYZ, when in reality it only interacted with local mock HTML fixtures.
* **Remediation**:
  1. Injected Spring `Environment` into `PassengerWhatsAppMessagingService`.
  2. If the active profile is not `prod` or provider is `SANDBOX`/`MOCK`, the message header mandates:
     `*[SIMULATION / SANDBOX MODE]*\nSimulation completed. No real airline booking or ticket was created.`
  3. Baggage notes and boarding pass sections explicitly state simulation status.
* **Test Verification**:
  ```
  [INFO] testDefect3_MockProviderMustNeverClaimAuthoritativeLiveTicketing PASSED
  ```

### Defect 4: Duplicate Outbound Success Notifications
* **Observed Failure in Live Run**: The passenger received two identical WhatsApp booking confirmations 6ms apart.
* **Remediation**:
  1. Implemented a concurrent thread-safe deduplication cache in `PassengerWhatsAppMessagingService` using idempotency key `dedupeKey = caseId + "_" + verifiedPnr + "_" + passengerPhone`.
  2. Suppresses subsequent delivery attempts within the session window with warning log `[Passenger WhatsApp] Suppressed duplicate booking confirmation delivery`.
* **Test Verification**:
  ```
  [INFO] testDefect4_ConcurrentSuccessDispatchesMustDeduplicateAndNeverDoubleDeliver PASSED
  ```

### Defect 5: Booking Command `B3` Unrecognized
* **Observed Failure in Live Run**: User typed `B3` in WhatsApp. System replied: `Unrecognized command. Type OPTIONS, ACCEPT <id>, DECLINE, OTP <code>, STATUS, or HELP.`
* **Remediation**:
  1. Enhanced `WhatsAppCommandService.handleCommand` regex to intercept bare booking tokens (`^B[1-9][0-9]*$`) and route directly to `sendSingleBookingStatus(senderPhone, bookingId)`.
  2. Enhanced `STATUS` handler to accept `STATUS B1`, `STATUS B3`, etc.
* **Test Verification**:
  ```
  [INFO] testDefect5_CommandParserMustAcceptB3AndSingleTokenBookingCommands PASSED
  ```
