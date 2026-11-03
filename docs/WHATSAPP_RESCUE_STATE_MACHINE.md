# Flyo Rescue Agent — Canonical State Machine Reference

This document serves as the **authoritative single source of truth** for all lifecycle states, permitted transitions, triggers, and invariants in the Flyo Rescue platform. Both the WhatsApp Functional Workflow (`whatsapp-rescue-workflow.mmd`) and the Technical Sequence (`whatsapp-rescue-sequence.mmd`) are strictly derived from and synchronized with this specification and `CaseStateMachineService.java`.

---

## 1. Master State Inventory

| State Name | Classification | Description |
| :--- | :--- | :--- |
| `DETECTED` | Ingress | Disruption event received and validated from carrier webhook or aviation radar. |
| `PASSENGER_NOTIFIED` | Engagement | Proactive interactive cancellation message dispatched to passenger via Meta WhatsApp Cloud API. |
| `AWAITING_PASSENGER_DECISION` | Engagement | Awaiting passenger decision button click: `[ RESCUE MY TRIP ]` vs `[ ABANDON RESCUE ]`. |
| `PASSENGER_DECLINED` | Terminal / Exception | Passenger chose `[ ABANDON RESCUE ]`. Case closed; original ticket preserved; no auto-refund. |
| `FETCHING_ITINERARY` | Analysis | Retrieving complete PNR, segment details, cabin class, baggage rules, connecting flights, and hotel risks. |
| `SEARCHING_ALTERNATIVES` | Discovery | Searching carrier network for viable flight candidates matching passenger itinerary. |
| `OPTIONS_READY` | Discovery | MCT calculation and deterministic multi-factor option ranking completed. |
| `AWAITING_OPTION_SELECTION` | Engagement | Ranked alternative flight options delivered to passenger WhatsApp; awaiting selection. |
| `OPTION_SELECTED` | Selection | Passenger submitted option selection (e.g., `SELECT_OPTION_O1` or `SELECT_OPTION_O2`). |
| `REVALIDATING_OPTION` | Validation | Revalidating live seat inventory, fare rules, and downstream feasibility with carrier host. |
| `OPTION_EXPIRED` | Exception / Loop | Selected flight seats sold out prior to confirmation; triggers inventory refresh. |
| `REFRESHING_ALTERNATIVES` | Loop | Closed-loop search underway to refresh candidate flight inventory. |
| `FARE_CHANGED` | Exception / Loop | Airline fare rules changed during selection; invalidates old consent and displays new exact fare. |
| `AWAITING_PASSENGER_CONFIRMATION` | Confirmation | Final itinerary preview presented with exact additional fare; prompt for OTP consent dispatched. |
| `AWAITING_PASSENGER_OTP` | Security | 6-digit confirmation OTP armed and sent; awaiting passenger entry via WhatsApp chat. |
| `OTP_FAILED` | Security | Incorrect OTP submitted (< 5 failed attempts); attempt counter decremented; retry prompted. |
| `OTP_EXPIRED` | Security | OTP elapsed beyond 10-minute expiry window; fresh challenge auto-regenerated for retry. |
| `LOCKED_OUT` | Security / Escalation | 5 consecutive invalid OTP attempts reached; 30-minute security lockout activated; escalated. |
| `PASSENGER_VERIFIED` | Authorization | Passenger OTP successfully validated; consent locked to case, option, fare, and currency. |
| `VERIFYING_AGENCY_APPROVAL` | Governance (Path B) | Verifying agency spending limits (e.g. ₹50k/₹100k) and corporate policy compliance. |
| `AGENCY_APPROVAL_REQUIRED` | Governance (Path B) | Fare difference exceeds automated spending cap; policy exception flagged (not a payment failure). |
| `MANUAL_APPROVAL_PENDING` | Governance (Path B) | Pending manual travel manager approval in the Agency Operations Console. |
| `PAYMENT_INSTRUMENT_READY` | Treasury (Path B) | Single-use Corporate Virtual Card (VCC) prepared and locked to case, option, fare, and MCC 3000. |
| `PAYMENT_AUTH_PENDING` | Treasury (Path B) | Submitting payment authorization request to card issuer. |
| `PAYMENT_AUTHORIZED` | Treasury (Path B) | Card issuer approved transaction authorization for exact approved amount. |
| `PAYMENT_CAPTURED` | Treasury (Path B) | Funds captured and settled with card issuer following ticket confirmation. |
| `PAYMENT_VOIDED` | Treasury (Path B) | Uncaptured VCC authorization reversed/cancelled following booking rejection or timeout. |
| `PAYMENT_REFUNDED` | Treasury (Path B) | Captured funds refunded following downstream booking cancellation. |
| `PAYMENT_FAILED` | Treasury (Path B) | Payment declined by card issuer during authorization or capture. |
| `PAYMENT_OUTCOME_UNKNOWN` | Exception / Treasury | Payment or carrier outcome remains uncertain; prevents blind retries; flags manual audit. |
| `PAYMENT_RECONCILIATION_REQUIRED` | Exception / Treasury | Provider timeout or network drop after authorization; routes to carrier host reconciliation. |
| `REBOOKING` | Execution | Dispatched rebooking transaction to airline NDC API or Playwright partner portal worker. |
| `VERIFYING_BOOKING` | Verification | Authoritatively verifying new reservation on airline host system. |
| `VERIFYING_TICKET` | Verification | Authoritatively verifying electronic ticket (e-ticket) issuance and coupon validation. |
| `RECONCILING` | Reconciliation | Querying airline host using case idempotency key (`caseId`) to determine actual outcome. |
| `DONE` | Terminal / Rescued | Autonomous recovery fully complete; new PNR and verified e-ticket confirmed to passenger. |
| `NO_OPTIONS_AVAILABLE` | Exception | No viable replacement flights found within connection safety threshold. |
| `BOOKING_FAILED` | Exception | Airline rejected rebooking or seats sold out; card authorization voided; escalated. |
| `FAILED_ESCALATED` | Terminal / Exception | Unrecoverable failure requiring human operator intervention. |
| `MANUAL_INTERVENTION_REQUIRED` | Exception | Case paused awaiting human travel desk specialist review. |
| `ESCALATED` | Escalation | Active case handed over to human travel operations console. |

---

## 2. Permitted State Transitions Matrix

```
DETECTED
  ├──> PASSENGER_NOTIFIED
  ├──> AWAITING_PASSENGER_DECISION
  ├──> FETCHING_ITINERARY
  ├──> OPTIONS_READY
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

PASSENGER_NOTIFIED
  ├──> AWAITING_PASSENGER_DECISION
  ├──> FETCHING_ITINERARY
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

AWAITING_PASSENGER_DECISION
  ├──> FETCHING_ITINERARY (Trigger: [RESCUE MY TRIP])
  ├──> PASSENGER_DECLINED (Trigger: [ABANDON RESCUE])
  └──> ESCALATED

FETCHING_ITINERARY
  ├──> SEARCHING_ALTERNATIVES
  ├──> ITINERARY_FETCH_FAILED
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

SEARCHING_ALTERNATIVES
  ├──> OPTIONS_READY
  ├──> NO_OPTIONS_AVAILABLE
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

OPTIONS_READY
  ├──> AWAITING_OPTION_SELECTION
  ├──> OPTION_SELECTED
  ├──> NO_OPTIONS_AVAILABLE
  ├──> OPTION_EXPIRED
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

AWAITING_OPTION_SELECTION
  ├──> OPTION_SELECTED (Trigger: SELECT_OPTION_{id})
  ├──> OPTION_EXPIRED
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

OPTION_SELECTED
  ├──> REVALIDATING_OPTION
  ├──> AWAITING_PASSENGER_CONFIRMATION
  ├──> AWAITING_PASSENGER_OTP
  ├──> FARE_CHANGED
  ├──> OPTION_EXPIRED
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

REVALIDATING_OPTION
  ├──> AWAITING_PASSENGER_CONFIRMATION
  ├──> AWAITING_PASSENGER_OTP
  ├──> REBOOKING (Zero-fare bypass)
  ├──> VERIFYING_AGENCY_APPROVAL (Positive-fare)
  ├──> FARE_CHANGED
  ├──> OPTION_EXPIRED
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

AWAITING_PASSENGER_CONFIRMATION
  ├──> AWAITING_PASSENGER_OTP
  ├──> PASSENGER_VERIFIED
  ├──> REVALIDATING_OPTION
  ├──> OPTION_SELECTED
  ├──> FARE_CHANGED
  ├──> OPTION_EXPIRED
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

AWAITING_PASSENGER_OTP
  ├──> PASSENGER_VERIFIED (Trigger: Valid OTP submitted)
  ├──> OTP_FAILED (Trigger: Invalid OTP < 5 attempts)
  ├──> OTP_EXPIRED (Trigger: OTP > 10 min window)
  ├──> LOCKED_OUT (Trigger: 5 failed attempts)
  ├──> OPTION_SELECTED (Trigger: Passenger re-selects option)
  ├──> AWAITING_PASSENGER_CONFIRMATION
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

OTP_FAILED
  ├──> AWAITING_PASSENGER_OTP (Retry prompt)
  ├──> LOCKED_OUT (5th consecutive failure)
  ├──> OTP_EXPIRED
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

OTP_EXPIRED
  ├──> AWAITING_PASSENGER_OTP (Fresh challenge regenerated)
  ├──> AWAITING_PASSENGER_CONFIRMATION
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

LOCKED_OUT
  ├──> ESCALATED (30-minute lockout enforced)
  ├──> MANUAL_INTERVENTION_REQUIRED
  └──> FAILED_ESCALATED

PASSENGER_VERIFIED
  ├──> REBOOKING (Path A: ₹0 Additional Fare -> Zero-fare bypass)
  ├──> VERIFYING_AGENCY_APPROVAL (Path B: Positive Fare -> Agency spending check)
  ├──> PAYMENT_INSTRUMENT_READY
  ├──> ESCALATED
  └──> PASSENGER_DECLINED

VERIFYING_AGENCY_APPROVAL
  ├──> PAYMENT_INSTRUMENT_READY (Spending approved within cap)
  ├──> AGENCY_APPROVAL_REQUIRED (Spending exceeds cap)
  ├──> MANUAL_APPROVAL_PENDING
  ├──> PAYMENT_FAILED
  └──> ESCALATED

AGENCY_APPROVAL_REQUIRED
  ├──> MANUAL_APPROVAL_PENDING
  ├──> PAYMENT_INSTRUMENT_READY (Operator approved override)
  └──> ESCALATED

MANUAL_APPROVAL_PENDING
  ├──> PAYMENT_INSTRUMENT_READY (Approved in console)
  ├──> ESCALATED
  └──> MANUAL_INTERVENTION_REQUIRED

PAYMENT_INSTRUMENT_READY
  ├──> REBOOKING (VCC credentials injected into rebooking job)
  ├──> PAYMENT_AUTH_PENDING
  ├──> PASSENGER_DECLINED
  └──> ESCALATED

PAYMENT_AUTH_PENDING
  ├──> PAYMENT_AUTHORIZED
  ├──> PAYMENT_CAPTURED
  ├──> REBOOKING
  ├──> PAYMENT_FAILED
  ├──> PAYMENT_OUTCOME_UNKNOWN
  ├──> PAYMENT_RECONCILIATION_REQUIRED
  └──> ESCALATED

PAYMENT_AUTHORIZED
  ├──> PAYMENT_CAPTURED
  ├──> REBOOKING
  ├──> VERIFYING_BOOKING
  ├──> PAYMENT_VOIDED (On booking failure)
  ├──> PAYMENT_RECONCILIATION_REQUIRED
  └──> BOOKING_FAILED

PAYMENT_CAPTURED
  ├──> VERIFYING_BOOKING
  ├──> VERIFYING_TICKET
  ├──> RECONCILING
  ├──> PAYMENT_REFUNDED (On downstream cancellation)
  └──> DONE

PAYMENT_VOIDED
  ├──> BOOKING_FAILED
  ├──> ESCALATED
  └──> FAILED_ESCALATED

PAYMENT_REFUNDED
  ├──> BOOKING_FAILED
  ├──> ESCALATED
  └──> FAILED_ESCALATED

PAYMENT_OUTCOME_UNKNOWN
  ├──> PAYMENT_RECONCILIATION_REQUIRED
  ├──> RECONCILING
  ├──> PAYMENT_FAILED
  ├──> ESCALATED
  └──> FAILED_ESCALATED

REBOOKING
  ├──> VERIFYING_BOOKING (Outcome A: Confirmed Success)
  ├──> BOOKING_FAILED (Outcome B: Definitive Failure)
  ├──> PAYMENT_RECONCILIATION_REQUIRED (Outcome C: Timeout / Unknown)
  ├──> PAYMENT_AUTH_PENDING
  ├──> PAYMENT_AUTHORIZED
  ├──> PAYMENT_CAPTURED
  ├──> RECONCILING
  └──> FAILED_ESCALATED

VERIFYING_BOOKING
  ├──> VERIFYING_TICKET (Reservation confirmed on host)
  ├──> RECONCILING
  ├──> BOOKING_FAILED
  └──> DONE

VERIFYING_TICKET
  ├──> DONE (e-Ticket validated; recovery complete)
  ├──> RECONCILING
  ├──> BOOKING_FAILED
  └──> FAILED_ESCALATED

RECONCILING
  ├──> VERIFYING_BOOKING (Host inquiry confirms reservation exists)
  ├──> VERIFYING_TICKET
  ├──> BOOKING_FAILED (Host inquiry confirms no reservation exists)
  ├──> PAYMENT_VOIDED
  ├──> PAYMENT_REFUNDED
  ├──> PAYMENT_OUTCOME_UNKNOWN
  ├──> DONE
  └──> ESCALATED

BOOKING_FAILED
  ├──> PAYMENT_VOIDED
  ├──> PAYMENT_REFUNDED
  ├──> REBOOKING (Retry with different option)
  ├──> ESCALATED
  └──> FAILED_ESCALATED

DONE
  └──> [Terminal State] Core booking updated to 'Rescued'. Confirmed PNR delivered.
```

---

## 3. Strict Invariant Guarantees

1. **Mandatory Passenger Consent**: No rebooking transaction can ever enter `REBOOKING` without prior transition through `PASSENGER_VERIFIED`.
2. **Zero-Fare Isolation**: Journeys with `extraCost == 0` can **never** enter `VERIFYING_AGENCY_APPROVAL`, `PAYMENT_INSTRUMENT_READY`, `PAYMENT_AUTH_PENDING`, `PAYMENT_AUTHORIZED`, or `PAYMENT_CAPTURED`.
3. **Agency Governance Boundary**: An extra fare exceeding an agency spending cap transitions strictly to `AGENCY_APPROVAL_REQUIRED` and `MANUAL_APPROVAL_PENDING`. It is never misclassified as `PAYMENT_FAILED`.
4. **Idempotency & Concurrency**: All state transitions record `oldState`, `newState`, `actor`, `reason`, `timestamp`, and `caseId`. Simultaneous requests on the same case are serialized using case locks and optimistic locking.
5. **No Blind Retries**: A provider timeout transitions to `PAYMENT_RECONCILIATION_REQUIRED` and `RECONCILING`. Host inquiry using the original `caseId` idempotency key is required before any subsequent action.
