# Flyo Rescue Agent — WhatsApp-First Autonomous Flight Recovery Workflow

## 1. Executive Summary

Flyo Rescue Agent provides an autonomous, passenger-driven disruption recovery pipeline operated over the official Meta WhatsApp Cloud API. Whenever an authoritative flight cancellation or disruption event occurs, Flyo bypasses call center queues and immediately engages the affected traveler on their verified WhatsApp channel with two primary actions:

1. `[ RESCUE MY TRIP ]`
2. `[ ABANDON RESCUE ]`

The entire recovery process—itinerary connection risk analysis, alternative flight discovery, deterministic option ranking, passenger selection, live availability and fare revalidation, mandatory confirmation OTP verification, explicit branching between zero-fare and positive-fare payment paths, corporate virtual card authorization, carrier rebooking, and authoritative PNR and e-ticket verification—runs autonomously in real time.

---

## 2. Corrected Master Architecture Flowchart

```mermaid
flowchart TD
    A["Flight Disruption Detected<br/>(Authoritative Carrier Webhook / Radar Ingress)"] --> B["WhatsApp Interactive Notification<br/>[ RESCUE MY TRIP ] | [ ABANDON RESCUE ]"]
    
    B -->|ABANDON RESCUE| C["PASSENGER_DECLINED<br/>Close Rescue Case<br/>Preserve Original Ticket (No Auto-Refund)<br/>Alert Agency Operations Desk"]
    
    B -->|RESCUE MY TRIP| D["Validate Passenger Ownership<br/>Fetch Complete PNR & All Itinerary Segments<br/>Analyze Downstream Connection & Hotel Risks"]
    
    D --> E["Search Candidate Alternatives<br/>Calculate MCT Buffer & Connection Feasibility<br/>Deterministic Multi-Factor Option Ranking"]
    
    E --> F{"Alternative Flights<br/>Available?"}
    F -->|No Alternatives| G["NO_OPTIONS_AVAILABLE &rarr; ESCALATED<br/>Hand over to Human Travel Desk"]
    F -->|Viable Options Found| H["Send Ranked Options on WhatsApp<br/>• Option 1 (Recommended)<br/>• Option 2<br/>• Option 3"]
    
    H --> I["Passenger Selects Flight Option<br/>(e.g., SELECT_OPTION_O1 or O2)"]
    
    I --> J["REVALIDATING_OPTION<br/>Revalidate Live Carrier Seat Availability & Fare Rules<br/>Recalculate Downstream Feasibility"]
    
    J --> K{"Availability or<br/>Fare Changed?"}
    K -->|Expired| L["OPTION_EXPIRED &rarr; Refresh Options"]
    K -->|Fare Changed| M["FARE_CHANGED &rarr; Display Updated Cost<br/>Require Passenger Re-Acceptance"]
    K -->|Confirmed Unchanged| N["Present Final Itinerary Preview & Exact Cost<br/>Generate Single-Use Confirmation OTP<br/>(Bound to Case + Phone + Option + Fare + Currency)"]
    
    N --> O["AWAITING_PASSENGER_OTP<br/>Passenger Enters 6-Digit Consent Code"]
    
    O --> P{"Verify Passenger OTP"}
    P -->|Invalid Code &lt; 5 Attempts| Q["OTP_FAILED<br/>Decrement Attempt Counter & Prompt Retry"]
    P -->|Expired (> 10 min)| R["OTP_EXPIRED &rarr; Regenerate Challenge"]
    P -->|5 Failed Attempts| S["LOCKED_OUT &rarr; ESCALATED<br/>30-Minute Security Lockout<br/>Alert Operations Desk"]
    P -->|Valid OTP| T["PASSENGER_VERIFIED<br/>Passenger Consent Authoritatively Locked"]
    
    T --> U{"Check Additional Fare<br/>Difference"}
    
    %% PATH A: Zero Additional Fare
    U -->|₹0 Additional Fare| V["PATH A: NO PAYMENT REQUIRED<br/>• Skip VCC Issuance Entirely<br/>• Skip Payment Gateway Processing<br/>• REBOOKING Directly Dispatched"]
    
    %% PATH B: Positive Additional Fare
    U -->|Positive Fare (> ₹0)| W["PATH B: PAYMENT REQUIRED<br/>VERIFYING_AGENCY_APPROVAL<br/>Check Corporate Policy & Agency Spending Limits"]
    
    W --> X{"Within Agency<br/>Limit (₹50k)?"}
    X -->|Exceeded / Denied| Y["PAYMENT_FAILED &rarr; ESCALATED<br/>Agency Approval Required"]
    X -->|Approved| Z["PAYMENT_AUTH_PENDING<br/>Issue Single-Use Corporate Virtual Card (VCC)<br/>Locked to Case, Option & Approved Fare"]
    
    Z --> AA["PAYMENT_VERIFIED<br/>Treasury Settlement Instrument Armed"]
    AA --> AB["REBOOKING<br/>Dispatch Rebooking Subprocess with VCC"]
    
    V --> AC["AUTHORIZED REBOOKING<br/>Airline NDC API / Playwright Partner Portal Engine"]
    AB --> AC
    
    AC --> AD{"Rebooking Provider<br/>Outcome"}
    AD -->|Failed / Unreachable| AE{"Payment Authorized?"}
    AE -->|Yes (Path B)| AF["PAYMENT_RECONCILIATION_REQUIRED<br/>Flag Card for Treasury Reversal"]
    AE -->|No (Path A)| AG["BOOKING_FAILED &rarr; ESCALATED"]
    AF --> AG
    
    AD -->|Success| AH["VERIFYING_BOOKING<br/>Independently Verify PNR on Airline Host System"]
    AH --> AI["VERIFYING_TICKET<br/>Confirm Electronic Ticket (e-Ticket) Issuance"]
    
    AI --> AJ["DONE (RESCUED)<br/>Record Financial Settlement (Path B only)<br/>Update Core Booking Record to 'Rescued'"]
    
    AJ --> AK["FINAL WHATSAPP CONFIRMATION<br/>Verified Itinerary + Confirmed PNR<br/>e-Ticket Status + Baggage Transfer Guidance<br/>Direct Boarding Pass URL"]
```

---

## 3. End-to-End Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    actor Pax as Passenger (WhatsApp)
    participant Meta as Meta Cloud API / Webhooks
    participant Ingress as Cancellation Ingress Service
    participant Engine as Passenger Rescue Engine
    participant SM as Case State Machine
    participant Provider as Carrier Discovery & NDC APIs
    participant Security as Passenger OTP Security
    participant Treasury as Payment & VCC Settlement
    participant Rebook as Playwright / Airline Portal

    Note over Ingress: Step 1: Disruption Detected
    Ingress->>SM: DETECTED -> PASSENGER_NOTIFIED -> AWAITING_PASSENGER_DECISION
    Ingress->>Meta: Step 2: Proactive WhatsApp Alert ([RESCUE MY TRIP] / [ABANDON RESCUE])
    Meta->>Pax: Interactive Notification Delivered

    alt Passenger Clicks [ABANDON RESCUE]
        Pax->>Meta: Taps [ABANDON RESCUE]
        Meta->>Engine: Webhook: ABANDON_RESCUE
        Engine->>SM: Transition: PASSENGER_DECLINED
        Engine->>Meta: Confirmation: Rescue closed. Original booking preserved.
    else Passenger Clicks [RESCUE MY TRIP]
        Pax->>Meta: Taps [RESCUE MY TRIP]
        Meta->>Engine: Webhook: RESCUE_MY_TRIP
        Engine->>Meta: Step 3: Immediate acknowledgment dispatched
        Engine->>SM: Transition: FETCHING_ITINERARY -> SEARCHING_ALTERNATIVES
        Engine->>Provider: Step 4 & 5: Validate PNR, fetch onward segments, search alternatives
        Provider-->>Engine: Ranked candidate options (Buffer, Duration, Cost)
        Engine->>SM: Transition: OPTIONS_READY -> AWAITING_OPTION_SELECTION
        Engine->>Meta: Step 6: Dispatches ranked flight options with interactive buttons
        Meta->>Pax: Displays options (FL301 +₹4,200 | FL101 ₹0 | FL401 +₹1,500)

        Pax->>Meta: Step 7: Selects Option (e.g. FL101 ₹0 or FL301 +₹4,200)
        Meta->>Engine: Webhook: SELECT_OPTION_{OptionCode}
        Engine->>SM: Transition: OPTION_SELECTED -> REVALIDATING_OPTION
        Engine->>Provider: Revalidate live inventory and fare rules
        Provider-->>Engine: Inventory confirmed
        Engine->>SM: Transition: AWAITING_PASSENGER_CONFIRMATION -> AWAITING_PASSENGER_OTP
        Engine->>Security: Step 8: Arms single-use OTP challenge (bound to Case, Option, Amount)
        Security-->>Engine: Generated 6-digit confirmation code
        Engine->>Meta: Dispatches Itinerary Preview & OTP prompt
        Meta->>Pax: "Reply with verification code to authorize rebooking"

        Pax->>Meta: Submits 6-digit OTP
        Meta->>Engine: Webhook: Inbound 6-digit message
        Engine->>Security: Validates SHA-256 hash, expiry, option, and amount
        Security-->>Engine: Validation SUCCESS (Attempt counter cleared)
        Engine->>SM: Transition: AWAITING_PASSENGER_OTP -> PASSENGER_VERIFIED

        alt Path A: Zero Additional Fare (₹0)
            Note over Engine,Treasury: PATH A: Payment processing completely bypassed
            Engine->>SM: Transition: PASSENGER_VERIFIED -> REBOOKING
            Engine->>Rebook: Dispatch zero-fare rebooking to booking provider
        else Path B: Positive Additional Fare (> ₹0)
            Engine->>SM: Transition: PASSENGER_VERIFIED -> VERIFYING_AGENCY_APPROVAL
            Engine->>Treasury: Check agency policy & spending limit (₹50,000 threshold)
            Treasury-->>Engine: Spending approved
            Engine->>SM: Transition: VERIFYING_AGENCY_APPROVAL -> PAYMENT_AUTH_PENDING
            Engine->>Treasury: Issue single-use Virtual Corporate Card (VCC)
            Treasury-->>Engine: VCC issued & authorized (Card locked to approved amount)
            Engine->>SM: Transition: PAYMENT_AUTH_PENDING -> PAYMENT_VERIFIED -> REBOOKING
            Engine->>Rebook: Dispatch payment-authorized rebooking with VCC credentials
        end

        Rebook-->>Engine: Portal rebooking executed. Confirmed PNR returned
        Engine->>SM: Transition: REBOOKING -> VERIFYING_BOOKING
        Engine->>Provider: Step 10: Authoritatively verify PNR on carrier host
        Provider-->>Engine: Booking confirmed
        Engine->>SM: Transition: VERIFYING_BOOKING -> VERIFYING_TICKET
        Engine->>Provider: Confirm e-ticket issuance and coupon coupons
        Provider-->>Engine: Electronic ticket ISSUED

        opt If Path B (> ₹0 fare)
            Engine->>Treasury: Record financial settlement on VCC
        end

        Engine->>SM: Transition: VERIFYING_TICKET -> DONE (RESCUED)
        Engine->>Meta: Step 11: Dispatches Final WhatsApp Confirmation (PNR, Ticket, Baggage)
        Meta->>Pax: Verified e-ticket, boarding pass link, and baggage guidance delivered
    end
```

---

## 4. Lifecycle States & Exceptional Matrix

### Core Lifecycle States
1. `DETECTED` — Initial disruption alert received from aviation radar or airline webhook.
2. `PASSENGER_NOTIFIED` — Proactive WhatsApp notification generated and dispatched.
3. `AWAITING_PASSENGER_DECISION` — Awaiting passenger choice: `[ RESCUE MY TRIP ]` vs `[ ABANDON RESCUE ]`.
4. `FETCHING_ITINERARY` — Retrieving full PNR, onward connecting flights, and hotel bookings.
5. `SEARCHING_ALTERNATIVES` — Searching carrier networks for viable candidate flights.
6. `OPTIONS_READY` — Multi-factor deterministic ranking complete; options evaluated.
7. `AWAITING_OPTION_SELECTION` — Options dispatched to passenger; awaiting selection.
8. `OPTION_SELECTED` — Passenger selected preferred flight option.
9. `REVALIDATING_OPTION` — Revalidating live seat inventory, fare rules, and connection buffer with carrier.
10. `AWAITING_PASSENGER_CONFIRMATION` — Final itinerary and exact cost preview presented.
11. `AWAITING_PASSENGER_OTP` — 6-digit consent OTP dispatched; awaiting passenger entry.
12. `PASSENGER_VERIFIED` — Passenger OTP verified; consent authoritatively bound to case and amount.
13. `VERIFYING_AGENCY_APPROVAL` — (Path B only) Verifying agency spending limits and policy compliance.
14. `PAYMENT_AUTH_PENDING` — (Path B only) Authorizing single-use corporate virtual card (VCC).
15. `PAYMENT_VERIFIED` — (Path B only) Corporate payment instrument successfully authorized.
16. `REBOOKING` — Dispatched automated rebooking transaction to provider or browser automation.
17. `VERIFYING_BOOKING` — Authoritatively verifying reservation on carrier host system.
18. `VERIFYING_TICKET` — Verifying electronic ticket issuance and coupon validation.
19. `DONE` — Autonomous recovery fully complete; confirmed e-ticket dispatched to passenger.

### Exceptional States
- `PASSENGER_DECLINED` — Passenger chose `[ ABANDON RESCUE ]`. Recovery closed without ticket cancellation.
- `NO_OPTIONS_AVAILABLE` — No viable replacement flight candidates found within connection threshold.
- `ITINERARY_FETCH_FAILED` — Unable to retrieve PNR or downstream itinerary from GDS.
- `OPTION_EXPIRED` — Selected flight option seats sold out prior to confirmation.
- `FARE_CHANGED` — Airline updated fare rules during selection; requires updated passenger approval.
- `OTP_FAILED` — Invalid OTP submitted (decrements remaining attempts).
- `OTP_EXPIRED` — Confirmation OTP expired (10-minute window elapsed).
- `LOCKED_OUT` — 5 consecutive invalid OTP attempts reached; phone locked for 30 minutes.
- `PAYMENT_FAILED` — Agency spending limit exceeded or corporate card issuer decline.
- `PAYMENT_RECONCILIATION_REQUIRED` — Booking failed after payment authorized; flagged for treasury void/refund.
- `BOOKING_FAILED` — Airline portal or GDS rejected rebooking transaction.
- `FAILED_ESCALATED` — Terminal failure requiring human intervention.
- `MANUAL_INTERVENTION_REQUIRED` — Suspended for travel desk specialist review.
- `ESCALATED` — Active case handed over to human travel operations console.

---

## 5. Security & Payment Invariants

1. **Compulsory Passenger Consent OTP**: Every recovery journey (both zero-fare and positive-fare) requires verified passenger OTP confirmation.
2. **Strict Zero-Fare Boundary**: If an alternative flight has `extraCost == 0`, the platform **never** generates a corporate virtual card or calls payment processors.
3. **Payment Authentication Isolation**: Passenger WhatsApp chat only collects consent confirmation. Bank OTPs, 3D Secure credentials, CVVs, and raw card numbers are **never** requested or processed via WhatsApp.
4. **Idempotent Ingress**: Duplicate flight radar alerts or repeated webhook callbacks are deduplicated using composite keys (`bookingId + flight + type`).
5. **No Blind Retries on Payment Failure**: If an airline portal times out after a corporate card was charged, the case transitions to `PAYMENT_RECONCILIATION_REQUIRED` rather than repeatedly attempting duplicate charges.
