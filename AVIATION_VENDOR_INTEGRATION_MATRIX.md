# Aviation Vendor Integration & Capability Matrix

**Document Status:** Enterprise Vendor Capability Audit  
**Author:** Principal Enterprise Integration Architect  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Research Date:** November 2026  

---

## 1. Vendor Capability Matrix Overview

This audit evaluates the leading global aviation technology vendors across eleven functional capabilities required for Flyo's automated disruption rescue operations. 

> [!CRITICAL]
> **Production Access Warning:** Public developer portals and "Self-Service" sandboxes (such as Amadeus for Developers) provide lightweight, simulated or carrier-restricted data. They **cannot** be used as enterprise connectors for an airline's internal fleet or a corporate agency's real client PNRs. Enterprise production access requires commercial contracts, developer certifications, and network IP allowlisting.

```
┌────────────────────┬──────────────┬──────────────┬──────────────┬──────────────┬──────────────┐
│ Capability         │ Amadeus      │ Sabre        │ Navitaire    │ Cirium       │ SITA         │
│                    │ Altéa / GDS  │ GDS / Sonic  │ New Skies    │ Aviation     │ Developer    │
├────────────────────┼──────────────┼──────────────┼──────────────┼──────────────┼──────────────┤
│ Live Flight Status │ Partial      │ Partial      │ No           │ Full (Global)│ Full (AODB)  │
│ Passenger PNR Data │ Full (Auth)  │ Full (Auth)  │ Full (Auth)  │ No           │ No           │
│ Real-Time Avail.   │ Full (GDS)   │ Full (GDS)   │ Full (Direct)│ Partial (Hist│ No           │
│ Programmatic Rebook│ Full (GDS)   │ Full (GDS)   │ Full (Direct)│ No           │ No           │
│ Push Webhooks      │ Queue / Event│ Event Notif. │ Webhooks/Poll│ Event Streams│ MQ / Webhooks│
│ Network Whitelist  │ Required     │ Required     │ Strict Reqd. │ Optional     │ Strict Reqd. │
└────────────────────┴──────────────┴──────────────┴──────────────┴──────────────┴──────────────┘
```

---

## 2. In-Depth Vendor Technical Audit

### 2.1. Amadeus IT Group

* **Vendor Profile:** Amadeus operates both the world’s largest GDS network and the Altéa Passenger Service System (PSS) used by full-service network carriers (e.g., Air India, Lufthansa, Air France, Singapore Airlines, British Airways).
* **Developer Portals:** 
  - Enterprise Web Services: [Amadeus Service Hub](https://servicehub.amadeus.com)
  - Self-Service API Portal: [Amadeus for Developers](https://developers.amadeus.com)

| Technical Dimension | Enterprise Altéa / GDS Platform | Amadeus Self-Service APIs |
| :--- | :--- | :--- |
| **API Availability** | SOAP XML 18.2+ & RESTful Digital Connect | REST / JSON |
| **Authentication** | WS-Security (UsernameToken / BinarySecurityToken) + Office ID (OID) session tokens | OAuth 2.0 (Client Credentials Grant) |
| **Live Flight Status** | `Air_FlightStatus` (Limited to schedules/operational reports) | `GET /v2/schedule/flights` (Limited carrier coverage) |
| **Passenger PNR Access** | `PNR_Retrieve`, `PNR_Reply` (Full passenger record) | Not permitted. Test sandbox uses synthetic PNRs |
| **Live Seat Availability**| `Air_MultiAvailability`, `Fare_MasterPricerTravelBoardSearch` | `GET /v2/shopping/flight-offers` (Limited low-cost carriers) |
| **Rebooking & Servicing**| `Air_SellFromRecommendation`, `Ticket_ProcessETicket`, `DocIssuance_IssueTicket` | Booking creation in sandbox only; no enterprise re-issuance |
| **Webhooks / Event Push**| Amadeus Event Engine (Kafka / IBM MQ); PNR Queue Placement | Not supported (Polling only) |
| **Batch Integration** | SFTP EDIFACT / CSV PNR dump; Daily BSP ticketing feed | Not available |
| **Production Access** | Commercial contract, IATA/ARC certification, VPN / IP allowlist | Credit card self-service; strictly rate-limited |
| **Pricing Model** | Enterprise fee per booking segment (EUR €0.30–€1.80) + setup fee | Freemium tier + €0.002–€0.015 per call over quota |
| **Official Documentation**| `https://developers.amadeus.com/self-service` | `https://developers.amadeus.com/enterprise` |

---

### 2.2. Sabre Corporation

* **Vendor Profile:** Sabre provides the Sabre GDS network (dominant in North America and corporate travel) and the SabreSonic PSS suite.
* **Developer Portal:** [Sabre Dev Studio](https://developer.sabre.com)

| Technical Dimension | Sabre Enterprise GDS & SabreSonic |
| :--- | :--- |
| **API Availability** | Sabre REST APIs (JSON) & Sabre Orchestrated Web Services (SOAP) |
| **Authentication** | OAuth 2.0 Token Authentication + Pseudo City Code (PCC) Session Token |
| **Live Flight Status** | `GetFlightStatus` REST API / `FlightDetailsRQ` SOAP |
| **Passenger PNR Access** | `GetReservationRQ` (REST / JSON) or `TravelItineraryReadRQ` (SOAP). Retrieves complete PNR history, segments, and ticketing coupons. |
| **Live Seat Availability**| `BargainFinderMaxRQ` (500-itinerary low fare search engine) |
| **Rebooking & Servicing**| `EnhancedAirBookRQ`, `AutomatedExchangeRQ`, `AirTicketRQ`. Full automated ticket reissue with penalty calculation. |
| **Webhooks / Event Push**| Sabre Event Notification Services (ENS) via AWS SNS, SQS, or HTTPS webhooks (triggers on PNR create/modify/cancel). |
| **Batch Integration** | Daily TCN (Ticket Control Number) files via SFTP; Sabre GDS Queue sweeps. |
| **Production Access** | Valid Sabre PCC / IATA number, signed Subscriber Agreement, IP allowlisting. |
| **Pricing Model** | Contractual GDS transaction fee or GDS incentive kickback; API monthly license. |
| **Official Documentation**| `https://developer.sabre.com/docs/rest_apis` |

---

### 2.3. Navitaire (An Amadeus Company)

* **Vendor Profile:** Navitaire New Skies is the leading PSS for Low-Cost Carriers (LCCs) and hybrid carriers globally, powering airlines such as IndiGo (India’s largest domestic carrier), Ryanair, Wizz Air, and Spirit Airlines.
* **Architecture:** Unlike full-service carriers operating on legacy EDIFACT, Navitaire was engineered as an e-commerce, ticketless reservation system.
* **Developer Reference:** [TUI Developer Portal — Navitaire New Skies Digital API](https://developer.tui.com)

| Technical Dimension | Navitaire New Skies Digital REST API |
| :--- | :--- |
| **API Availability** | RESTful JSON APIs (`/rest/api/nsk/...`) |
| **Authentication** | **Two-Tier Authentication:**<br>1. *Application Key:* `apikey` header issued via developer portal.<br>2. *Agent Session Token:* `POST /rest/api/nsk/v1/token` with agent username, password, and organizational domain (`EXT`). Returns Bearer token. |
| **Live Flight Status** | Not an operational tracker. Queries flight schedule and check-in status only (`/rest/api/nsk/v1/flight`). |
| **Passenger PNR Access** | `GET /rest/api/nsk/v1/booking/{recordLocator}`. Retrieves complete passenger manifest, booking contact, payments, and segment history. |
| **Live Seat Availability**| `POST /rest/api/nsk/v1/booking/availability`. Real-time seat inventory per fare family. |
| **Rebooking & Servicing**| `POST /rest/api/nsk/v1/booking/passengers`, `PUT /rest/api/nsk/v1/booking/passengers/itinerary`. Direct in-place re-accommodation. |
| **Webhooks / Event Push**| Navitaire Message Queue (MQ) integration or configured webhook callbacks for booking state transitions. |
| **Batch Integration** | Nightly Skyledger financial files and manifest exports via SFTP. |
| **Production Access** | Strict vendor sponsorship by carrier. Pre-approved outbound server IP allowlisting. User certification mandatory. |
| **Pricing Model** | Enterprise PSS enterprise agreement (bundled with per-passenger boarded fee). |
| **Official Documentation**| `https://developer.tui.com/apis/navitaire` |

---

### 2.4. Cirium (LexisNexis Risk Solutions)

* **Vendor Profile:** Cirium is the world standard commercial provider of flight status, airline schedules, aircraft fleet data, and global aviation analytics.
* **Developer Portal:** [Cirium Developer Studio](https://developer.cirium.com)

| Technical Dimension | Cirium Core & Flex APIs |
| :--- | :--- |
| **API Availability** | REST / JSON & Streaming Event Hub (WebSocket / Kafka) |
| **Authentication** | `appId` + `appKey` headers or OAuth 2.0 Bearer tokens |
| **Live Flight Status** | **Market Leader:** `Flight Status & Track API`. Real-time gate pushback, runway takeoff, in-flight coordinates, arrival estimates, delay codes, and cancellations across 99% of global commercial flights. |
| **Passenger PNR Access** | **Strictly Prohibited / Unavailable.** Cirium has zero access to individual passenger reservations. |
| **Live Seat Availability**| Historic schedules and published capacity via `Schedules API`; no live seat availability. |
| **Rebooking & Servicing**| Not applicable. Operational data only. |
| **Webhooks / Event Push**| Cirium Flight Status Alert Streams (Push webhooks or Kafka queue emitting JSON alerts within 30 seconds of an airline status change). |
| **Batch Integration** | Daily SSIM flight schedule downloads via AWS S3 / SFTP. |
| **Production Access** | Direct commercial contract. Sandbox evaluation keys available upon sales qualification. |
| **Pricing Model** | Enterprise annual license ($12,000–$120,000+/year based on flight volume and query frequency). |
| **Official Documentation**| `https://developer.cirium.com/apis/flight-status` |

---

### 2.5. SITA (Société Internationale de Télécommunications Aéronautiques)

* **Vendor Profile:** Aviation communications backbone owned by the airline industry, operating SITA Type B messaging, Airport Operational Databases (AODB), and airport connectivity.
* **Developer Portal:** [SITA Developer Hub](https://developer.aero)

| Technical Dimension | SITA Aviation Intelligence & AODB APIs |
| :--- | :--- |
| **API Availability** | RESTful JSON, AIDX XML Web Services, Type B Messaging |
| **Authentication** | API Key (`X-apiKey`) or mutual TLS with X.509 client certificates |
| **Live Flight Status** | `SITA Airport Operations API` & `AIDX FlightLegNotif`. Authoritative gate, terminal, and runway timestamps direct from airport AODBs. |
| **Passenger PNR Access** | SITA Horizon PSS supports PNRs; standard SITA flight feeds do not. |
| **Live Seat Availability**| Not available via operational APIs. |
| **Rebooking & Servicing**| Limited to Horizon PSS customers. |
| **Webhooks / Event Push**| Real-time JMS / AMQP message queues over SITA ATI Cloud. |
| **Batch Integration** | Automated Type B telex distribution and AIDX XML batch exchanges. |
| **Production Access** | Airline/Airport operational sponsorship; closed industry network. |
| **Pricing Model** | Commercial B2B contract based on port speed and message count. |
| **Official Documentation**| `https://developer.aero/apis` |

---

### 2.6. OpenSky Network (Audit of Current Flyo Consideration)

* **Vendor Profile:** Academic and non-profit crowdsourced ADS-B receiver network based in Switzerland.
* **Developer Portal:** [OpenSky Network API](https://opensky-network.org)

| Technical Dimension | OpenSky Network Live API |
| :--- | :--- |
| **API Availability** | REST / JSON |
| **Authentication** | Basic Auth (Username / Password) |
| **Flight Status** | **Raw State Vectors Only** (latitude, longitude, barometric altitude, velocity, squawk code, callsign). Does **not** provide commercial flight schedules, published delay minutes, cancellation notices, gate assignments, or passenger impact. |
| **Commercial Terms** | **Strict Legal Prohibition:** OpenSky Terms of Use explicitly forbid commercial, operational, or business usage without a signed commercial licensing agreement. Free access is restricted to academic research. |
| **Recommendation for Flyo** | **DO NOT USE FOR PRODUCTION FLYO.** Use Cirium, FlightAware, or direct airline AIDX feeds for commercial disruption intelligence. |

---

## 3. Summary of API Verification Status for Flyo

```
┌─────────────────────────────────┬───────────────────┬───────────────────────────────────────────┐
│ Provider & Service              │ Verification Level│ Flyo Implementation Status                │
├─────────────────────────────────┼───────────────────┼───────────────────────────────────────────┤
│ Amadeus Self-Service API        │ VERIFIED PUBLIC   │ Existing (Restricted, sandbox only)       │
│ Amadeus Altéa Enterprise        │ PARTNER REQUIRED  │ Incomplete (Stubbed in AmadeusNdcAdapter)  │
│ Sabre GDS Web Services          │ PARTNER REQUIRED  │ Incomplete (Stubbed in SabreGdsAdapter)   │
│ Navitaire New Skies Digital API │ PARTNER REQUIRED  │ Specification Verified; Adapter Needed    │
│ Cirium Flight Status Stream     │ COMMERCIAL QUOTE  │ Recommended as primary flight feed       │
│ Open-Meteo Weather API          │ VERIFIED PUBLIC   │ Implemented & Verified in OpenMeteoService│
│ Meta WhatsApp Cloud API         │ VERIFIED ENTERPRISE│ Implemented & Verified in MetaCloudAdapter│
│ Stripe Issuing (VCC)            │ VERIFIED COMMERCE │ Implemented in StripeIssuingPaymentAdapter│
└─────────────────────────────────┴───────────────────┴───────────────────────────────────────────┘
```
