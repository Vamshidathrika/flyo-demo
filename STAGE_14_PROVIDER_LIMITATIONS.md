# Stage 14 — External Provider Limitations, Sandbox Disclosures & Production Boundaries

## Executive Summary & Regulatory Transparency
In strict accordance with IATA GDS regulations, airline distribution policies, and financial compliance standards, Flyo Rescue explicitly distinguishes between **sandbox/simulation testing environments** and **commercial live airline production readiness**.

This document transparently defines:
1. What capabilities are operational in the current system.
2. What capabilities are running in simulation/sandbox mode.
3. The exact regulatory prerequisites required before live ticketing can be unblocked.

---

## Provider Capability Matrix

| Provider Domain | Adapter Implementation | Current Runtime Mode | Real Network Call | Commercial Ticketing Readiness |
| :--- | :--- | :--- | :--- | :--- |
| **GDS Flight Search** | `AmadeusGdsClient` | Live Sandbox API | Yes (`test.api.amadeus.com`) | Operational for availability & pricing |
| **Airline Booking / Ticketing** | `AmadeusOrderCreateClient` | Mock / Test Sandbox | Sandbox Simulation | **BLOCKED** pending IATA NDC Level 4 Accreditation |
| **Aviation Radar / Flight Tracking** | `AviationRadarClient` | Live Aviationstack API / Mock | Conditional (live if key present, else fail-closed) | Operational (fail-closed if unconfigured) |
| **Meteorological Intelligence** | `OpenMeteoWeatherService` | Live Open-Meteo API | Yes (`api.open-meteo.com`) | Operational (live weather with baseline fallback) |
| **Aviation News Intelligence** | `AviationNewsIntelligenceService` | Live RSS Feed | Yes (`simpleflying.com/feed`) | Operational (live operational RSS headlines) |
| **Virtual Card Issuance (VCC)** | `StripeVccAdapter` | Stripe Test API / Sandbox | Yes (Stripe Testmode) | Operational for test VCC issuance |
| **WhatsApp Ingress / Egress** | `MetaWhatsAppClient` | Meta Cloud API / Mock | Conditional (live if token present, else logged) | Operational for sandbox testing |
| **Airline Headless Web Booking** | `PlaywrightBrowserWorker` | Local Mock Carrier Portal | Local HTTP (`localhost:8080/mock-airline`) | Simulation only (zero real tickets created) |

---

## Detailed Provider Boundaries

### 1. Airline Booking & Live Ticketing (IATA NDC / Amadeus Enterprise)
- **Current Capability**:
  - The system executes end-to-end automated rebooking against the local mock airline reservation portal (`MockAirlineController`) via Playwright, or against Amadeus Sandbox Flight Offers Search.
  - The booking flow exercises virtual card generation, seat selection, PNR generation, and e-ticket simulation.
- **Production Boundary**:
  - Commercial live issuance of airline electronic tickets (IATA 13-digit e-tickets) is **strictly blocked**.
  - **Prerequisites for Live Ticketing**:
    1. IATA / ARC Travel Agency Accreditation.
    2. Valid IATA BSP (Billing and Settlement Plan) cash guarantee.
    3. Bilateral airline ticketing authority agreements (Interline agreements).
    4. Amadeus Enterprise Production Credentials (IATA Office ID, Production LSS/WSAP access).

### 2. Disruption Radar & Weather Intelligence
- **Current Capability**:
  - `AviationRadarClient` queries Aviationstack API for real-time flight statuses (DEL, HYD, BOM, BLR).
  - If the API key is not supplied or returns an error, the platform **fails closed** (`UNCONFIGURED_FAIL_CLOSED`), returning empty telemetry rather than faking live radar.
  - `OpenMeteoWeatherService` queries real-time hourly meteorological data (visibility, wind speed, precipitation, WMO code) for Indian airports.

### 3. Virtual Card Payments & PCI-DSS Boundaries
- **Current Capability**:
  - Virtual Card issuance runs through `SandboxPaymentAdapter` or Stripe Test API.
  - The platform implements tokenized VCC references (`VCC-xxxxxxxx`), never storing raw 16-digit primary account numbers (PAN) in database tables.
- **Production Boundary**:
  - Commercial issuing requires a live corporate commercial card agreement (Stripe Issuing Production, Brex, or Marqeta) and PCI-DSS Level 1 compliance certification.

### 4. Passenger WhatsApp Notifications
- **Current Capability**:
  - In development and test environments, `MetaWhatsAppClient` operates in logged/mock mode, recording interactive messages to the audit trail and in-memory delivery queues.
  - When configured with `flyo.whatsapp.access-token`, it routes messages to Meta Cloud API.
- **Mandatory User Disclosure**:
  - Every outbound simulated message explicitly includes the disclaimer:
    ```
    *[SIMULATION / SANDBOX MODE]*
    Simulation completed. No real airline booking or ticket was created.
    Ticket Status: SIMULATION ONLY — NOT A VALID AIRLINE TICKET
    ```
