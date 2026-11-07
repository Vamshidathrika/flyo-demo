# FLYO — STAGE 4 WORKFLOW STATE TRANSITION MATRIX
## Complete Deterministic Lifecycle, State Invariants & Security Guards

**Classification:** Flyo Core Safety Specification  
**Version:** 4.0.0-PROD-STAGE4  
**Date:** November 2026  
**Implementation File:** `CaseStateMachineService.java` (44 Deterministic States)

---

## 1. Primary Operational States & Transition Matrix

The table below catalogs valid state transitions, authorized actors, entry guards, and automated side effects.

| From State | Allowed Target States | Permitted Actors | Guard Condition / Prerequisite | Side Effects / Outbox Events |
| :--- | :--- | :--- | :--- | :--- |
| **`DETECTED`** | `OPTIONS_READY`, `AWAITING_APPROVAL`, `FETCHING_ITINERARY`, `PASSENGER_NOTIFIED`, `PASSENGER_DECLINED`, `ESCALATED` | `Flyo Radar Poller`, `Flyo System`, `Webhook Processor` | Flight disruption confirmed with confidence $\ge 70\%$. | Outbox: `CASE_DETECTED`, `CASE_STATE_CHANGED`. Emits audit log. |
| **`OPTIONS_READY`** | `AWAITING_APPROVAL`, `APPROVED`, `OPTION_SELECTED`, `NO_OPTIONS_AVAILABLE`, `PASSENGER_DECLINED`, `MANUAL_INTERVENTION_REQUIRED`, `ESCALATED` | `Flyo Ranking Engine`, `Flyo Operator` | Viable alternative flights discovered complying with MCT rules. Prior unexecuted approvals revoked. | Outbox: `OPTIONS_GENERATED`. Revokes stale cards if present. |
| **`AWAITING_APPROVAL`** | `APPROVED`, `OPTIONS_READY`, `PASSENGER_DECLINED`, `ESCALATED` | `Flyo Supervisor`, `Flyo Operator` | Options presented on console or WhatsApp. Case awaiting explicit human selection. | Emits audit log. Notification to supervisor queue. |
| **`APPROVED`** | `REBOOKING`, `PAYMENT_INSTRUMENT_READY`, `PAYMENT_AUTH_PENDING`, `OPTIONS_READY`, `FAILED_ESCALATED`, `ESCALATED`, `PASSENGER_DECLINED` | `Flyo Supervisor`, `Flyo Operator` | **Non-Negotiable:** Approver is authenticated human (`ROLE_OPERATOR`/`SUPERVISOR`), approval age $< 30\text{m}$, valid option ID. Conflicting option rejected. | Outbox: `SAGA_INITIATED`. Issues VCC if extra fare $> 0$. Locks case option. |
| **`PAYMENT_AUTH_PENDING`** | `PAYMENT_AUTHORIZED`, `PAYMENT_CAPTURED`, `PAYMENT_VERIFIED`, `REBOOKING`, `PAYMENT_FAILED`, `PAYMENT_RECONCILIATION_REQUIRED`, `ESCALATED` | `Flyo Treasury Adapter` | Fare difference $> 0$. VCC creation requested from payment gateway. | Generates tokenized single-use VCC (no CVV). Outbox: `PAYMENT_AUTHORIZED`. |
| **`PAYMENT_AUTHORIZED`** | `PAYMENT_CAPTURED`, `REBOOKING`, `VERIFYING_BOOKING`, `PAYMENT_RECONCILIATION_REQUIRED`, `BOOKING_FAILED`, `ESCALATED` | `Flyo Treasury Adapter`, `Saga Worker` | Virtual card successfully authorized with MCC 3000 (Airlines). | Advances saga to `STEP_DISPATCH_REBOOK`. |
| **`REBOOKING`** | `VERIFYING_BOOKING`, `VERIFYING_TICKET`, `RECONCILING`, `DONE`, `BOOKING_FAILED`, `FAILED_ESCALATED`, `PAYMENT_RECONCILIATION_REQUIRED` | `Saga Coordinator`, `AirlineRebookAdapter` | Rebooking command dispatched to carrier host or Playwright worker. | Worker acquires lease lock. Outbox: `REBOOK_DISPATCHED`. |
| **`RECONCILING`** | `VERIFYING_BOOKING`, `VERIFYING_TICKET`, `DONE`, `BOOKING_FAILED`, `PAYMENT_VOIDED`, `PAYMENT_REFUNDED`, `MANUAL_INTERVENTION_REQUIRED`, `ESCALATED` | `Saga Coordinator`, `Reconciliation Service` | Ambiguous outcome (socket timeout, network drop) during rebooking dispatch. | Queries carrier booking API/portal by name & flight before retrying. |
| **`VERIFYING_BOOKING`** | `VERIFYING_TICKET`, `RECONCILING`, `DONE`, `BOOKING_FAILED`, `FAILED_ESCALATED`, `MANUAL_INTERVENTION_REQUIRED`, `ESCALATED` | `Flyo Saga Coordinator`, `Booking Verifier` | Carrier returned confirmed PNR string. | Verifies reservation exists in carrier host. |
| **`VERIFYING_TICKET`** | `DONE`, `RECONCILING`, `BOOKING_FAILED`, `FAILED_ESCALATED`, `MANUAL_INTERVENTION_REQUIRED`, `ESCALATED` | `Flyo Ticket Verifier`, `TicketVerificationProvider` | Confirmed PNR exists. Independent check queries electronic ticket coupon. | Amadeus Altea coupon verification. If coupon void/missing, fails closed to `MANUAL_INTERVENTION_REQUIRED`. |
| **`MANUAL_INTERVENTION_REQUIRED`** | `DONE`, `FAILED_ESCALATED`, `ESCALATED` | `Human Ticketing Desk Operator` | Unresolved ambiguity or ticketing coupon validation failure. Consequential automation stopped. | Sets `manual_next_step`. Dispatches high-priority alert to airline desk. |
| **`DONE`** | Terminal State (No automated outgoing transitions) | `Flyo Saga Coordinator` | Authoritative PNR confirmed, e-ticket coupon `OPEN` and verified, VCC settled. | Outbox: `SAGA_COMPLETED`. Triggers passenger WhatsApp and Salesforce CRM sync. |
| **`FAILED_ESCALATED`** | `AWAITING_APPROVAL`, `OPTIONS_READY` (Manual Reset Only) | `Flyo Saga Coordinator`, `Flyo Operator` | Max booking attempts exhausted, or carrier confirmed sold out / rejected. Backward compensation complete. | Releases VCC authorization. Notifies operator console with diagnostic trace. |
| **`ESCALATED`** | `AWAITING_APPROVAL`, `OPTIONS_READY` (Manual Operator Reset) | `Flyo Operator`, `Supervisor` | Explicit operator rejection or unrecoverable system exception. | Case flagged for manual rebooking queue. |

---

## 2. Critical Safety Invariants

### Invariant 1: Consequential Action Authorization
No consequential state (`REBOOKING`, `PAYMENT_AUTH_PENDING`, `DONE`) can ever be reached without transitioning through `APPROVED`.
- `AWAITING_APPROVAL` $\not\to$ `REBOOKING` (Throws `ApprovalRequiredException`).
- Unauthenticated / anonymous approval $\not\to$ `APPROVED` (Throws `ApprovalRequiredException`).
- Stale approval ($> 30\text{m}$) $\not\to$ `APPROVED` (Throws `ApprovalRequiredException`).

### Invariant 2: Independent Electronic Ticketing Gate
Confirmed PNR $\neq$ Passenger Carriage.
- A PNR reservation can exist with an unissued, voided, or suspended e-ticket.
- In `STEP_VERIFY_TICKET`, `TicketVerificationProvider` checks that coupon status is `OPEN`.
- If ticket is invalid, transition to `DONE` is strictly prohibited. Case is forced into `MANUAL_INTERVENTION_REQUIRED`.

### Invariant 3: Single-Use Financial Containment
- VCCs are bound to `case_id` and `option_id`.
- Virtual card credit limits are strictly clamped to approved fare delta.
- Spending limits are verified against corporate agency policy ($\le \text{₹}50,000$ default); exceeding limit aborts rebooking and marks saga `FAILED`.
- Zero-cost rebookings bypass VCC generation entirely.

### Invariant 4: Non-Destructive Downstream Messaging
- Failure of WhatsApp messaging or Salesforce CRM synchronization does **not** roll back confirmed flight bookings or void valid tickets.
- Passenger messaging and CRM operations are recorded with independent execution statuses (`notification_status`, `crm_sync_status`).
