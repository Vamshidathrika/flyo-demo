# Client System Capability Matrix: Integration Feasibility & Support Register

**Purpose:** Detailed matrix of enterprise aviation systems, supported integration protocols, read/write capabilities, latency profiles, and operational readiness status.

---

## 1. Airline Passenger Service Systems (PSS)

| System Vendor | Integration Protocols | Supported Operations | Read Latency | Write Latency | Flyo Adapter Status | Production Feasibility Notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Amadeus Altéa** | SOAP / XML, NDC 19.2/21.3, EDIFACT | Read PNR, Ticket Status, Seat Map, Rebook OrderCreate | 350ms - 800ms | 1,200ms - 3,500ms | **CONFIGURED_NOT_VERIFIED** | Requires Amadeus Web Services (1A WS) credentials and signed BTA / IATA ticketing agreement. |
| **SabreSonic (Sabre)** | REST APIs, Sabre Web Services (SWS SOAP), NDC | PNR Read/Update, Reissue, Ancillary Booking | 400ms - 900ms | 1,500ms - 4,000ms | **CONFIGURED_NOT_VERIFIED** | Requires EPR (Employee Profile Record) PCC configuration. |
| **Navitaire New Skies** | Navitaire dotREZ REST API, SOAP | Booking Retrieve, Modify Passenger, Pay with VCC | 250ms - 600ms | 900ms - 2,200ms | **SANDBOX_ONLY** | Low-cost carrier architecture; instant settlement with credit shells and single-use cards. |
| **Travelport+** | Travelport JSON REST API, XML Select | PNR Read, AirCreate, Exchange Ticket | 450ms - 1,100ms | 2,000ms - 4,500ms | **CONFIGURED_NOT_VERIFIED** | Used extensively by TMCs; supports multi-airline itinerary consolidation. |

---

## 2. Flight Operations & Real-Time Disruption Feeds (OCC / AODB)

| Source Provider | Data Standard | Feed Transport | Update Frequency | Reliability / SLA | Flyo Adapter Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Aviationstack** | REST / JSON | HTTPS Polling | 1-5 minutes | 99.0% SLA (Commercial aggregator) | **CONFIGURED_AND_VERIFIED** | Integrated with fallback logic in `AviationRadarClient`. |
| **OAG FlightView** | REST / Webhook | Webhook Push + Polling | Real-time (< 30s) | 99.9% Enterprise SLA | **CONFIGURED_NOT_VERIFIED** | Gold standard for global airline operational delays. |
| **Cirium FlightStats** | REST / Event Streams | Kafka / AWS SNS / Webhook | Real-time (< 15s) | 99.95% Enterprise SLA | **CONFIGURED_NOT_VERIFIED** | Full disruption event payload with gate changes, ATC holds, and diversions. |
| **Airport AODB (AIDX)** | IATA AIDX XML 14.1 | IBM MQ / JMS / HTTPS | Real-time push | Airport-dependent | **UNSUPPORTED** (Planned Stage 3) | Direct airport terminal database integration. |

---

## 3. Virtual Card & Treasury Settlement Providers

| Payment Provider | API Standard | Tokenization Scheme | Settlement Latency | Fraud / MCC Lock | Flyo Adapter Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Stripe Issuing** | REST / JSON | Ephemeral Card Tokens (Stripe.js / API) | Instant auth; batch capture | Enforced MCC 3000-3350, single-use spending limit | **CONFIGURED_NOT_VERIFIED** | Direct B2B API; requires PCI-DSS Attestation of Compliance. |
| **WEX Corporate Payments** | REST / SOAP | Secure VCC Token + Virtual PAN | Real-time auth | Travel MCC restricted, auto-close on settlement | **CONFIGURED_NOT_VERIFIED** | Industry standard for global TMC corporate travel funding. |
| **Flyo Sandbox Treasury** | Local Memory / DB | Tokenized SHA-256 Mock (`tok_sandbox_*`) | Sub-10ms | Simulated authorization and settlement | **CONFIGURED_AND_VERIFIED** | Used for local integration verification and unit testing. |

---

## 4. Passenger Communication Channels

| Channel / Provider | Protocol | Delivery Latency | Message Types Supported | Flyo Adapter Status |
| :--- | :--- | :--- | :--- | :--- |
| **Meta WhatsApp Cloud API** | REST / Webhook | 500ms - 2,000ms | HSM Templates, Interactive Buttons, List Messages | **CONFIGURED_NOT_VERIFIED** | Direct official Meta graph API integration. |
| **Twilio Messaging** | REST / Webhook | 800ms - 3,000ms | WhatsApp Business, SMS, Status Callbacks | **CONFIGURED_NOT_VERIFIED** | Fallback SMS provider when WhatsApp fails or is unavailable. |
| **Flyo Simulated WhatsApp** | In-Memory / DB | Instant (< 5ms) | Text, Reassurance, Confirmed Boarding Pass | **CONFIGURED_AND_VERIFIED** | Embedded interactive simulation console. |

---

## 5. Enterprise CRM & Ticketing Systems

| CRM System | Connection Protocol | Synchronized Entities | Bi-Directional Event Support | Flyo Adapter Status |
| :--- | :--- | :--- | :--- | :--- |
| **Salesforce Service Cloud** | REST Composite API | Disruption Case, Interaction Log, Approval Audit | Platform Events / CDC | **CONFIGURED_NOT_VERIFIED** |
| **Zendesk Support** | REST Core API | Support Ticket, Internal Private Note | Webhooks | **CONFIGURED_NOT_VERIFIED** |
| **HubSpot Service Hub** | REST API | CRM Ticket, Timeline Event | CRM Webhooks | **CONFIGURED_NOT_VERIFIED** |

---

## 6. Capability Registry Status Definition

- **`CONFIGURED_AND_VERIFIED`**: Adapter code implemented, verified via automated test suites against live or validated sandbox endpoints, actively processing traffic.
- **`CONFIGURED_NOT_VERIFIED`**: Full contract-compliant adapter written, configuration schema defined, awaiting client sandbox / production API credentials.
- **`SANDBOX_ONLY`**: Operational against provider mock/sandbox test environments; lacks signed production commercial contract.
- **`UNSUPPORTED`**: Provider protocol recognized by the canonical model but adapter implementation deferred to future development phases.
- **`DISABLED`**: Adapter disabled administratively via feature flags or tenant configuration.
