# Stage 2: Comprehensive Field Mapping Reference

**Author:** Enterprise Integration Architect  
**Date:** November 6, 2026  
**System:** Flyo Autonomous Disruption Recovery Engine  

---

## 1. Amadeus Altéa PSS to Canonical Flyo Field Mapping

| Amadeus XML / EDIFACT Element | Amadeus Path | Canonical Flyo Field | Transformation Rule |
| :--- | :--- | :--- | :--- |
| `pnrHeader.reservationInfo.reservation.controlNumber` | `PnrReply/pnrHeader/reservationInfo` | `RescueCase.bookingId` / `pnr` | 6-character uppercase alphanumeric string. |
| `travellerInfo.passengerData.travellerInformation.passenger.firstName` | `PnrReply/travellerInfo` | `RescueCase.passengerName` | Concatenate First + Surname, strip salutations ("MR", "MS"). |
| `originDestinationDetails.itineraryInfo.travelProduct.productDetails.identification` | `PnrReply/originDestinationDetails` | `RescueCase.flight` | Standardize as `<Carrier>-<FlightNumber>` (e.g. `AI-839`). |
| `originDestinationDetails.itineraryInfo.travelProduct.boardpointDetail.cityCode` | `PnrReply/originDestinationDetails` | `originAirport` | 3-letter IATA code ("HYD"). |
| `originDestinationDetails.itineraryInfo.travelProduct.offpointDetail.cityCode` | `PnrReply/originDestinationDetails` | `destinationAirport` | 3-letter IATA code ("DEL"). |
| `ticketElement.ticket.documentDetails.number` | `PnrReply/dataElementsMaster` | `eTicketNumber` | Format as `13-digit` e-ticket string (e.g. `098-2451928410`). |
| `couponDetails.couponStatus` | `PnrReply/ticketElement` | `couponStatus` | `O` $\rightarrow$ `OPEN`, `C` $\rightarrow$ `CHECKED_IN`, `F` $\rightarrow$ `FLOWN`, `E` $\rightarrow$ `EXCHANGED`, `V` $\rightarrow$ `VOID`. |

---

## 2. Sabre GDS to Canonical Flyo Field Mapping

| Sabre SWS JSON Element | Sabre JSON Path | Canonical Flyo Field | Transformation Rule |
| :--- | :--- | :--- | :--- |
| `GetReservationRS.Reservation.BookingDetails.RecordLocator` | `$.Reservation.BookingDetails.RecordLocator` | `pnr` | Uppercase 6-character string. |
| `PassengerDetails.Passenger[0].GivenName` + `Surname` | `$.PassengerDetails.Passenger[*]` | `passengerName` | Formatted passenger display name. |
| `FlightSegment.MarketingAirlineCode` + `FlightNumber` | `$.FlightSegment[*]` | `flight` | Formatted as `AA-100` or `DL-240`. |
| `FlightSegment.DepartureDateTime` | `$.FlightSegment[*].DepartureDateTime` | `scheduledDeparture` | ISO-8601 UTC timestamp. |
| `FlightSegment.Status` | `$.FlightSegment[*].Status` | `disruptionType` | `HX` / `UN` $\rightarrow$ `CANCELLED`; `TK` $\rightarrow$ `SCHEDULE_CHANGED`. |

---

## 3. Stripe Issuing & Payment Gateway to Canonical Flyo Field Mapping

| Stripe Issuing API Field | Stripe Path | Canonical Flyo Field | Transformation Rule |
| :--- | :--- | :--- | :--- |
| `card.id` | `$.id` | `VirtualCard.cardTokenId` | Opaque token (`ic_...` / `tok_...`). |
| `card.last4` | `$.last4` | `VirtualCard.lastFour` | 4-digit numeric string. |
| `card.exp_month` + `card.exp_year` | `$.exp_month`, `$.exp_year` | `VirtualCard.expiry` | Format as `MM/YY` (e.g. `11/28`). |
| `card.spending_controls.spending_limits[0].amount` | `$.spending_controls` | `VirtualCard.amount` | Converted from cents/paise to standard currency unit. |
| `card.spending_controls.allowed_categories` | `$.spending_controls` | `VirtualCard.merchantCategory` | Locked to `airlines_air_carriers` (MCC 3000-3350). |
| `authorization.id` | `$.authorization.id` | `PaymentTransaction.id` | Unique financial transaction identifier. |
| `authorization.status` | `$.authorization.status` | `PaymentTransaction.status` | `closed` $\rightarrow$ `SETTLED`; `reversed` $\rightarrow$ `VOIDED`. |
