# Flyo Existing Connector Gap Analysis & Repository Audit

**Document Status:** Technical Architecture & Codebase Gap Audit  
**Author:** Principal Forward Deployment Engineer & Software Architect  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Executive Repository Audit Summary

A rigorous inspection of the current Flyo repository (`/Users/nani/Downloads/flyo`) reveals two distinct realities:

1. **The Strengths:** Flyo has developed sophisticated, high-quality **domain decision logic**. The Minimum Connection Time engine ([`MctRulesService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/MctRulesService.java)), the multi-source evidence reconciler ([`FlightEvidenceReconciliationService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/FlightEvidenceReconciliationService.java)), the transaction outbox pattern ([`OutboxService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/OutboxService.java)), the human approval state machine ([`CaseStateMachineService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/CaseStateMachineService.java)), and the WhatsApp OTP challenge security flow ([`WhatsAppOtpService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/WhatsAppOtpService.java)) are robust, well-architected, and production-grade.
2. **The Gaps:** As an **integration product**, the current implementation relies heavily on synthetic mocks, regex scrapers, and headless browser automation designed for demonstration purposes. It cannot connect to a real airline's PSS or an agency's GDS without architectural extension.

---

## 2. Component-by-Component Technical Gap Audit

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                COMPONENT GAP EVALUATION MATRIX                                   │
├───────────────────────┬─────────────────────────────┬─────────────────────────────┬──────────────┤
│ System Component      │ Current Flyo Implementation │ Enterprise Client Req.      │ Gap Severity │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ PSS / GDS Rebooking   │ Playwright Headless Browser │ Certified GDS / NDC API     │ CRITICAL     │
│ Execution             │ on Mock HTML Portal         │ with Structured Exchange    │ (P0)         │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Flight Inventory &    │ Hardcoded Template Flights  │ Dynamic Live Availability   │ CRITICAL     │
│ Search                │ (`FL101`, `FL301`, `FL401`) │ Engine (BargainFinderMax)   │ (P0)         │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Multi-Tenancy &       │ Zero `tenant_id` columns    │ PostgreSQL Row-Level        │ CRITICAL     │
│ Data Isolation        │ Single-tenant shared DB     │ Security (RLS) + Isolation  │ (P0)         │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Payment & Card Data   │ Plaintext PAN and CVV in    │ Tokenized Corporate VCC     │ CRITICAL     │
│ Security (PCI)        │ `virtual_cards` SQL table   │ Proxy; Zero CVV in DB       │ (P0)         │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Itinerary Ingestion   │ Regex extraction on text    │ Certified GDS Queue Sweeper │ HIGH         │
│                       │ pasted to `/api/ingest/pnr` │ / PSS Webhook Ingress       │ (P1)         │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Flight Telemetry      │ AviationStack HTTP polling /│ Real-Time Streaming B2B     │ HIGH         │
│ Telemetry Feed        │ Mock fallback in RadarClient│ Feed (Cirium / AIDX XML MQ) │ (P1)         │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Machine Learning      │ Trained on US BTS data;     │ Validated Indian Domestic   │ HIGH         │
│ Disruption Inference  │ Geofenced out Indian flights│ Disruption Model Corpus     │ (P1)         │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Operator UI Console   │ Hardcoded "Ananya Sharma" / │ Dynamic Enterprise Flight & │ MEDIUM       │
│ Frontend              │ Case `C1` / Flight `AI-839` │ Case Search / Multi-User    │ (P2)         │
└───────────────────────┴─────────────────────────────┴─────────────────────────────┴──────────────┘
```

---

## 3. In-Depth Component Analysis

### 3.1. Rebooking Adapters: Playwright vs. Certified APIs
* **Current State:** 
  In [`PartnerPortalPlaywrightAdapter.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/rebook/PartnerPortalPlaywrightAdapter.java), the class is annotated `@Primary`. It executes:
  ```java
  List<String> cmd = List.of("node", "--experimental-strip-types", "rebook.ts", 
                             "--baseUrl", "http://localhost:8080");
  ```
  This spawns a headless browser script driving Flyo's mock airline HTML portal (`/mock-airline/search.html`).
  Meanwhile, [`AmadeusNdcBookingAdapter.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/rebook/AmadeusNdcBookingAdapter.java) throws:
  ```java
  throw new UnsupportedOperationException("Amadeus live NDC channel requires approved IATA agency certification.");
  ```
  And [`SabreGdsBookingAdapter.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/rebook/SabreGdsBookingAdapter.java) throws `ProviderNotConfiguredException` when credentials are missing.
* **The Gap:** Enterprise airlines and agencies block bot scraping via Cloudflare / Akamai Bot Manager and MFA. Rebooking must flow through certified GDS/NDC APIs, with Playwright strictly relegated to internal regression testing.

### 3.2. Alternative Flight Search: Hardcoded Mocks vs. Live GDS
* **Current State:**
  In [`FlightSearchService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/FlightSearchService.java), the candidate recovery options are generated via hardcoded static objects:
  ```java
  new RebookOption(o1Id, caseId, "FL101", origin + " → " + destination + " (Direct)", ...);
  new RebookOption(o2Id, caseId, "FL301", origin + " → BOM → " + destination + " (Express Hub)", ...);
  new RebookOption(o3Id, caseId, "FL401", origin + " → " + destination + " (Direct Daytime)", ...);
  ```
* **The Gap:** In a real disruption, an operator cannot book synthetic flight `FL301`. Flyo must integrate with live GDS availability (`BargainFinderMaxRQ`) or PSS seat inventory (`POST /rest/api/nsk/v1/booking/availability`).

### 3.3. Database Multi-Tenancy & PCI Security
* **Current State:**
  In [`V1__initial_production_schema.sql`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/resources/db/migration/V1__initial_production_schema.sql):
  1. The `bookings` table has no `tenant_id`. Every client's passenger data would be commingled in a single global table.
  2. The `virtual_cards` table stores plaintext card details:
     ```sql
     card_number VARCHAR(32) NOT NULL,
     expiry VARCHAR(16) NOT NULL,
     cvv VARCHAR(32) NOT NULL
     ```
* **The Gap:** Commingled data violates enterprise tenant isolation. Storing plaintext PAN and CVV violates PCI-DSS Requirement 3.2.

### 3.4. PNR Ingestion: Regex Scraping vs. Queue Sweeping
* **Current State:**
  In [`PnrIngestionService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/PnrIngestionService.java), ingestion relies on regex pattern matching against unstructured raw text pasted into `/api/ingest/pnr`.
* **The Gap:** Real travel operations do not copy and paste emails. Real itineraries must be ingested via automated GDS queue polling or PSS webhook event streams.

### 3.5. Predictive ML Disruption Engine
* **Current State:**
  In [`predictor.py`](file:///Users/nani/Downloads/flyo/ml/service/predictor.py), the service explicitly blocks Indian flights:
  ```python
  if origin in INDIAN_AIRPORT_CODES or carrier in INDIAN_AIRLINE_CODES:
      return (PredictionStatus.UNSUPPORTED_AIRSPACE,
              "Flyo v2 ML disruption models were trained exclusively on US domestic flights (BTS).")
  ```
* **The Gap:** An Indian airline client (Air India, IndiGo) cannot use the US BTS model. A localized Indian model corpus must be trained on DGCA historical delay data before ML disruption scores can be activated for Indian airspace.

---

## 4. Preservation & Reuse Strategy: What We Keep

Flyo does **NOT** need to be rewritten from scratch. The core intellectual property and architecture are preserved:
1. **Retain 100% of the State Machine:** The 9-state disruption recovery lifecycle in [`CaseStateMachineService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/CaseStateMachineService.java) and [`RescueCase.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/model/RescueCase.java) is fully retained.
2. **Retain 100% of the MCT Safety Engine:** The connection time evaluation in [`MctRulesService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/MctRulesService.java) remains the authoritative safety barrier.
3. **Retain 100% of the Evidence Reconciliation Logic:** Multi-source confidence scoring in [`FlightEvidenceReconciliationService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/FlightEvidenceReconciliationService.java) remains active.
4. **Retain 100% of the WhatsApp Security & OTP Workflow:** Passenger interactive messaging, OTP generation, and rate limiting in [`WhatsAppOtpService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/WhatsAppOtpService.java) are enterprise-ready.
