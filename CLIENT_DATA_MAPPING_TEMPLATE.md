# Flyo Forward Deployment Engineering (FDE): Client Data Mapping Template

**Purpose:** Comprehensive field-level specification and schema transformation workbook to map proprietary client systems (Amadeus, Sabre, Navitaire, Corporate PSS) into the Flyo Canonical Aviation Data Model.

---

## 1. Flight Status & Disruption Feed Mapping

| Client Source Field | Source Type / Format | Canonical Target Field | Canonical Type | Transformation Rule / Default Value |
| :--- | :--- | :--- | :--- | :--- |
| `FlightIdentification.FlightNumber` | String (e.g. "839") | `flightNumber` | String | If airline prefix missing, concatenate `AirlineCode` + `FlightNumber` $\rightarrow$ "AI-839" |
| `FlightStatus.Code` | String ("CX", "DV", "DL", "OT") | `disruptionType` | Enum (`DisruptionType`) | "CX" $\rightarrow$ `CANCELLED`; "DV" $\rightarrow$ `DIVERTED`; "DL" $\rightarrow$ `DELAYED`; else `ON_TIME` |
| `EstimatedDepartureDateTime` | ISO-8601 UTC / Local | `estimatedDeparture` | Instant | Parse timezone offset; convert to UTC instant |
| `ScheduledDepartureDateTime` | ISO-8601 UTC / Local | `scheduledDeparture` | Instant | Calculate delay minutes: `(estimated - scheduled) / 60` |
| `DepartureAirport.IataCode` | 3-letter IATA ("HYD") | `originAirportCode` | String | Validate uppercase 3-character IATA; lookup geographic metadata |
| `ArrivalAirport.IataCode` | 3-letter IATA ("DEL") | `destinationAirportCode`| String | Validate uppercase 3-character IATA |
| `DelayReason.Description` | Free text ("ATC HOLD WX") | `rawDisruptionReason` | String | Feed into Flyo Evidence Reconciliation Engine for corroboration |

---

## 2. Passenger Itinerary & PNR Mapping

| Client PSS Source Field | Source Example | Canonical Target Field | Canonical Type | Transformation Rule |
| :--- | :--- | :--- | :--- | :--- |
| `AirReservation.BookingReferenceID` | "X7Y8Z9" | `pnr` | String (6 chars) | Trim whitespace, uppercase |
| `Passenger.Surname` + `Firstname` | "SHARMA/ANANYA MS" | `passengerName` | String | Normalize title removal: "Ananya Sharma" |
| `Contact.MobilePhone` | "+91-9876543210" | `passengerPhone` | E.164 String | Normalize non-digits; prepend international country code: `+919876543210` |
| `Contact.EmailAddress` | "ananya@example.com" | `passengerEmail` | String | Lowercase, RFC-5322 validation |
| `TravelerLoyalty.Tier` | "PLATINUM", "GOLD" | `vipTier` | Enum (`VipTier`) | Maps to `TIER_1_VIP`, `TIER_2_PRIORITY`, or `STANDARD` |
| `FlightSegment.OnwardBooking` | "DEL-YYZ AC-043" | `missedConnection` | String | Detect connection viability via MCT engine; flag at-risk onward flights |
| `AncillaryServices.HotelBooking` | "ITC Maurya Delhi" | `hotelRisk` | String | Extract non-refundable hotel stays for automated voucher / insurance rescue |

---

## 3. Rescue Rebooking Option Mapping (NDC / GDS $\rightarrow$ Flyo)

| Client / GDS Offer Field | Source Example | Canonical Target Field | Canonical Type | Transformation Rule |
| :--- | :--- | :--- | :--- | :--- |
| `OfferItem.OfferItemID` | "OFFER-99214-01" | `optionId` | String | Prefix with case identifier: `caseId + "-O" + rank` |
| `FlightSegment.MarketingCarrier` | "UK" (Vistara) | `carrierCode` | String | 2-letter IATA carrier code |
| `FlightSegment.FlightNumber` | "811" | `flight` | String | Canonical carrier flight format: "UK-811" |
| `FlightSegment.DepartureTime` | "2026-11-06T23:30:00"| `departure` | String / Instant | Local format + UTC timestamp |
| `FlightSegment.ArrivalTime` | "2026-11-07T01:40:00"| `arrival` | String / Instant | Local format + UTC timestamp |
| `Pricing.TotalFare.Amount` | 4200.00 | `extraCost` | Integer / BigDecimal | Currency converted to base client currency; calculate net delta |
| `SeatAvailability.RemainingSeats`| 4 | `seatsRemaining` | Integer | Revalidate with airline inventory host prior to booking execution |
| `PreservesConnectionIndicator` | `true` | `savesConnection` | Boolean | Computed by Flyo Minimum Connecting Time (MCT) graph |

---

## 4. Virtual Credit Card & Payment Settlement Mapping

| Client Payment System Field | Source Example | Canonical Target Field | Canonical Type | Security & PCI Policy |
| :--- | :--- | :--- | :--- | :--- |
| `VCC.TransactionToken` | `tok_1Nh7J82eZvKYlo2C` | `cardToken` | String | **Mandatory:** Flyo never stores raw PAN or CVV. Store token only. |
| `VCC.CardLast4` | "7819" | `last4` | String | Masked display only |
| `VCC.ExpiryMonthYear` | "11/28" | `expiry` | String | Retained for authorized gateway verification |
| `VCC.AuthorizedAmount` | 4200.00 | `amount` | BigDecimal / Int | Enforce strict transaction maximum limit; reject overages |
| `VCC.MerchantCategoryCode` | "3001" | `mccLock` | String | Restrict card usage strictly to airline / accommodation MCCs |
| `Settlement.ProviderTxnId` | `TXN-99824` | `settlementTxnId` | String | Correlate with Flyo `payment_settlement` ledger |

---

## 5. Webhook & External Notification Ingestion Contract

```json
{
  "eventId": "evt_client_9876543210",
  "eventType": "FLIGHT_STATUS_UPDATE",
  "timestamp": "2026-11-06T12:00:00Z",
  "tenantId": "tenant_corporate_tmc_01",
  "payload": {
    "flightNumber": "AI-839",
    "departureDate": "2026-11-06",
    "origin": "HYD",
    "destination": "DEL",
    "statusCode": "CANCELLED",
    "reason": "Air Traffic Control Slot Delays due to Weather",
    "impactedBookings": [
      {
        "bookingReference": "HYD123",
        "pnr": "HYD123",
        "passengerName": "Ananya Sharma",
        "passengerPhone": "+919876543210"
      }
    ]
  },
  "signature": "sha256=4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"
}
```
