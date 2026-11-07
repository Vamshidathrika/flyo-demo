# Flyo Forward Deployment Engineering (FDE): Client Integration Discovery Template

**Purpose:** Standard operating procedure for Forward Deployment Engineers during technical pre-sales, onboarding, and architecture evaluation at target airline, Travel Management Company (TMC), and Online Travel Agency (OTA) customer environments.

---

## 1. Client Environment Classification

| Field | Assessment / Client Specification |
| :--- | :--- |
| **Organization Name** | [e.g., Global Express Corporate Travel / FlySky Airlines] |
| **Customer Category** | [ ] Tier-1 Carrier &nbsp;&nbsp; [ ] Regional Carrier &nbsp;&nbsp; [X] Corporate TMC &nbsp;&nbsp; [ ] OTA |
| **Primary System of Record** | [ ] Amadeus Altéa &nbsp;&nbsp; [ ] SabreSonic &nbsp;&nbsp; [ ] Navitaire &nbsp;&nbsp; [ ] Travelport &nbsp;&nbsp; [ ] Custom Internal PSS |
| **Deployment Archetype** | [ ] Flyo Dedicated VPC &nbsp;&nbsp; [ ] Client On-Premises Kubernetes &nbsp;&nbsp; [X] Multi-Tenant SaaS with Client Gateway |
| **Target Service Level Agreement** | 99.95% Availability; < 120s automated end-to-end disruption rescue cycle |

---

## 2. Technical System Discovery Questionnaire

### 2.1 Flight Operations & Disruption Feeds (OCC / AODB)
- **Protocol:** [ ] AIDX (IATA XML) &nbsp;&nbsp; [ ] SSIM / SSM &nbsp;&nbsp; [ ] REST / JSON Webhook &nbsp;&nbsp; [ ] Kafka / MQ Stream &nbsp;&nbsp; [ ] Polling REST API
- **Feed Frequency:** Real-time push vs Polling interval (seconds)
- **Latency from Event to Feed:** Average minutes between operational delay/cancellation announcement and feed emission
- **Authentication:** mTLS / OAuth2 Bearer Token / API Key / VPN IP Whitelist
- **Historical Reliability:** Outage frequencies, message drop rates, sequence tracking mechanism

### 2.2 Passenger Reservation & PNR Systems (PSS / GDS)
- **Primary PSS / GDS:** Amadeus (1A), Sabre (1S), Travelport (1G), or Direct NDC
- **Read Access:**
  - PNR Retrieval endpoint: REST / SOAP / EDIFACT
  - Query parameters supported: PNR Record Locator, Ticket Number, Flight + Date + Passenger Surname
  - Return payload includes: Ancillaries, SSRs (special service requests), Fare Basis, Ticket Coupons, Loyalty Tier
- **Write / Modification Access:**
  - Can Flyo create split PNRs for disrupted passengers in multi-party bookings?
  - Does the client authorize Flyo to issue exchange tickets or re-validate coupons?
  - What PSS ticketing command authority does Flyo's B2B agent profile hold?

### 2.3 Distribution & Rebooking Channels (NDC / Aggregators)
- **NDC Schema Version:** [ ] IATA NDC 17.2 &nbsp;&nbsp; [ ] 19.2 &nbsp;&nbsp; [ ] 21.3 &nbsp;&nbsp; [ ] GDS Aggregator (Amadeus NDC-X, Sabre NDC)
- **Offer & Order Management:**
  - Does client support `OrderCreate` and `OrderChange` with instant ticket issuance?
  - Are corporate negotiated discount codes supported via NDC `Party/Sender` headers?
- **Seat Availability Lookups:**
  - Real-time seat inventory latency (sub-second vs 2-4 seconds)
  - Rate limits per minute on availability search queries

### 2.4 Payment & Treasury Settlement (VCC / BSP)
- **Virtual Credit Card Issuer:** WEX / Conferma / Brex / Stripe Issuing / AirPlus
- **Funding Currency:** Multi-currency support (USD, EUR, GBP, INR)
- **Pre-authorization Requirements:** Dynamic single-use card generation with exact merchant code restriction (MCC 3000-3350 for Airlines, 3500-3999 for Hotels)
- **Settlement & Reconciliation:** Webhook settlement notifications vs periodic batch file (CAMT.053 / MT940 / CSV)

### 2.5 Passenger Communications & CRM
- **WhatsApp Provider:** Meta Cloud API / Gupshup / Infobip / Twilio
- **Message Templates Approved:** Pre-approved WhatsApp HSM templates for Disruption Alert, Itinerary Options, Payment Request, Ticket Delivery
- **Two-Way Messaging:** Webhook endpoint for passenger replies ("REBOOK 1", "CALL AGENT")
- **CRM Sync:** Salesforce Service Cloud / Zendesk / HubSpot via bidirectional REST synchronization

---

## 3. Data Governance, Security & Boundary Controls

- **PCI-DSS Scope:** Does Flyo process Primary Account Numbers (PAN)? Standard architecture: Flyo operates as PCI Level 4 / SAQ A-EP using tokenized virtual cards; raw PAN and CVV never stored.
- **Personally Identifiable Information (PII):** Passenger name, phone, email, passport data tokenized and masked in logs.
- **Data Residency Requirements:** EU GDPR / India DPDP / US jurisdiction requirements.
- **Audit Logging:** Immutable transition audit trail retained for 7 years for airline and financial compliance.

---

## 4. FDE Discovery Review & Next Step Sign-Off

- **Lead FDE:** ___________________________ &nbsp;&nbsp;&nbsp;&nbsp; **Date:** _______________
- **Client Enterprise Architect:** ___________ &nbsp;&nbsp;&nbsp;&nbsp; **Date:** _______________
- **Go/No-Go Decision for Integration Gateway Deployment:** [ ] APPROVED &nbsp;&nbsp; [ ] BLOCKED
