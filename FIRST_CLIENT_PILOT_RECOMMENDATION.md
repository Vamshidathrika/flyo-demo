# First Client Pilot Selection & Minimum Viable Integration (MVI)

**Document Status:** Strategic Commercial & Technical Recommendation  
**Author:** Principal Forward Deployment Engineer & Technical Product Strategist  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Customer Segment Comparative Analysis

To select the most realistic and high-impact first deployment target for Flyo, we evaluate the four primary candidate customer archetypes across eight operational and technical criteria:

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                CUSTOMER ARCHETYPE EVALUATION MATRIX                              │
├────────────────────────────┬──────────────┬──────────────┬──────────────┬────────────────────────┤
│ Selection Dimension        │ Segment 1:   │ Segment 2:   │ Segment 3:   │ Segment 4:             │
│                            │ Airline Flag │ Corporate TMC│ Online Travel│ Airport / Ground       │
│                            │ Carrier      │ / Agency     │ Agency (OTA) │ Handling Operator      │
├────────────────────────────┼──────────────┼──────────────┼──────────────┼────────────────────────┤
│ 1. Ease of Securing Auth.  │ Very Low     │ High         │ Moderate     │ Moderate               │
│    Real Operational Data   │ (Vendor Red  │ (Agency owns │ (Aggregator  │ (AODB access readily   │
│                            │ Tape & CAB)  │ PNR directly)│ contracts)   │ available; no PNRs)    │
├────────────────────────────┼──────────────┼──────────────┼──────────────┼────────────────────────┤
│ 2. Available Direct APIs   │ Complex SOAP │ Standard GDS │ Modern REST  │ AIDX / SITA MQ         │
│                            │ / Digital API│ REST / Web   │ / NDC APIs   │ (Zero booking APIs)    │
├────────────────────────────┼──────────────┼──────────────┼──────────────┼────────────────────────┤
│ 3. Passenger Itinerary     │ Yes (Full    │ Yes (Agency  │ Yes (Internal│ STRICTLY NO            │
│    (PNR) Retrieval Rights  │ PSS Access)  │ Office PNRs) │ DB + GDS)    │ (Zero PNR visibility)  │
├────────────────────────────┼──────────────┼──────────────┼──────────────┼────────────────────────┤
│ 4. Alternative Flight      │ Single Carrier│ All GDS     │ All GDS +    │ None                   │
│    Inventory Access        │ or Alliances │ Carriers     │ LCC Direct   │ (No ticketing rights)  │
├────────────────────────────┼──────────────┼──────────────┼──────────────┼────────────────────────┤
│ 5. Programmatic Rebooking  │ High Vendor  │ Standard GDS │ Standard API │ STRICTLY NO            │
│    Execution Rights        │ Barrier (DCS)│ Exchange Priv│ Exchange Priv│ (No rebooking rights)  │
├────────────────────────────┼──────────────┼──────────────┼──────────────┼────────────────────────┤
│ 6. Integration Timeline    │ 9–14 Months  │ 2–3 Months   │ 4–6 Months   │ 3–5 Months             │
├────────────────────────────┼──────────────┼──────────────┼──────────────┼────────────────────────┤
│ 7. Information Security    │ Severe (OCC/ │ Moderate     │ Moderate     │ High (Critical Infra)  │
│    & Compliance Friction   │ Critical Net)│ (Corporate)  │ (PCI-DSS)    │                        │
├────────────────────────────┼──────────────┼──────────────┼──────────────┼────────────────────────┤
│ 8. Measurable Financial ROI│ High         │ Immediate    │ High         │ Low                    │
│    from Disruption Rescue  │ (Crew/Fleet) │ (SLA / Duty  │ (Support Call│ (Resource allocation   │
│                            │              │ of Care)     │ Deflection)  │ optimization only)     │
└────────────────────────────┴──────────────┴──────────────┴──────────────┴────────────────────────┘
```

---

## 2. Definitive First Client Recommendation

### The Winner: Segment 2 — High-Volume Corporate Travel Management Company (TMC) / Corporate Travel Agency

> [!IMPORTANT]
> **Why the Corporate TMC is the Optimal First Deployment Target:**
> 1. **Immediate PNR Ownership:** A corporate travel agency (e.g., FCM Travel India, Thomas Cook India Corporate, or a boutique enterprise travel desk) **already owns the PNRs** created under their Pseudo City Code (PCC). They do not require months of airline vendor committee approvals to read and service their own clients' reservations.
> 2. **Multi-Carrier Protection:** When an Air India flight is cancelled, an airline's own desk is often restricted to rebooking on their own flights or limited alliance partners. A corporate TMC has the commercial authority and contractual mandate to rebook their VIP business travelers across **any competing airline** (e.g., shifting an executive from cancelled AI-839 onto IndiGo 6E-402) to guarantee arrival.
> 3. **High Willingness to Pay:** Corporate travel accounts pay high servicing retainers. Missing an international board meeting or overseas flight connection causes massive financial damage. TMCs actively seek automated disruption rescue tools to provide 24/7 proactive Duty of Care.
> 4. **Rapid Deployment Time:** Flyo can deploy into an authorized agency GDS environment within **60 to 90 days**, compared to 12+ months for an enterprise airline PSS.

---

## 3. Minimum Viable Integration (MVI) Specification for First Client

For the initial pilot deployment with an enterprise Corporate TMC, Flyo implements the following scoped architecture:

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                          FIRST CLIENT (TMC) MVI ARCHITECTURE                           │
 │                                                                                        │
 │  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────────────┐  │
 │  │ Commercial Flight    │  │ Agency GDS Connector │  │ Passenger Outreach Channel   │  │
 │  │ Telemetry Feed       │  │ (Sabre / Travelport) │  │ (Meta WhatsApp Cloud API)    │  │
 │  │ ─ Cirium FlightStats │  │ ─ Agency PCC / OID   │  │ ─ Verified Business Template │  │
 │  │ ─ Open-Meteo Weather │  │ ─ Read: GetReservation│ ─ Interactive Button Choices  │  │
 │  │ ─ Real-time Delays   │  │ ─ Write: EnhancedBook│  │ ─ 6-Digit OTP Challenge      │  │
 │  └──────────┬───────────┘  └──────────┬───────────┘  └──────────────┬───────────────┘  │
 │             │                         │                             │                  │
 │             └───────────────────┐     │     ┌───────────────────────┘                  │
 │                                 ▼     ▼     ▼                                          │
 │                     ┌───────────────────────────────┐                                  │
 │                     │    FLYO INTELLIGENCE CORE     │                                  │
 │                     │ ─ Disruption Case Manager     │                                  │
 │                     │ ─ MCT Rules (DEL/BOM Hubs)    │                                  │
 │                     │ ─ Ranking Engine (Buffer Safe)│                                  │
 │                     │ ─ Operator Console (1-Click)  │                                  │
 │                     └───────────────┬───────────────┘                                  │
 │                                     │                                                  │
 │                                     ▼                                                  │
 │                     ┌───────────────────────────────┐                                  │
 │                     │ Corporate Settlement Proxy    │                                  │
 │                     │ ─ Tokenized Corporate VCC     │                                  │
 │                     │ ─ Zero Plaintext Card in DB   │                                  │
 │                     └───────────────────────────────┘                                  │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

### 3.1. Systems Connected in MVI
1. **Agency GDS API (Sabre REST / Travelport+):**
   - **Read:** Scheduled PNR queue polling. Automatically ingests corporate traveler itineraries departing within the next 48 hours.
   - **Write:** Dispatches authorized `AutomatedExchange` or `EnhancedAirBook` transactions to modify flight segments upon operator approval.
2. **Cirium Flight Status Alert Feed:** Ingests live operational flight alerts, gate changes, delay minutes, and cancellation notices for all monitored flights.
3. **Open-Meteo Airport Weather Service:** Live destination airport weather, visibility meters, and CAT III fog advisory tracking.
4. **Meta WhatsApp Cloud API:** Real-time conversational rescue interaction with stranded travelers.
5. **Corporate Treasury VCC Proxy:** Automated issuance of spend-capped, merchant-locked virtual cards for rebooking settlement.

### 3.2. Agent Capabilities Activated vs. Disabled in MVI

| Capability | MVI Status | Operational Control |
| :--- | :--- | :--- |
| **Proactive Disruption Detection** | **ENABLED** | Multi-source radar + weather correlation |
| **MCT Safety & Ranking Engine** | **ENABLED** | 120-min international buffer policy enforced |
| **Operator Console Approval Gate** | **ENABLED** | Mandatory human approval required for every action |
| **Passenger WhatsApp OTP Verification**| **ENABLED** | Mandatory passenger biometric/OTP confirmation |
| **GDS Automated Segment Exchange** | **ENABLED** | Authorized agency PNR modification |
| **US BTS ML Disruption Predictor** | **DISABLED** | Geofenced off; replaced by live radar/weather |
| **Autonomous Unattended Rebooking** | **DISABLED** | Strictly locked; human operator must approve |
| **Playwright Web Browser Automation** | **DISABLED** | Uncertified fallback; disabled in production |
| **Plaintext CVV Database Storage** | **PERMANENTLY BANNED** | Replaced with tokenized PCI proxy |
