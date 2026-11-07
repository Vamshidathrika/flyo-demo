# Flyo Forward Deployment Engineering (FDE) & Client Integration Strategy

**Document Status:** Approved Architecture & Field Strategy  
**Role Perspective:** Principal Forward Deployment Engineer (FDE), Enterprise Integration Architect & Aviation Operations Strategist  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## Executive Summary

The initial prototype of Flyo was designed and demonstrated as a standalone disruption rescue dashboard. It generated its own simulated flight schedules, parsed pasted e-ticket emails via regular expressions, generated hardcoded rebooking alternatives, executed UI automation through Playwright on a mock web portal, and evaluated disruptions using machine learning models trained on United States Bureau of Transportation Statistics (BTS) domestic data.

While this demonstration successfully proved the **operational value proposition**—specifically, proactive disruption identification, Minimum Connection Time (MCT) rule enforcement, and passenger WhatsApp conversational re-accommodation—it cannot be deployed into a real airline, travel management company (TMC), online travel agency (OTA), or airport environment in its current state.

A commercial airline or global travel agency will **never** replace their core Passenger Service System (PSS), Global Distribution System (GDS), Operations Control Centre (OCC), or Customer Relationship Management (CRM) infrastructure with a third-party startup dashboard. Furthermore, modern aviation systems of record do not permit unauthorized direct database mutations or uncertified bot-driven web scraping of their internal agent portals.

**The Strategic Shift:** Flyo must pivot from a *standalone application* to an **integration-first aviation rescue platform**. Flyo operates as an intelligent sensing, evaluation, and orchestration layer wrapped around the client’s existing, authorized systems of record. 

```
                               CLIENT ENTERPRISE BOUNDARY
 ┌─────────────────────────────────────────────────────────────────────────────────┐
 │                                                                                 │
 │   OCC / AODB              PSS / DCS                GDS / NDC          CRM / OMS │
 │  (Cirium / SITA)     (Altéa / New Skies)       (Sabre / Travelport)  (Salesforce)
 │        │                       │                        │                 │     │
 │        ▼                       ▼                        ▼                 ▼     │
 │  ┌───────────────────────────────────────────────────────────────────────────┐  │
 │  │                 FLYO CUSTOMER INTEGRATION GATEWAY (FIG)                   │  │
 │  │     - mTLS Ingress/Egress             - Token & Credential Vault          │  │
 │  │     - Protocol & Schema Normalization - Outbound-Only Cloud Sync          │  │
 │  │     - PII & PCI Tokenization / Masking - Local Event Queue & Circuit Break│  │
 │  └───────────────────────────────────────────────────────────────────────────┘  │
 └────────────────────────────────────────┬────────────────────────────────────────┘
                                          │ Outbound Secure mTLS / gRPC
                                          ▼
 ┌─────────────────────────────────────────────────────────────────────────────────┐
 │                         FLYO CORE CLOUD INTELLIGENCE                            │
 │                                                                                 │
 │  ┌───────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐  │
 │  │  Canonical Data Model │  │  Multi-Source Engine │  │  Region-Specific ML  │  │
 │  │ (Flights, PAX, Cases) │  │(Radar, Weather, NOTAM│  │ Disruption Predictor │  │
 │  └───────────┬───────────┘  └──────────┬───────────┘  └──────────┬───────────┘  │
 │              │                         │                         │              │
 │              └───────────────────┬─────┴─────────────────────────┘              │
 │                                  ▼                                              │
 │                     ┌─────────────────────────┐                                 │
 │                     │ Disruption Case Manager │                                 │
 │                     │ & State Machine (V1-V3) │                                 │
 │                     └────────────┬────────────┘                                 │
 │                                  │                                              │
 │              ┌───────────────────┴───────────────────┐                          │
 │              ▼                                       ▼                          │
 │  ┌───────────────────────┐               ┌───────────────────────┐              │
 │  │ Operator Console RBAC │               │ WhatsApp & Omni-Channel│             │
 │  │ Human Approval Gate   │               │ Passenger OTP Workflow│              │
 │  └───────────┬───────────┘               └───────────┬───────────┘              │
 │              │                                       │                          │
 └──────────────┼───────────────────────────────────────┼──────────────────────────┘
                │ Verified Execution Orders             │ Verified Passenger Choice
                ▼                                       ▼
 ┌─────────────────────────────────────────────────────────────────────────────────┐
 │                   AUTHORIZED CLIENT WRITE-BACK / SERVICING                      │
 │    - GDS PNR Reshop & Exchange (Sabre / Travelport)                             │
 │    - PSS Digital API Order Reissue (Navitaire / Altéa)                          │
 │    - Direct Bank Corporate Virtual Card Settlement (Tokenized PCI Proxy)         │
 └─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 1. The Five Non-Negotiable FDE Principles

As a Forward Deployment Engineer entering an enterprise customer's IT ecosystem, you operate under five strict tenets:

### Principle 1: Integrate, Don't Replace
Flyo does not store the master copy of a passenger's ticket or the definitive schedule of an airline fleet. The client’s PSS (e.g., Amadeus Altéa, Navitaire New Skies) or GDS (e.g., Sabre, Travelport) is the sole system of record for passenger itineraries and ticketing status; their OCC/AODB is the sole authority for flight movements. Flyo ingests read-only event telemetry, enriches it with multi-source risk intelligence, computes optimal rescue alternatives, obtains required operator and passenger consents, and dispatches standardized servicing orders back into the customer's native booking engines.

### Principle 2: Standardized Adapter Architecture
To scale across multiple clients without maintaining separate code forks, Flyo enforces a decoupled adapter design. A single canonical domain model (`Flight`, `PassengerJourney`, `DisruptionEvent`, `RescueOption`, `ApprovalOrder`) powers Flyo's core state machine and ranking logic. Vendor-specific communication is isolated within pluggable adapters (`AmadeusAltéaAdapter`, `NavitaireNewSkiesAdapter`, `SabreGdsAdapter`, `CiriumAidxAdapter`). Adding a new customer never alters Flyo's business rules; it only requires configuring an adapter implementing our standard interface.

### Principle 3: Strict Separation of Three Data Tiers
A common architectural failure in travel startups is conflating flight tracking with passenger reservations. Flyo strictly segregates three distinct data planes:
1. **Flight Operational Telemetry (Public / B2B Feeds):** Flight status, radar vectors, runway arrival times, weather conditions, NOTAM bulletins, and ATC ground-stops.
2. **Passenger Itineraries & Booking Records (Strict PII):** Passenger names, frequent flyer numbers, contact details, onward connecting legs, special assistance requests (SSRs), and baggage tags.
3. **Inventory, Ticketing & Settlement (Financial & PCI):** Fare classes, seat availability, ticket exchange eligibility, penalty waivers, payment transactions, and Virtual Commercial Card (VCC) settlement.

Each tier demands distinct access controls, refresh cycles, retention schedules, and encryption keys.

### Principle 4: Deploy Inside the Client's Security Boundary
Airlines and financial-grade travel organizations operate under strict data residency mandates (e.g., India's Digital Personal Data Protection Act 2023 / 2025 Rules, GDPR, ISO 27001). Flyo provides the **Flyo Customer Integration Gateway (FIG)**—a containerized, zero-trust gateway deployed within the customer's VPC, on-premises DMZ, or private cloud. The gateway accesses internal PSS/OCC endpoints locally, sanitizes/tokenizes PII and PCI data, and communicates with the central Flyo Cloud solely via outbound-initiated, mutual TLS (mTLS) encrypted sessions.

### Principle 5: Real Data with Certified UAT (Zero Mock Fallback in Production)
Flyo will never substitute synthetic mock data or unverified scrapers in an enterprise production environment. When an integration adapter lacks live production credentials or certification, the platform must fail closed, marking the provider status as `BLOCKED_BY_EXTERNAL_ACCESS` and escalating to manual human-in-the-loop servicing. Every client deployment follows a structured progression from synthetic test harness to certified sandbox, passive shadow operation on live data, and finally gated production write access.

---

## 2. Platform Value Delivery Framework

When an FDE pitches and deploys Flyo into an airline or travel agency, value is measured strictly through operational efficiency and cost avoidance:

| Value Vector | Traditional Manual Workflow | Flyo Automated Rescue Workflow | Measurable KPI Impact |
| :--- | :--- | :--- | :--- |
| **Disruption Detection** | 45–90 min post-ATC alert (reactive upon gate queue surge) | 3–15 min prior to public notice (multi-source radar + weather) | 60+ min lead time advantage |
| **Connection Risk Evaluation** | Manual agent inspection of PNR connection buffers | Instant automated evaluation against airport MCT matrix (e.g. DEL T3 D-I 90m vs Flyo 120m) | Zero illegal connections booked |
| **Passenger Outreach** | Static airport PA announcements or generic SMS delays | Targeted WhatsApp conversational bot with authenticated OTP | 85%+ digital self-service adoption |
| **Rebooking & Re-ticketing** | 12–25 minutes per passenger at airport service desks | Automated pre-ranked options, 1-click operator approval, automated PSS/GDS reissuance | 80% reduction in desk queue times |
| **Regulatory Compliance (DGCA CAR)** | Frequent non-compliance fines regarding refreshments and hotel care | Automated triggers for meal vouchers and hotel booking tracking | Elimination of regulatory non-compliance penalties |

---

## 3. Deployment Topology Overview

Flyo supports three operational deployment topologies depending on client size and compliance constraints:

1. **Topology A: Hybrid Gateway (Recommended for Tier-1 Airlines & Large TMCs)**  
   - Flyo Gateway runs in client VPC (AWS/Azure/GCP or on-premises OpenShift).
   - Ingests internal PSS, DCS, and OCC feeds directly.
   - PII/PCI tokenized before streaming to Flyo Intelligence Cloud.
   - Operators use Flyo SaaS or embedded CRM iframe.
2. **Topology B: Secure Direct SaaS (Recommended for Digital OTAs & Mid-Market Agencies)**  
   - Flyo Core Cloud connects directly to client's internet-facing GDS/NDC/REST endpoints via IP allowlisting, OAuth2, and mutual TLS.
   - Zero client infrastructure footprint.
3. **Topology C: Fully Isolated Air-Gapped / Private Cloud (Sovereign Carriers / Defense)**  
   - Entire Flyo stack (Backend, DB, ML Inference, Frontend) deployed within the client's sovereign dedicated infrastructure.
