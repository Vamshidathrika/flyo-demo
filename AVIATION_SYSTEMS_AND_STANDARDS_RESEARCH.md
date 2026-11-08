# Comprehensive Aviation Systems and Industry Standards Research

**Document Status:** Technical Authority Reference  
**Author:** Principal Enterprise Integration Architect & Aviation Systems Specialist  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Industry Integration Landscape & Functional Taxonomy

A common error among software engineers approaching commercial aviation is assuming that airline information systems are homogenous or modern RESTful microservices. In reality, airline IT is a multi-layered ecosystem built over five decades, comprising distinct domain silos governed by international standards (IATA, ICAO, ACI).

```
 ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 │                                 COMMERCIAL DISTRIBUTION PLANE                                   │
 │    ┌─────────────────────────────┐   ┌───────────────────────────┐   ┌───────────────────────┐  │
 │    │       Global Distribution   │   │     IATA NDC (New         │   │   IATA ONE Order      │  │
 │    │       Systems (GDS)         │   │  Distribution Capability) │   │  (Unified Retail      │  │
 │    │  Sabre, Travelport, Amadeus │   │   IATA 17.2, 19.2, 21.3   │   │   Order Record)       │  │
 │    └──────────────┬──────────────┘   └─────────────┬─────────────┘   └───────────┬───────────┘  │
 └───────────────────┼────────────────────────────────┼─────────────────────────────┼──────────────┘
                     ▼                                ▼                             ▼
 ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 │                              PASSENGER SERVICE SYSTEMS (PSS) PLANE                              │
 │   ┌─────────────────────────────────────────────────────────────────────────────────────────┐   │
 │   │ Central Reservation System (CRS) & Inventory: PNR, Fare Classes, Availability, Reissue   │   │
 │   │ Departure Control System (DCS): Check-in, Boarding Passes, Baggage Tags, Gate Control  │   │
 │   │ Platforms: Amadeus Altéa, Navitaire New Skies, SabreSonic, IBS iFly                     │   │
 │   └────────────────────────────────────────┬────────────────────────────────────────────────┘   │
 └────────────────────────────────────────────┼────────────────────────────────────────────────────┘
                                              ▼
 ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 │                            OPERATIONAL & FLIGHT TRACKING PLANE                                  │
 │   ┌──────────────────────────────┐   ┌───────────────────────────┐   ┌───────────────────────┐  │
 │   │  IATA AIDX (RP 1797A)        │   │   Airport A-CDM & AODB    │   │  Commercial Feeds     │  │
 │   │ XML Flight Status, Delays,   │   │  Milestone Timestamps,    │   │ SITA, Cirium,         │  │
 │   │ Baggage & Aircraft Movement  │   │  Runway / Gate Allocation │   │ FlightAware, ADS-B    │  │
 │   └──────────────────────────────┘   └───────────────────────────┘   └───────────────────────┘  │
 └─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Deep Dive: Aviation Messaging Standards

### 2.1. IATA AIDX (Aviation Information Data Exchange — IATA RP 1797A / ACI)

#### Standard Overview
AIDX is the global standard XML message schema developed jointly by IATA and Airports Council International (ACI) to facilitate operational flight data exchange among airlines, airports, and third-party ground service providers.

* **Primary Purpose:** Publish and consume flight lifecycle events, scheduled vs. estimated vs. actual operational timestamps, delay reasons, aircraft registration, gate assignments, and baggage load metrics.
* **Message Formats:** XML payloads validated against standard W3C XSD schemas (`IATA_AIDX_FlightLegNotifRQ.xsd` and `IATA_AIDX_FlightLegNotifRS.xsd`).
* **Transport Protocols:** IBM MQ, WebSphere MQ, RabbitMQ, Kafka over TLS, SFTP batch drops, or SOAP/HTTPS Web Services.

#### Schema Elements & Structure
An AIDX payload is organized around the `FlightLeg` entity:
* **`LegIdentifier`:** Globally unique operational flight key:
  - `<Airline>`: 2-character IATA or 3-character ICAO carrier code (e.g., `AI` / `AIC`).
  - `<FlightNumber>`: Operational flight number (e.g., `839`).
  - `<DepartureAirport>`: 3-character IATA code (e.g., `DEL`).
  - `<ArrivalAirport>`: 3-character IATA code (e.g., `BOM`).
  - `<OriginDate>`: Scheduled departure date in UTC (`YYYY-MM-DD`).
* **`OperationalTimes`:** Fine-grained operational timestamps with explicit qualifiers (`Scheduled`, `Estimated`, `Target`, `Actual`):
  - `Off-Blocks` (Gate departure / pushback).
  - `Wheels-Up` (Airborne / Takeoff).
  - `Wheels-Down` (Touchdown).
  - `On-Blocks` (Gate arrival / chocks on).
* **`IrregularityDelay`:** Delay categorization element:
  - `<ReasonCode>`: Standard IATA numeric/alphabetic delay code (e.g., `71` for weather at departure, `89` for mechanical, `93` for aircraft rotation).
  - `<Duration>`: ISO-8601 duration (e.g., `PT1H45M`).
  - `<DepartureOrArrival>`: Indicator whether delay is departure or arrival-based.
* **`AircraftInfo` & `Baggage`:** Aircraft tail number, equipment type (e.g., `A321NEO`), total passenger souls on board (aggregated), and containerized/bulk baggage piece counts.

#### Critical Limitation for Flyo
**AIDX does NOT contain individual passenger records or PNRs.** It is strictly a flight-level and resource-level operational standard. Flyo uses AIDX for authoritative flight disruption triggers, but must correlate it with PSS/GDS data to identify affected passengers.

---

### 2.2. IATA NDC (New Distribution Capability — Resolution 787)

#### Standard Overview
NDC is an XML/JSON standard designed by IATA to modernize airline retailing. It enables airlines to distribute rich content, personalized offers, dynamic pricing, and direct booking management to travel sellers and aggregators without passing through traditional legacy GDS EDIFACT message switches.

* **Primary Purpose:** Direct commercial distribution: Offer shopping, dynamic pricing, ancillary upsell, order creation, payment collection, and post-booking servicing.
* **Major Schema Versions:** NDC 17.2, 19.2, and 21.3 (current enterprise standard). Modern implementations use RESTful JSON wrappers around the XML domain schema.
* **Key Transactions:**
  - `AirShoppingRQ / RS`: Search availability and pricing for origin-destination pairs.
  - `OfferPriceRQ / RS`: Lock in pricing and fare rules for selected flight offers.
  - `OrderCreateRQ / RS`: Create a binding commercial order and generate airline booking references.
  - `OrderReshopRQ / RS`: Query alternative flights, cancellation penalties, or rebooking fare deltas for an existing order.
  - `OrderChangeRQ / RS`: Commit modifications to an existing order (e.g., date changes, flight changes, ancillary adjustments).
  - `OrderCancelRQ / RS`: Cancel all or part of an existing reservation.

#### What NDC Does and Does NOT Provide
* **What NDC Provides:** Direct, programmatic flight shopping and automated ticket modification on participating airlines; access to airline-controlled seat inventory; ancillary servicing (meals, seats, bags); direct credit card / corporate billing authorization.
* **What NDC Does NOT Provide:** Real-time operational radar tracking, live flight positioning, gate pushback alerts, runway queue delays, or airport terminal congestion telemetry.

---

### 2.3. IATA ONE Order (Resolution 797)

#### Standard Overview
ONE Order is the ongoing structural evolution that replaces the fractured legacy trifecta of airline records—the **PNR** (reservation in PSS), the **e-Ticket** (electronic ticket coupon in the ticketing database), and the **EMD** (Electronic Miscellaneous Document for baggage and seats)—with a **single, unified customer Order record**.

* **Architectural Model:** Similar to modern e-commerce (e.g., Amazon Order ID). Every service (flights, seats, lounge access, taxi vouchers) is an `OrderItem` under one master order ID.
* **Current Industry Maturity & Reality Check:** While major carriers (Lufthansa Group, Singapore Airlines, Emirates, Air France-KLM) have live pilot routes under ONE Order, **the vast majority of airlines globally and in India (including Air India and IndiGo) continue to operate on legacy PNR + e-Ticket + EMD architectures**.
* **Implication for Flyo:** Flyo must not assume ONE Order adoption. The canonical Flyo data model must maintain separate fields for `pnr`, `ticketNumber`, `couponStatus`, and `emdReference` to maintain 100% backward compatibility with traditional PSS architectures.

---

### 2.4. Other Essential Aviation Standards

| Standard / Message | Purpose | Data Exchange Format | Flyo Operational Relevance |
| :--- | :--- | :--- | :--- |
| **SSIM (Standard Schedules Information Manual)** | Scheduled flight timetables distributed 6–12 months in advance (Chapter 7/8). | Fixed-width 200-character ASCII text or CSV files. | Baseline schedule ingestion. Required to establish original planned departure/arrival times. |
| **IATA MVT (Movement Messages)** | Legacy telex telegrams (Type B messaging via SITA network) for flight departures, arrivals, and delays. | Compact telegraphic text (e.g., `MVT AI839/06.VT-EXC.DEL DEP 1245...`). | Legacy OCC feed ingestion. If an airline’s OCC only exposes SITA Type B telegraphic feeds, Flyo parses MVT messages. |
| **A-CDM (Airport Collaborative Decision Making)** | Airport operational efficiency initiative linking Eurocontrol / DGCA, airports, and airlines. | XML / REST JSON message bus. Focuses on TOBT (Target Off-Block Time) and TSAT (Target Startup Approval Time). | High-precision airport delay forecasting. Predicts gate holds 30–60 minutes before pushback. |
| **IATA PNRGOV / PADIS** | Standard for transmitting passenger manifest data between airlines and government border security agencies. | EDIFACT / XML structured batch data. | Reference standard for complex PNR schemas and passenger demographic structures. |

---

## 3. Aviation Systems Architecture Taxonomy

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        AIRLINE ENTERPRISE SYSTEM ECOSYSTEM                             │
│                                                                                        │
│  ┌─────────────────────────┐  ┌─────────────────────────┐  ┌─────────────────────────┐ │
│  │   PASSENGER SERVICE     │  │   OPERATIONS CONTROL    │  │   DEPARTURE CONTROL     │ │
│  │      SYSTEM (PSS)       │  │      CENTRE (OCC)       │  │      SYSTEM (DCS)       │ │
│  │ - Amadeus Altéa         │  │ - Jeppesen / Boeing     │  │ - Amadeus Altéa DCS     │ │
│  │ - Navitaire New Skies   │  │ - Sabre Movement Mgr    │  │ - Navitaire Check-in    │ │
│  │ - SabreSonic            │  │ - SITA MissionWatch     │  │ - SabreSonic DCS        │ │
│  │ Stores: PNRs, Fares,    │  │ Stores: Aircraft tails, │  │ Stores: Boarding passes,│ │
│  │ Tickets, Inventories    │  │ Crew, ATC, Maintenance  │  │ Checked bags, Gate scan │ │
│  └────────────┬────────────┘  └────────────┬────────────┘  └────────────┬────────────┘ │
│               │                            │                            │              │
│               └──────────────────────┐     │     ┌──────────────────────┘              │
│                                      ▼     ▼     ▼                                     │
│                              ┌───────────────────────────────┐                         │
│                              │  FLYO INTEGRATION GATEWAY     │                         │
│                              │  (Normalizes to Canonical)    │                         │
│                              └───────────────────────────────┘                         │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 3.1. Passenger Service Systems (PSS)
The PSS is the central operational database and core ERP of an airline. It consists of:
1. **CRS (Central Reservation System):** Holds all commercial bookings, ticketing records, fare rules, and inventory allocations.
2. **Inventory Management:** Determines seat availability per booking class (e.g., `Y`, `B`, `M` for economy, `J`, `C` for business) using dynamic revenue management algorithms.
3. **DCS (Departure Control System):** Manages the physical flight at the airport from 24–48 hours before departure through takeoff (check-in, seat assignment, baggage acceptance, boarding pass issuance, weight & balance, and load manifest).

**Access Constraints:** Direct database access to a PSS is strictly forbidden by vendors (Amadeus, Navitaire, Sabre). Integrations must flow through certified SOAP/REST Digital APIs or EDIFACT MQ gateways requiring commercial sponsorship and IP allowlisting.

### 3.2. Global Distribution Systems (GDS)
GDS networks (Sabre, Travelport, Amadeus) serve as global aggregation switches connecting tens of thousands of travel agencies, corporate travel management companies (TMCs), and online travel platforms (OTAs) to hundreds of airline reservation databases.
* **Agency Pseudo City Code (PCC) / Office ID (OID):** Every agency accessing a GDS operates under a unique identifier. Bookings created under a specific PCC are owned by that agency; other agencies cannot modify the PNR without explicit security branch authorization.
* **GDS Ticketing Authority:** Agencies hold ticketing authority via ARC (Airlines Reporting Corporation in the US) or IATA BSP (Billing and Settlement Plan globally). Flyo can automate GDS commands only on behalf of an authorized agency owning the target PNR.
