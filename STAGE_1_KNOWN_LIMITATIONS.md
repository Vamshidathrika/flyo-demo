# FLYO — STAGE 1: KNOWN LIMITATIONS & OPEN TECHNICAL DEBT

**Author:** Forward Deployment & Principal Systems Architect  
**Date:** November 6, 2026  
**Status:** ACTIVE ASSESSMENT  
**Target Application:** Flyo Backend (Stage 1 Completed)  

---

## 1. Executive Summary

While Stage 1 successfully delivers a resilient Transactional Outbox, Distributed Saga Engine, and PostgreSQL `FOR UPDATE SKIP LOCKED` worker framework, several operational limitations remain before Flyo can be connected to live commercial airline systems in production.

This document catalogs all confirmed limitations, architectural boundaries, and pending Stage 2 prerequisites.

---

## 2. Catalog of Known Limitations

### 1. In-Memory Login Rate Limiter (Single-Instance Bound)
- **Status:** Architectural Limitation (Stage 0 Technical Debt)
- **Component:** `com.flyo.rescue.security.LoginRateLimiter`
- **Description:** Rate limiting for operator and supervisor authentication attempts is implemented via an in-memory `ConcurrentHashMap`. While thread-safe on a single node, in a multi-pod Kubernetes or ECS deployment, login attempts will be split across pods, multiplying the effective brute-force threshold.
- **Production Requirement (Stage 2/3):** Must be backed by Redis (`spring-boot-starter-data-redis`) or PostgreSQL advisory lock rate limiting prior to multi-replica horizontal autoscaling.

### 2. Payment Gateway Adapter is a Sandbox Surrogate
- **Status:** Simulation Boundary (By Design in Stage 1)
- **Component:** `com.flyo.rescue.service.payment.SandboxPaymentAdapter`
- **Description:** Virtual Commercial Card (VCC) issuance and settlement currently generate synthetic tokens (`tok_sandbox_*`) and internal card records. Flyo is **NOT** connected to live Stripe Issuing, Marqeta, or WEX commercial card banking APIs.
- **Production Requirement (Stage 2):** Live provider authentication, webhook listener for settlement clearing, and financial compliance certifications.

### 3. Airline Rebooking Relies on Headless Browser (Playwright) Mock Portal
- **Status:** Carrier Integration Boundary (By Design in Stage 1)
- **Component:** `com.flyo.rescue.service.rebook.PartnerPortalPlaywrightAdapter`
- **Description:** Automated rebooking execution interacts with Flyo's local mock airline portal via Playwright automation. It does not communicate with live Amadeus Altéa NDC, Sabre 1B/1S GDS, or Navitaire New Skies PSS APIs.
- **Production Requirement (Stage 2):** Enterprise NDC / GDS direct XML/JSON connectors matching client airline specifications.

### 4. Single-Passenger Itinerary Focus
- **Status:** Domain Limitation
- **Component:** `RescueCase`, `Booking`, `RebookOption`
- **Description:** The current rescue data model is optimized for single-passenger PNRs (or primary contact resolution). Multi-passenger PNR split operations, infant-in-lap ticketing, and group baggage reconciliations are not yet modeled.
- **Production Requirement (Stage 3):** Canonical PNR Passenger Splitting engine and multi-ticket issuance saga.

### 5. Open-Meteo Weather Service is Free-Tier / Public
- **Status:** External Dependency Boundary
- **Component:** `com.flyo.rescue.service.OpenMeteoWeatherService`
- **Description:** Operational weather ingestion queries the public Open-Meteo API. While functional and free of API keys, it lacks contractual enterprise SLAs, high-frequency radar METAR/TAF parsing, and convective storm SIGMET ingestion.
- **Production Requirement (Stage 2/3):** Aviation weather provider (e.g. NOAA AWOS, The Weather Company Aviation, or Jeppesen) with contracted availability.

### 6. Synchronous Reconciliation Queries in Carrier Portal
- **Status:** Implementation Boundary
- **Component:** `RebookingSagaCoordinator.reconcileWithCarrier`
- **Description:** Carrier reconciliation currently checks the local case repository and returns a mock absent status if PNR is not present. Real carrier reconciliation will require querying the airline PSS via NDC OrderRetrieve or GDS PNR read.

---

## 3. Summary Table

| Limitation | Impact | Stage 1 Mitigation | Production Remediation Target |
| :--- | :--- | :--- | :--- |
| **In-Memory Rate Limiter** | Multi-pod auth tracking incomplete | Single-node safety verified | Stage 2 Redis cluster integration |
| **Sandbox Payment Adapter** | No real bank funds moved | Safe development; no monetary risk | Stage 2 Stripe Issuing / Marqeta live adapter |
| **Playwright Mock Portal** | No real ticket issuance on airlines | Clean saga and provider contract separation | Stage 2 Amadeus NDC / Sabre connector |
| **Single-Passenger Focus** | Groups require manual intervention | Clear operational boundaries | Stage 3 Multi-passenger PNR splitting |
| **Public Open-Meteo API** | No enterprise SLA on weather | Non-blocking fallback in ML client | Stage 3 NOAA / Jeppesen METAR/TAF |
