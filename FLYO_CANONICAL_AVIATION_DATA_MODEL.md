# Flyo Canonical Aviation Data Model (CADM)

**Document Status:** Enterprise Data Standard Specification  
**Author:** Principal Enterprise Integration Architect & AI Systems Specialist  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Architectural Philosophy & Need for a Canonical Contract

Enterprise integrations fail when core domain logic directly binds to proprietary vendor payloads. If Flyo's state machine directly processed an Amadeus `PNR_Reply` XML document, adding Navitaire or Sabre would require rewriting all case handling, ranking algorithms, and UI logic.

The **Flyo Canonical Aviation Data Model (CADM)** provides a unified, vendor-agnostic abstraction layer. Pluggable adapters translate external vendor formats (EDIFACT, XML, REST/JSON, AIDX, NDC) into CADM upon ingestion, and serialize CADM servicing commands back into vendor-specific APIs upon execution.

```
 ┌──────────────────────┐   ┌──────────────────────┐   ┌──────────────────────┐
 │ Amadeus Altéa / GDS  │   │ Navitaire New Skies  │   │   Sabre GDS / Sonic  │
 └──────────┬───────────┘   └──────────┬───────────┘   └──────────┬───────────┘
            │                          │                          │
            ▼                          ▼                          ▼
 ┌────────────────────────────────────────────────────────────────────────────┐
 │                     VENDOR PROTOCOL ADAPTER LAYER                          │
 └─────────────────────────────────────┬──────────────────────────────────────┘
                                       │ Normalizes to
                                       ▼
 ┌────────────────────────────────────────────────────────────────────────────┐
 │                   FLYO CANONICAL AVIATION DATA MODEL                       │
 │                                                                            │
 │  [Organization/Tenant] ─── [PassengerJourney] ─── [BookingReference/PNR]  │
 │           │                         │                         │            │
 │           ▼                         ▼                         ▼            │
 │     [FlightLeg] ──────────── [DisruptionEvent] ─────── [TicketCoupon]      │
 │           │                         │                                      │
 │           ▼                         ▼                                      │
 │     [FlightStatus] ─────── [ReaccommodationOffer] ─── [ApprovalOrder]      │
 └────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Globally Unique Identity Resolution Rules

A cardinal sin in travel software engineering is treating a flight number (e.g., `AI-839`) or a PNR code (e.g., `W4X9K2`) as globally unique identifiers:

1. **Flight Number Ambiguity:** Flight `AI-839` operates every day. Across a calendar year, 365 distinct flights share the identifier `AI-839`. Furthermore, codeshare agreements mean the same physical aircraft operates simultaneously as `AI-839`, `UA-8102`, and `SQ-4091`.
2. **PNR Code Collisions:** A 6-character alphanumeric PNR (e.g., `ABC123`) is only unique within a specific airline or GDS partition. Amadeus, Sabre, and Navitaire independently generate identical PNR strings over time.
3. **Flight Leg Segments:** A single commercial flight number (e.g., `AI-101`) can have multiple legs (e.g., `DEL -> LHR` continuing to `LHR -> EWR`). Each leg has distinct runways, pilots, delay statuses, and passenger manifests.

### The Canonical Unique Key Formulas

```
Canonical Flight Leg ID = SHA256(
    operatingCarrierCode + ":" + 
    flightNumber + ":" + 
    departureAirportIata + ":" + 
    arrivalAirportIata + ":" + 
    scheduledDepartureTimeUtc (ISO-8601 Date)
)
Example: SHA256("AI:839:DEL:BOM:2026-11-06") -> "FLG-8f92b7c4d1e2"

Canonical PNR ID = SHA256(
    tenantId + ":" + 
    sourceSystemCode + ":" + 
    pnrLocator + ":" + 
    creationDateUtc
)
Example: SHA256("TENANT-AIRINDIA:AMADEUS_ALTEA:W4X9K2:2026-10-15") -> "PNR-4a11c08e59b3"
```

---

## 3. Core Entity Specifications

### 3.1. Organization & Tenant (`CADM_Tenant`)
Defines the enterprise client operating Flyo:
* `tenantId`: Globally unique UUID string (`TENANT-AIRINDIA-IND`, `TENANT-AMEX-GBT`).
* `organizationType`: Enum (`AIRLINE`, `TMC`, `OTA`, `AIRPORT_OPERATOR`).
* `primaryRegulatoryRegion`: Enum (`INDIA_DGCA`, `EU_EC261`, `US_DOT`).
* `dataResidencyZone`: Enum (`IN_CENTRAL_MUMBAI`, `EU_FRANKFURT`, `US_EAST`).
* `complianceProfiles`: Array of active policies (`DPDP_ACT_2023`, `PCI_DSS_SAQ_D`).

### 3.2. Flight & FlightLeg (`CADM_FlightLeg`)
Represents an individual operational segment:
* `flightLegId`: Canonical unique hash.
* `operatingCarrier`: 2-letter IATA code (`AI`) and 3-letter ICAO code (`AIC`).
* `marketingCarriers`: Array of codeshare identifiers with flight numbers.
* `flightNumber`: String (`839`).
* `originAirport`: 3-letter IATA airport code (`DEL`).
* `destinationAirport`: 3-letter IATA airport code (`BOM`).
* `scheduledTimes`: Departure and arrival UTC timestamps (`2026-11-06T14:30:00Z`).
* `estimatedTimes`: Dynamic updated UTC timestamps based on radar and ATC.
* `actualTimes`: Actual off-block, wheels-up, touchdown, and on-block timestamps.
* `aircraftInfo`: Tail registration (`VT-EXC`), equipment code (`A321NEO`).

### 3.3. FlightStatus & DisruptionEvent (`CADM_DisruptionEvent`)
Captures operational disruption telemetry:
* `eventId`: Unique event UUID.
* `flightLegId`: Foreign key to `CADM_FlightLeg`.
* `sourceSystem`: System generating the report (`AIDX`, `CIRIUM_FEED`, `AIRLINE_OCC`).
* `reportedStatus`: Enum (`ON_TIME`, `DELAYED`, `CANCELLED`, `DIVERTED`, `GATE_RETURN`).
* `delayMinutes`: Integer duration.
* `iataDelayCode`: Standard IATA code (`71` Weather, `89` Mechanical, `93` Late Inbound).
* `confidenceScore`: Integer (0–100%) computed by Flyo multi-source reconciler.
* `observedAtUtc`: ISO timestamp of observation.
* `rawSourcePayload`: Preserved raw JSON/XML message for audit provenance.

### 3.4. PassengerJourney & PNR (`CADM_PassengerJourney`)
Represents the customer reservation and ticketed itinerary:
* `journeyId`: Canonical unique UUID.
* `tenantId`: Client identifier.
* `pnrLocator`: Vendor record locator (`W4X9K2`).
* `sourceSystem`: System of record (`AMADEUS_ALTEA`, `SABRE_GDS`, `NAVITAIRE`).
* `passenger`:
  - `passengerId`: Anonymized/Tokenized traveler UUID.
  - `fullName`: Structured (`lastName`, `firstName`, `salutation`).
  - `contactChannel`: Encrypted phone (E.164) and email.
  - `frequentFlyer`: Tier and number.
* `segments`: Ordered list of `CADM_JourneySegment`:
  - `segmentIndex`: 1, 2, 3...
  - `flightLegId`: Link to `CADM_FlightLeg`.
  - `originAirport`, `destinationAirport`.
  - `cabinClass`: (`ECONOMY`, `PREMIUM_ECONOMY`, `BUSINESS`, `FIRST`).
  - `fareBasis`: Fare code governing rebooking rules (`Y01`, `KIP`).
  - `ticketNumber`: 13-digit e-ticket string (`098-1234567890`).
  - `couponStatus`: (`OPEN`, `CHECKED_IN`, `BOARDED`, `EXCHANGED`, `REFUNDED`).
* `connections`: Evaluated transit points:
  - `transitAirport`: (`DEL`).
  - `scheduledBufferMinutes`: Integer.
  - `publishedMctMinutes`: Airport official MCT for transfer type.
  - `flyoPolicyMctMinutes`: Flyo safety threshold (e.g. 120 min for int'l).
  - `connectionRiskLevel`: (`SAFE`, `TIGHT_LEGAL`, `MISSED_CONNECTION_CONFIRMED`).

### 3.5. ReaccommodationOffer (`CADM_ReaccommodationOffer`)
Candidate rescue flights evaluated and ranked for the passenger:
* `offerId`: Unique UUID.
* `journeyId`: Target journey.
* `recommendedOptionRank`: 1, 2, 3.
* `flightRouteDescription`: (`HYD -> BOM -> DEL`).
* `connectingAirport`: Hub transit code.
* `projectedArrivalUtc`: Estimated final arrival.
* `connectionBufferMinutes`: Buffer to onward international leg.
* `savesConnection`: Boolean indicator.
* `extraCostMinorUnits`: Additional fare/tax amount (0 if protected under involuntary IROP rules).
* `currency`: Currency code (`INR`, `USD`).
* `complianceEntitlements`: Array of mandated welfare benefits under DGCA CAR / EC261:
  - `MEALS_AND_REFRESHMENTS`
  - `HOTEL_ACCOMMODATION`
  - `GROUND_TRANSFER`
  - `FULL_REFUND_WAIVER`

### 3.6. ApprovalOrder & ExecutionReceipt (`CADM_ApprovalOrder`)
Audit-locked command record for authorized write-back:
* `orderId`: Unique execution command UUID.
* `tenantId`: Client identifier.
* `journeyId`: Target passenger journey.
* `selectedOfferId`: Approved reaccommodation offer.
* `approvalMechanism`: Enum (`OPERATOR_EXPLICIT_CONSOLE`, `PASSENGER_VERIFIED_OTP`).
* `approvedBy`: Operator user ID or Passenger phone hash.
* `approvedAtUtc`: Timestamp.
* `executionStatus`: Enum (`PENDING`, `DISPATCHED`, `CONFIRMED`, `FAILED`, `ROLLED_BACK`).
* `authoritativeBookingReference`: Confirmed PNR returned by PSS/GDS.
* `authoritativeTicketNumber`: New 13-digit e-ticket issued by airline CRS.

---

## 4. Canonical CADM JSON Schema Example

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "FlyoCanonicalPassengerJourney",
  "type": "object",
  "required": [
    "canonicalJourneyId",
    "tenantId",
    "sourceSystem",
    "pnrLocator",
    "passenger",
    "segments"
  ],
  "properties": {
    "canonicalJourneyId": { "type": "string", "pattern": "^JRN-[a-f0-9]{12}$" },
    "tenantId": { "type": "string" },
    "sourceSystem": { 
      "type": "string", 
      "enum": ["AMADEUS_ALTEA", "NAVITAIRE_NEWSKIES", "SABRE_GDS", "TRAVELPORT"] 
    },
    "pnrLocator": { "type": "string", "minLength": 5, "maxLength": 8 },
    "passenger": {
      "type": "object",
      "required": ["passengerToken", "lastName", "firstName"],
      "properties": {
        "passengerToken": { "type": "string" },
        "lastName": { "type": "string" },
        "firstName": { "type": "string" },
        "phoneE164Encrypted": { "type": "string" }
      }
    },
    "segments": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["segmentIndex", "flightNumber", "carrierCode", "origin", "destination"],
        "properties": {
          "segmentIndex": { "type": "integer" },
          "carrierCode": { "type": "string", "pattern": "^[A-Z0-9]{2}$" },
          "flightNumber": { "type": "string" },
          "origin": { "type": "string", "pattern": "^[A-Z]{3}$" },
          "destination": { "type": "string", "pattern": "^[A-Z]{3}$" },
          "scheduledDepartureUtc": { "type": "string", "format": "date-time" },
          "ticketNumber": { "type": "string" },
          "couponStatus": { "type": "string" }
        }
      }
    }
  }
}
```
