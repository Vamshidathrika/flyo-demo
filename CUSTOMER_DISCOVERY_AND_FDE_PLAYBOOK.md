# Forward Deployment Engineering (FDE) Customer Discovery & Field Playbook

**Document Status:** Enterprise Standard Operating Procedure (SOP)  
**Author:** Principal Forward Deployment Engineer & Head of Technical Services  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. The Forward Deployment Engineering Philosophy

In enterprise software, an engineering team builds generic features; a **Forward Deployment Engineer (FDE)** makes those features work inside a customer's real, messy, legacy-encumbered production environment. 

A Flyo FDE does not ask the customer to adapt to Flyo. The FDE enters the customer’s Operations Control Centre (OCC), understands their exact shift handovers, maps their legacy PSS/GDS terminals, navigates their security and firewall approvals, connects Flyo's adapters around their existing software of record, and proves measurable operational savings.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                             7-STAGE FDE CUSTOMER ONBOARDING LIFECYCLE                            │
│                                                                                                  │
│  [STAGE A] ──> [STAGE B] ──> [STAGE C] ──> [STAGE D] ──> [STAGE E] ──> [STAGE F] ──> [STAGE G]   │
│  Discovery     System        Access &      Data          Shadow        Controlled    Production  │
│  & Teams       Mapping       Security      Contracts     Operations    Pilot         Scale       │
│  (Days 1-7)    (Days 8-14)   (Days 15-28)  (Days 29-42)  (Days 43-70)  (Days 71-98)  (Day 99+)   │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Stage-by-Stage Field Playbook

### Stage A: Discovery & Operational Team Alignment (Days 1–7)

#### Objective
Understand the client’s real disruption handling process, pain points, shift handovers, and executive priorities before discussing software.

#### Stakeholder Discovery Matrix
The FDE must conduct structured discovery sessions with five key operational leaders:
1. **Head of Operations Control Centre (OCC / Dispatch):** Understand flight tracking feeds, slot management, ATC ground delay programs, and how cancellations are decided.
2. **Director of Airport Operations & Customer Service:** Understand airport desk crowding, gate agent workflows, meal voucher distribution, and hotel booking bottlenecks.
3. **VP / Head of Airline IT & PSS Systems:** Identify core PSS (Altéa, Navitaire, Sabre), existing API licenses, vendor contract constraints, and cloud infrastructure policies.
4. **Chief Information Security Officer (CISO) & Legal/DPO:** Review data residency, PII/PCI security controls, DPDP/GDPR compliance, and vendor risk assessments.
5. **Head of Commercial / Revenue Management:** Understand involuntary rebooking policies (IROP rules), interline agreements, and fare waiver authorization.

#### FDE Discovery Questionnaire
* *What is your current average time between an ATC disruption decision and passenger notification?*
* *How do airport gate agents currently handle missed connections during evening hub banks?*
* *What percentage of disrupted passengers are rebooked automatically vs. manually at desks?*
* *Do you currently possess programmatic API write access to your PSS, or do agents use terminal emulators (green screens)?*

---

### Stage B: System & Network Topology Mapping (Days 8–14)

#### Objective
Produce a complete technical inventory of all systems that Flyo must read from or write to.

#### Deliverable Checklist
1. **System Inventory:** Exact software names, versions, hosting locations (on-prem vs cloud), and vendor SLAs for PSS, DCS, OCC, AODB, and CRM.
2. **API & Interface Inventory:** Catalog all available integration protocols (SOAP, REST, MQ, Type B telex, SFTP batches).
3. **Data Flow & Firewall Diagram:** Map internal network routes, corporate proxies, NAT gateways, and IP allowlist policies.
4. **Identity & Authorization Matrix:** Catalog service accounts, agent login domains, OAuth providers, and required RBAC privileges.

---

### Stage C: Access, Security & Readiness Certification (Days 15–28)

#### Objective
Secure certified technical access to customer test sandboxes and complete all legal and compliance prerequisites.

#### Field Execution Steps
1. **Execute Bilateral Data Processing Agreement (DPA):** Establish Flyo’s status as a Data Processor under India’s DPDP Act 2023 or relevant privacy statutes.
2. **Vendor Approval & Commercial Sponsorship:** If accessing Amadeus Altéa or Navitaire New Skies, coordinate with the airline to submit the official vendor application form for third-party developer access.
3. **Deploy Flyo Customer Integration Gateway (FIG) in Test VPC:** Deploy FIG via Helm chart into the customer’s staging Kubernetes cluster. Verify outbound-only mTLS connectivity to Flyo Cloud.
4. **Provision Test Service Accounts:** Obtain dedicated test credentials with read-only scopes initially. Verify that no production PII is exposed in the test sandbox.

---

### Stage D: Canonical Data Contract Mapping (Days 29–42)

#### Objective
Map the client's proprietary payloads into Flyo’s Canonical Aviation Data Model (CADM).

#### Field Engineering Checklist
* **Flight Identifiers:** Verify operating carrier vs marketing carrier mappings.
* **Timestamp Normalization:** Validate that all flight times are correctly converted from local station time to UTC ISO-8601 strings.
* **PNR Segment Extraction:** Map multi-passenger and multi-segment bookings into CADM `PassengerJourney` structures. Ensure ticket coupon status (`OPEN`, `CHECKED_IN`) is correctly extracted.
* **Data Quality Audit:** Benchmark data freshness. If the client’s PSS feed exhibits a 15-minute polling latency, implement webhook push or reduce polling intervals.

---

### Stage E: Read-Only Shadow Operation (Days 43–70)

#### Objective
Connect Flyo to live customer production data in **read-only mode**. Evaluate Flyo’s disruption detection and ranking accuracy against real-world human decisions **with zero write access**.

#### Shadow Evaluation Criteria
```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        SHADOW EVALUATION AUDIT METRICS                                 │
├─────────────────────────┬───────────────────────────┬──────────────────────────────────┤
│ Metric                  │ Target Benchmark          │ Measured Real-World Variance     │
├─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ Disruption Detection    │ Detected >= 30 min before │ Track lead time vs airline public│
│ Lead Time               │ airport gate announcement │ SMS announcement                 │
├─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ False Positive Rate     │ < 2% of monitored flights │ Alert generated but flight       │
│                         │                           │ departed on time                 │
├─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ Missed Disruption Rate  │ 0% of flight cancellations│ Flight cancelled without Flyo    │
│                         │                           │ alert generated                  │
├─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ Reaccommodation Rank 1  │ >= 85% match with human   │ Flyo Rank 1 option matches agent │
│ Concordance             │ dispatcher choice         │ manual rebooking choice          │
├─────────────────────────┼───────────────────────────┼──────────────────────────────────┤
│ MCT Rule Violations     │ STRICT ZERO (0%)          │ Flyo never recommends an illegal │
│                         │                           │ connection                       │
└─────────────────────────┴───────────────────────────┴──────────────────────────────────┘
```

> [!CRITICAL]
> During Stage E, all rebooking write functions remain programmatically disabled. The FDE presents weekly Shadow Accuracy Reports to the airline OCC leadership to build trust.

---

### Stage F: Controlled Operational Pilot (Days 71–98)

#### Objective
Enable authorized write-back and passenger outreach for a strictly controlled operational scope (e.g., a single domestic hub pair like DEL-BOM, or a single corporate client account).

#### Controlled Pilot Protocol
1. **Human-in-the-Loop Mandatory:** Every rebooking recommendation must be explicitly approved by an authorized airline or agency operator via the Flyo Console. Autonomous auto-booking is locked.
2. **Passenger OTP Authentication:** Passengers must explicitly confirm alternative options via WhatsApp with a 6-digit OTP before any ticket is exchanged.
3. **Rollback & Reversal Readiness:** The FDE establishes a 24/7 dedicated escalation bridge. If a rebooking fails or causes an itinerary error, the original booking can be immediately restored via automated PSS coupon reversal.

---

### Stage G: Full Production Rollout & Governance (Day 99+)

#### Objective
Transition from pilot to standard enterprise operating procedure across all fleets, routes, and corporate accounts.

#### Operational Governance
* **Continuous SLA Monitoring:** 99.95% Gateway uptime; < 2.0s rebooking execution latency.
* **Automated Model Retraining:** Ingest real operational outcomes into the local region ML pipeline.
* **Monthly Business Reviews (MBR):** Deliver executive dashboards showing total passengers rescued, desk hours saved, hotel costs avoided, and customer Net Promoter Score (NPS) deltas.
