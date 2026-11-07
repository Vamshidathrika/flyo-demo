# Client Data Ownership and Source of Truth (SoT) Framework

**Document Status:** Enterprise Architecture Policy  
**Author:** Principal Enterprise Integration Architect  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. The Multi-System Data Reality in Aviation

A critical error in enterprise systems engineering is attempting to make a secondary intelligence platform the "master database" for data it does not generate. In aviation operations, different systems hold legal, financial, and operational authority over different attributes of the same physical event.

Flyo adheres to a strict **Source of Truth (SoT) Hierarchy**. Flyo is the authoritative system of record **only** for its internal decision models: Disruption Risk Confidence Scores, Reaccommodation Ranking Ledgers, Operator Approvals, and Audit Timestamps. For all external aviation domains, Flyo acts strictly as a consumer, correlation engine, and execution proxy.

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                              DATA DOMAIN SOT MAPPING                                   │
 ├────────────────────────────────┬───────────────────────────────────────────────────────┤
 │ Data Domain                    │ Authoritative Enterprise Source of Truth (SoT)        │
 ├────────────────────────────────┼───────────────────────────────────────────────────────┤
 │ Published Flight Schedule      │ Airline SSIM Timetable / OAG Schedule Master          │
 │ Active Gate & Runway Times     │ Airport AODB / SITA AIDX / Local Airport Tower        │
 │ Airborne Flight Telemetry      │ Live ADS-B Radar / Airline Operations Control (OCC)    │
 │ Flight Cancellation / Delay    │ Airline OCC Dispatch (Corroborated by AIDX / Cirium)  │
 │ Passenger Itinerary & Segments │ Airline PSS (Altéa/New Skies) or Agency GDS (Sabre)   │
 │ Electronic Ticket & Coupon     │ Airline CRS Ticketing Database / E-Ticket Server      │
 │ Seat Inventory Availability    │ Real-time PSS / GDS Inventory Controller              │
 │ Passenger Contact Channel      │ Booking Contact Element (SSR/CTC) in PSS / CRM        │
 │ Disruption Rescue Evaluation   │ FLYO INTELLIGENCE ENGINE (CADM Risk Ledger)           │
 │ Operator & Passenger Approval  │ FLYO STATE MACHINE AUDIT TRAIL (Cryptographic Log)    │
 │ Reissued Booking Confirmation  │ Authoritative PSS/GDS PNR Commit Reply                │
 └────────────────────────────────┴───────────────────────────────────────────────────────┘
```

---

## 2. Domain-by-Domain Authority Analysis

### 2.1. Flight Operational Telemetry vs. PSS Flight Status
* **The Conflict:** During winter fog operations in Delhi (DEL), an airline's customer-facing app and PSS may display a flight as "Delayed by 45 minutes" to prevent passenger panic, while the airline's OCC and airport tower have already placed the aircraft under an indefinite ground-stop or cancelled the rotation due to crew duty limits.
* **Authority Rule:** 
  - The **Airline OCC Dispatch Message (MVT/AIDX)** is legally authoritative for the operational status of the aircraft.
  - The **Airport AODB** is authoritative for gate allocation, pushback clearances, and runway de-icing queues.
  - The **Passenger Service System (PSS)** is authoritative for whether the passenger is permitted to board or change tickets.
* **Flyo Behavior:** Flyo flags the discrepancy immediately: *"Status Mismatch Detected: Aircraft grounded by OCC/Tower (Confidence 98%), but PSS status remains active. Proactive rescue pipeline initiated in SHADOW mode pending PSS status synchronization."*

### 2.2. Passenger Itineraries: PSS vs. GDS
* **The Conflict:** A corporate traveler books via an Amex GDS channel. Later, the passenger calls the airline call center directly to change their seat or add baggage. The airline Altéa PSS updates immediately, but the GDS PNR may not reflect the update until synchronized via a passive segment notification.
* **Authority Rule:**
  - The **Operating Carrier PSS** holds the authoritative coupon and check-in status.
  - The **Booking GDS** holds authority over travel agency ticketing rights, corporate travel policies, and agency billing commissions.
* **Flyo Behavior:** Flyo always checks the Operating Carrier PSS coupon status (`OPEN`, `CHECKED_IN`, `LIFTED`) before initiating an agency GDS exchange to prevent issuing duplicate tickets against lifted coupons.

### 2.3. Seat Availability & Fare Quotas
* **The Conflict:** Third-party aggregator caches or cached search results show 4 seats available on a rescue flight, but upon sending a booking request, the airline PSS returns `UNABLE_FLIGHT_FULL` (UC).
* **Authority Rule:**
  - The **Operating Carrier Inventory Engine** (accessed via real-time NDC `OfferPrice` or GDS `BargainFinderMax`) is the sole real-time authority.
* **Flyo Behavior:** Flyo never executes a rebooking based on cached search results. Flyo executes a dynamic live availability verification immediately prior to presenting options to the operator and re-verifies availability at the millisecond of booking execution.

---

## 3. Conflict Resolution & Evidence Reconciliation Engine

Flyo's `FlightEvidenceReconciliationService` implements a strict mathematical rule engine to resolve conflicting observations without silently discarding data:

```
               CONFIDENCE SCORING & RESOLUTION MATRIX
┌───────────────────────┬───────────────────────┬──────────────┬──────────────────────────────┐
│ Primary Observation   │ Corroborating Feed    │ Confidence   │ Flyo Action Engine           │
├───────────────────────┼───────────────────────┼──────────────┼──────────────────────────────┤
│ OCC / AIDX Cancelled  │ Destination CAT III   │ 98% (MAX)    │ Immediate Rescue Pipeline    │
│                       │ Weather / Fog Alert   │              │ (Trigger WhatsApp & Rebook)  │
├───────────────────────┼───────────────────────┼──────────────┼──────────────────────────────┤
│ OCC / AIDX Cancelled  │ Weather Normal        │ 93% (HIGH)   │ Operational Cancellation     │
│                       │ (Fleet / Crew issue)  │              │ (Trigger Immediate Rescue)   │
├───────────────────────┼───────────────────────┼──────────────┼──────────────────────────────┤
│ Live Radar Delay > 90m│ Severe Hub Weather    │ 95% (HIGH)   │ MCT Breach Evaluation        │
│                       │                       │              │ (Trigger Pre-Emptive Rescue) │
├───────────────────────┼───────────────────────┼──────────────┼──────────────────────────────┤
│ Weather Zero-Vis      │ Airline Status Shows  │ 50% (HOLD)   │ Advisory Monitoring Only     │
│ (CAT III Active)      │ "On Time"             │              │ (DO NOT Cancel; Wait OCC)    │
├───────────────────────┼───────────────────────┼──────────────┼──────────────────────────────┤
│ Radar Shows Airborne  │ PSS / Web Shows Cancel│ 10% (REJECT) │ Suppress Rescue Pipeline     │
│ (Wheels-Up Verified)  │                       │              │ (Telemetry Trumps Static App)│
└───────────────────────┴───────────────────────┴──────────────┴──────────────────────────────┘
```

### 3.1. Non-Overwriting Evidence Preservation Policy
When two authorized sources emit contradictory telemetry, Flyo **never** overwrites the database record. Instead, Flyo executes an append-only evidence reconciliation:
1. Both raw payloads are preserved in `disruption_evidence_history` with source system, timestamp, and signature.
2. A calculated `reconciled_confidence` score (0–100) and `confidence_level` (`CONFIRMED`, `ELEVATED_DELAY`, `WEATHER_ADVISORY_MONITORING`, `REJECTED`) are assigned.
3. If confidence is below the safety threshold (85%), automated write-back is **strictly blocked**, and the case is routed to the Operator Console for manual investigation.
