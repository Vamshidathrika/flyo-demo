# Flyo First Client Integration Blueprint & Real Aviation Operations Architecture

**Document Status:** Approved Engineering Blueprint  
**Role:** Principal Forward Deployment Engineer & Airline Systems Specialist  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. First Client Selection & Operational Scope

Based on the customer discovery analysis in `FIRST_CLIENT_PILOT_RECOMMENDATION.md`, the recommended initial customer is a **Corporate Travel Management Company (TMC) / Corporate Travel Agency** managing high-volume business travel across major Indian domestic and international departure corridors (e.g., DEL, BOM, BLR, HYD).

### Why the Corporate TMC Wins:
1. **Immediate PNR Access:** The agency creates and manages PNRs under its own GDS Pseudo City Code (PCC). It owns the servicing rights and does not require complex airline PSS committee approvals to read or service bookings.
2. **Multi-Carrier Protection Mandate:** When an airline flight is cancelled, the airline itself will only rebook passengers on its own flights or alliance partners. A corporate TMC has the commercial mandate to rebook across **any competing carrier** (e.g. shifting an executive from cancelled Air India AI-839 onto IndiGo 6E-402) to protect critical business travel.
3. **High Willingness to Pay:** Corporate accounts pay premium retainers for automated 24/7 Duty of Care.

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                              FIRST CLIENT PILOT TOPOLOGY                               │
 │                                                                                        │
 │  DATA TIER 1: OPERATIONAL TELEMETRY                                                    │
 │  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
 │  │ Cirium B2B Flight Status Alert Stream / Airport AODB (Push Webhooks)            │  │
 │  │ ─ Real-time gate pushback, wheels-up, airborne tracking, delay codes, cancellations│
 │  └───────────────────────────┬──────────────────────────────────────────────────────┘  │
 │                              │                                                         │
 │  DATA TIER 2: PASSENGER ITINERARIES                                                    │
 │  ┌───────────────────────────┴──────────────────────────────────────────────────────┐  │
 │  │ Corporate Agency GDS Queue Sweeper (Sabre REST APIs / PCC Partition)             │  │
 │  │ ─ Ingests active corporate bookings departing within 48-hour rolling window       │  │
 │  └───────────────────────────┬──────────────────────────────────────────────────────┘  │
 │                              │                                                         │
 │                              ▼                                                         │
 │  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
 │  │                     FLYO REAL-TIME DISRUPTION RESCUE DESK                        │  │
 │  │  ─ CADM Ingestion Engine & Normalization                                         │  │
 │  │  ─ IATA Official Airport MCT & Transfer Matrix Rules Engine                      │  │
 │  │  ─ Live Multi-Carrier Low Fare Availability (Sabre BargainFinderMax)             │  │
 │  │  ─ Operator Disruption Console (Human Approval Gate)                             │  │
 │  └───────────────────────────┬──────────────────────────────────────────────────────┘  │
 │                              │                                                         │
 │  DATA TIER 3: SERVICING & PASSENGER REACH                                              │
 │  ┌───────────────────────────┴──────────────────────┐                                  │
 │  ▼                                                  ▼                                  │
 │  ┌─────────────────────────┐               ┌─────────────────────────┐                 │
 │  │ Authorized GDS Exchange │               │ Passenger Outreach      │                 │
 │  │ ─ Sabre AutomatedBook   │               │ ─ Meta WhatsApp Cloud   │                 │
 │  │ ─ IROP Waiver Codes     │               │ ─ 6-Digit OTP Challenge │                 │
 │  │ ─ PNR Segment Exchange  │               │ ─ Interactive Buttons   │                 │
 │  └─────────────────────────┘               └─────────────────────────┘                 │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Separation of Integration Domains: AIDX vs. NDC

A critical architectural correction required in Flyo is decoupling operational flight-data standards from commercial airline distribution standards:

| Integration Domain | Standard / Protocol | Data Scope | Flyo Adapter Implementation |
| :--- | :--- | :--- | :--- |
| **Operational Flight Data** | **IATA AIDX (RP 1797A) / Cirium Stream** | Flight status, runway timestamps, delay reason codes, gate assignments, baggage metrics. **Zero PNRs.** | [`CiriumFlightTelemetryAdapter.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/AviationRadarClient.java) |
| **Reservation Records** | **GDS Sabre REST / Navitaire Digital API** | Passenger names, record locators, booked segments, ticket numbers, coupon status, SSRs. | `GdsQueueSweeperAdapter.java` |
| **Commercial Distribution** | **IATA NDC 21.3 / GDS Low Fare Search** | Real-time seat inventory, dynamic pricing, fare families, ancillary upsell, OrderCreate, OrderChange. | `SabreBargainFinderAdapter.java` |
| **Servicing & Rebooking** | **GDS Automated Exchange / NDC OrderChange** | Involuntary flight re-accommodation, schedule change acceptance, ticket reissue. | [`SabreGdsBookingAdapter.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/rebook/SabreGdsBookingAdapter.java) |

### Rejection of 13-Digit e-Ticket as Sole Confirmation Rule
In legacy ticketing, an e-ticket number (e.g., `098-1234567890`) was the sole proof of issuance. However, under modern NDC and hybrid LCC PSS models (e.g., Navitaire New Skies, which is completely ticketless):
* An NDC order is confirmed via a **10-character NDC Order ID** (e.g., `ORD-8f192b4a`) and booking reference.
* Navitaire generates a **6-character Record Locator** without creating an e-ticket number.
* **Flyo Final-State Verification:** Flyo checks for an **authoritative booking reference AND confirmed segment status (`HK`/`SS`)**, alongside an e-ticket coupon status if operating in a traditional legacy GDS environment.

---

## 3. Authoritative Minimum Connecting Time (MCT) Architecture

### 3.1. Elimination of the "Universal 120-Minute" Assumption
In [`MctRulesService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/MctRulesService.java#L24-L26), the current code defines:
```java
public static final int FLYO_INTERNATIONAL_POLICY_MINUTES = 120;
```
Treating 120 minutes as a universal aviation rule is incorrect. Minimum Connecting Time is an official standard published by IATA for over 400 global airports, determined by airport geometry, terminal transit trains, customs/immigration clearance times, and bilateral airline interline agreements.

### 3.2. Separation of the Five Aviation Decision Concepts
Flyo rigorously separates five distinct concepts in the CADM evaluation pipeline:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        FIVE AVIATION CONNECTION DECISION TIERS                         │
├──────────────────────────┬─────────────────────────────────────────────────────────────┤
│ Concept                  │ Definition & Authority                                      │
├──────────────────────────┼─────────────────────────────────────────────────────────────┤
│ 1. Published Airport MCT │ Official IATA station standard (e.g., DEL T3 D-I: 90 min;   │
│                          │ DEL T3 D-D: 60 min; FRA I-I: 45 min). Legal airline minimum.│
├──────────────────────────┼─────────────────────────────────────────────────────────────┤
│ 2. Carrier/Terminal MCT  │ Inter-terminal transit additions (e.g., DEL T1 to T3 coach  │
│                          │ transfer adds 60 min: total 150 min).                       │
├──────────────────────────┼─────────────────────────────────────────────────────────────┤
│ 3. Flyo Safety Policy    │ Flyo's conservative business policy buffer (e.g., 120 min   │
│                          │ for long-haul international flights during winter fog).     │
├──────────────────────────┼─────────────────────────────────────────────────────────────┤
│ 4. Estimated Inbound     │ Operational radar/ATC estimate of arrival delay (e.g., +45m)│
│    Delay (Telemetry)     │                                                             │
├──────────────────────────┼─────────────────────────────────────────────────────────────┤
│ 5. Predicted Disruption  │ Machine learning probability of catastrophic failure        │
│    Probability           │ (Experimental / Decoupled).                                 │
└──────────────────────────┴─────────────────────────────────────────────────────────────┘
```

### 3.3. Missing Coverage Catalog for Indian Hubs
Before commercial launch, the MCT database must incorporate:
1. **Terminal Change Shuttles:** Delhi (DEL) T1/T2 to T3 transfer times; Mumbai (BOM) T1 to T2 transfers.
2. **Baggage Re-check Rules:** Separate PNR bookings require passengers to collect baggage at carousel, exit to landside, and re-check at departure hall (requires minimum **180 minutes**).
3. **Interline Baggage Agreements:** Automated baggage transfer only applies if airlines have an active Interline Electronic Ticketing (IET) agreement.
