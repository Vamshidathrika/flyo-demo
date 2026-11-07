# FLYO — STAGE 0 KNOWN LIMITATIONS & DELIBERATE SCOPE BOUNDARIES

**Document Version:** 1.0.0  
**Status:** Approved Reference  
**Date:** November 6, 2026  
**Implementation Phase:** Stage 0 (Security Containment & Baseline Hardening)  

---

## 1. Scope Boundary Enforcement

Per user instructions and the architectural transformation roadmap, **Stage 0 strictly encompasses security containment, PCI data protection, and profile hardening**.

The following capabilities were deliberately excluded from Stage 0 to preserve architectural containment and prevent unvetted code churn before customer integration:

---

## 2. Inventory of Known Limitations in Stage 0

### 2.1 External Airline & Distribution System Integrations (Deferred to Stages 2–3)
- **Real Airline GDS / PSS / NDC Connectors:** Flyo does not yet connect to live Amadeus Altéa, Sabre GDS, Navitaire New Skies, or airline NDC Order APIs. Flight searches, seat availability checks, and booking confirmations remain simulated in development/sandbox adapters.
- **Transactional Outbox Worker:** Although `outbox_events` table and `OutboxService.queueEvent()` exist, an asynchronous background message polling worker (e.g. Spring `@Scheduled` or Debezium CDC) has not yet been enabled. Events are durably stored in the database but await the Stage 1 outbox processor.

### 2.2 Payment Gateway Live Settlement (Deferred to Stage 4)
- **Live VCC Issuance:** The payment adapter is currently configured to `SandboxPaymentAdapter`, which simulates provider token creation (`tok_sandbox_...`). Live integration with Stripe Issuing, Marqeta, or corporate banking VCC APIs requires live merchant credentials and secure webhook signature verification.
- **PCI Attestation:** Stage 0 has eliminated cardholder data storage and exposure. However, formal PCI SAQ validation remains subject to a formal Qualified Security Assessor (QSA) audit based on the production infrastructure topology.

### 2.3 Operational Flight Data & Weather Feeds (Deferred to Stage 2)
- **Open-Meteo Weather:** Open-Meteo provides supplementary numerical weather prediction (NWP) model data, but is **not** an aviation-certified METAR / TAF observation source. Real operational deployment requires connecting to certified airport weather feeds (e.g. NOAA Aviation Weather Center or SITA/Cirium).
- **Aviation Feeds:** Live radar polling currently connects to AviationStack or SimpleFlying RSS. Production deployment requires authorized AIDX operational feeds from airline Operations Control Centers (OCC) or airport Airport Operations Databases (AODB).

### 2.4 Machine Learning Inference & Retraining (Deferred to Stage 5)
- **BTS Model Operational Restrictions:** The v2 machine learning models in `ml/artifacts/` were trained on US domestic Bureau of Transportation Statistics (BTS) data from 2019–2024.
  - The model has a known 0.0 recall on cancellation classification and weak extreme delay regression.
  - Geofencing strictly disqualifies Indian airspace flights (`UNSUPPORTED_AIRSPACE`) and non-BTS carriers.
  - Deterministic radar evidence reconciliation remains 100% authoritative for disruption detection and case creation.

### 2.5 Single-Passenger Demonstration Frontend (Deferred to Stage 6)
- **Demo Dashboard:** The frontend at `src/main/resources/static/index.html` remains tailored to single-passenger demonstration workflows (Ananya Sharma / HYD $\to$ DEL). Enterprise multi-passenger triage, batch case filtering, and PSS manifest views will be built during Stage 6.

---

## 3. Risk Mitigation Summary for Current Stage 0 Posture

| Risk / Limitation | Why Safe in Stage 0 | Planned Resolution Phase |
| :--- | :--- | :--- |
| Mock airline portal | Isolated by `@Profile("!prod")`; unavailable in production profile | Stage 3 (Real GDS / NDC Connectors) |
| Synthetic flight options | Sandboxed within `RankingEngine`; does not execute real bookings | Stage 2 (Client PSS / GDS Inventory) |
| Missing outbox worker | Case state machine executes synchronously with immediate DB consistency | Stage 1 (Transactional Outbox Worker) |
| Open-Meteo non-METAR | Weather telemetry treated as advisory evidence; does not alter radar confidence | Stage 2 (Aviation Weather / METAR Feed) |
| BTS ML limitations | Airspace geofencing blocks non-US flights; deterministic engine is authoritative | Stage 5 (Client Historical Retraining) |
