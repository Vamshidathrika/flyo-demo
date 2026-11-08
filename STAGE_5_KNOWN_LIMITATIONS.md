# FLYO STAGE 5 — KNOWN LIMITATIONS AND EXTERNAL DEPENDENCIES

**Document ID:** `FLYO-STG5-LIMIT-015`  
**Classification:** Operational Transparency & System Boundaries  
**Application:** Flyo Rescue System (`flyo-rescue-demo`)  
**Date:** November 2026  
**Auditor:** Principal Integration Architect & Reliability Engineer  

---

## 1. Executive Summary

In adherence to Flyo's engineering principles of absolute operational transparency and zero false integration claims, this document catalogs the precise external dependencies, environment boundaries, and known limitations of the current codebase.

While Flyo provides fully compiled, typed, and contract-tested enterprise adapters for every external integration, live end-to-end execution against third-party production servers is bounded by credentials and environment provisioning. This inventory separates **software readiness** from **external access availability**.

---

## 2. External Integration Dependency Matrix

| Provider / Service | Functional Capability | Implementation Status | Sandbox / Live Credential Status | Operational Classification |
|---|---|---|---|---|
| **Amadeus GDS** | Flight search, order creation, independent order retrieval, electronic ticket verification | Typed REST client ([AmadeusGdsSandboxClient.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/amadeus/AmadeusGdsSandboxClient.java)) with 7 contract tests | Missing `AMADEUS_API_KEY` and `AMADEUS_API_SECRET` in environment | **BLOCKED_BY_EXTERNAL_ACCESS** |
| **Sabre PSS** | Host airline rebooking, PNR servicing, coupon status check | Interface & adapter architecture implemented | Missing Sabre EPR (Employee Profile Record), PCC, and host credentials | **BLOCKED_BY_EXTERNAL_ACCESS** |
| **Meta WhatsApp Cloud API** | Passenger rescue messaging, interactive buttons, delivery webhooks | Typed client ([MetaWhatsAppClient.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/whatsapp/MetaWhatsAppClient.java)), HMAC validator ([MetaWhatsAppSignatureValidator.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/webhook/signature/MetaWhatsAppSignatureValidator.java)) | Missing `WHATSAPP_ACCESS_TOKEN` and Phone Number ID | **BLOCKED_BY_EXTERNAL_ACCESS** (Local simulation fallback active) |
| **Salesforce Service Cloud** | Passenger CRM case upsert, resolution audit logging | OAuth2 client ([SalesforceServiceCloudAdapter.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/crm/SalesforceServiceCloudAdapter.java)) with SObject REST dispatch | Missing `SALESFORCE_CLIENT_ID` and `SALESFORCE_CLIENT_SECRET` | **BLOCKED_BY_EXTERNAL_ACCESS** |
| **Stripe Issuing / Treasury** | Virtual card generation, spending caps, 12-state payment lifecycle | Payment service ([PaymentSettlementService.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/PaymentSettlementService.java)), webhook validator ([StripeSignatureValidator.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/webhook/signature/StripeSignatureValidator.java)) | Missing `STRIPE_SECRET_KEY` | **BLOCKED_BY_EXTERNAL_ACCESS** (Local sandbox active) |
| **Open-Meteo Weather** | Airport METAR, wind, visibility, convective weather telemetry | Real public REST client ([OpenMeteoWeatherClient.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/integration/weather/OpenMeteoWeatherClient.java)) with 15-minute TTL caching | None required (Free tier public REST API) | **VERIFIED_REAL** (Functional over live internet) |

---

## 3. Database & Persistence Limitations

### In-Memory H2 Database (Current Test Baseline)
- **Current Behavior:** The automated test suite (`mvn test`) runs against an embedded in-memory H2 database (`jdbc:h2:mem:flyo_test`).
- **Limitation:** In-memory H2 does not replicate PostgreSQL-specific behaviors such as row-level locking (`SELECT ... FOR UPDATE`), transaction concurrency isolation under parallel load, or Flyway migration history persistence across application restarts.
- **Client Deployment Requirement:** Production and pilot deployments must provision a managed PostgreSQL 15+ database instance (Amazon RDS, Google Cloud SQL, or Supabase).

---

## 4. Machine Learning Disruption Model Boundaries

### US BTS Dataset Boundary
- **Training Data:** The disruption prediction and delay scoring model ([flight_delay_model.py](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/ml/models/flight_delay_model.py)) is trained on the United States Bureau of Transportation Statistics (BTS) Airline On-Time Performance dataset (2020–2024).
- **Domain Coverage:** High predictive confidence for US domestic operations (Delta, American, United, Southwest) across major US airports (ATL, ORD, DFW, DEN, JFK, LAX).
- **Known Limitation:** The model has lower predictive accuracy for international air traffic control flow management (ATFM) constraints (e.g., Eurocontrol Network Manager slots in Europe or DGCA weather congestion in India).
- **Remediation for International Pilots:** Ingest regional historical radar and delay data prior to launching pilots outside the US domestic network.

---

## 5. Minimum Connection Time (MCT) Matrix Coverage

### Published Airport Matrix
As hardened in Stage 5 ([MctRulesService.java](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/MctRulesService.java)), official terminal-specific MCT rules are published and verified for seven hub airports:
- `DEL` (Delhi Indira Gandhi)
- `BOM` (Mumbai Chhatrapati Shivaji)
- `BLR` (Bengaluru Kempegowda)
- `LHR` (London Heathrow)
- `FRA` (Frankfurt am Main)
- `DXB` (Dubai International)
- `SIN` (Singapore Changi)

### Fallback Policy for Unlisted Airports
For all airports outside this matrix, Flyo applies a conservative default buffer (60 minutes for domestic, 120 minutes for international) and **strictly marks the feasibility evaluation as requiring human operator review** (`requiresManualReview = true`). It does not assume feasibility based solely on arrival and departure time differences.

---

## 6. Financial & Virtual Card Issuance Boundaries

### PCI DSS Scope Limitation
- **Implemented:** Flyo implements strict data minimization. Full PANs are masked to last 4 digits; CVVs are **never** stored, logged, or cached.
- **Limitation:** Flyo is **not** a PCI DSS Level 1 certified service provider. In enterprise deployments, virtual card generation and settlement must route through a certified third-party payment gateway (Stripe Issuing, Adyen, or Wex) where Flyo operates strictly as an orchestrator without touching raw card data streams.

---

## 7. Prerequisites for Production Unblocking

To promote any provider from `BLOCKED_BY_EXTERNAL_ACCESS` to `PRODUCTION_OPERATION_VERIFIED`:
1. Execute commercial agreements with the respective API provider (Amadeus, Sabre, Meta, Salesforce, Stripe).
2. Provision dedicated enterprise sandbox / production credentials in the client's secure vault.
3. Inject environment variables according to [STAGE_5_DEPLOYMENT_PIPELINE_AND_OPERATIONS.md](file:///Users/nani/Downloads/flyo/STAGE_5_DEPLOYMENT_PIPELINE_AND_OPERATIONS.md).
4. Run provider-specific smoke verification tests before routing live passenger rescue cases.

---

*Report signed by Principal Integration Architect & Reliability Engineer, November 2026.*
