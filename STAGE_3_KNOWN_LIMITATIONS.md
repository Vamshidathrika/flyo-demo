# STAGE 3 — KNOWN SYSTEM LIMITATIONS & TECHNICAL DEBT INVENTORY

**Assessment Date:** 2026-11-06  
**Scope:** Flyo Rescue Agent v0.3.0 (Stage 3 Verification Baseline)  
**Classification:** Enterprise Engineering Disclosure  

---

## 1. Multi-Tenancy Architecture

### Current Implementation Baseline
Flyo employs **Logical Multi-Tenancy via Row-Level Scoping** (`tenant_id` columns on core operational tables: `rescue_cases`, `bookings`, `outbox_events`, `saga_step_logs`, `payment_transactions`, `virtual_cards`, `shared_rate_limits`, `webhook_events`, `discrepancy_records`, and `operator_accounts`).

### Known Limitations
1. **Shared Database Instance & Connection Pool:**
   * All tenants currently share the same PostgreSQL cluster and Hikari connection pool. A runaway query initiated by Tenant A (e.g. an exhaustive audit report) could consume database I/O, potentially increasing query latency for Tenant B (noisy neighbor effect).
2. **Lack of PostgreSQL Native Row-Level Security (RLS) Policies:**
   * Tenant isolation is enforced at the application tier via Spring Security `TenantFilter`, `TenantContext` ThreadLocal, and JPA/JDBC parameterized queries (`findByTenantId`). While verified across 18 adversarial tests, application bugs or raw unparameterized SQL queries in future modules could accidentally bypass isolation if not disciplined.
3. **Shared In-Memory JVM State:**
   * Global singleton beans (e.g. `ProviderCapabilityRegistry`, Spring ApplicationContext) reside in the same JVM.

### Upgrade Path to Hardened Production
* **Near-Term (Pilot):** Implement PostgreSQL native Row-Level Security (RLS) using `SET LOCAL app.current_tenant = ?` at connection checkout in Hikari.
* **Medium-Term (Scale):** Schema-per-tenant architecture for enterprise tier clients requiring strict data separation, separating read-replicas per high-volume tenant.

---

## 2. Distributed Rate Limiting

### Current Implementation Baseline
`DistributedRateLimiter` implements an atomic sliding-window bucket algorithm backed by the database table `shared_rate_limits` with row-level locks (`SELECT ... FOR UPDATE`). When run without a database, it falls back to an in-memory `ConcurrentHashMap` with a strict single-instance assertion guard (`flyo.instance.standalone=true`).

### Known Limitations
1. **Database Write Overhead Under High Concurrency:**
   * Using PostgreSQL for sub-second request rate limiting introduces write transaction overhead and row lock contention on the `shared_rate_limits` table during heavy burst traffic (>1,000 requests/sec).
2. **Fixed Window Jitter:**
   * The current algorithm resets timestamps on sliding 60-second windows. While sufficient for preventing brute-force and API abuse in pilot phases, it lacks leaky-bucket smoothing for bursty downstream provider quotas.

### Upgrade Path to Hardened Production
* **Pilot Phase:** Database-backed rate limiting is optimal for 1–5 nodes handling realistic airline pilot loads (up to 100 requests/sec) with zero operational infrastructure dependencies.
* **Production Scale:** Migrate the rate limiter backend to **Redis Cluster** or **Valkey** using atomic Lua scripts (`EVAL`) or token-bucket Redis modules for sub-millisecond overhead.

---

## 3. Webhook Security & Ingress Ingestion

### Current Implementation Baseline
Flyo enforces provider-specific cryptographic signature verification:
* Meta WhatsApp: `X-Hub-Signature-256` computed over raw bytes using HMAC-SHA256.
* Stripe Issuing: `Stripe-Signature` parsing timestamp (`t=...`) and signature (`v1=...`) with 300-second replay tolerance.
* Generic Webhook: HMAC-SHA256 signature and timestamp drift protection.
* Payload size capped at 512 KB, and events deduplicated idempotently via `webhook_events` database records.

### Known Limitations
1. **Mutual TLS (mTLS) Not Terminated at Spring Boot:**
   * Certain legacy GDS and Tier-1 airline NDC gateways require mutual certificate exchange (mTLS) at the transport layer. Flyo currently assumes mTLS or TLS termination occurs upstream at the ingress controller / API gateway (e.g. AWS ALB, Cloudflare, Envoy).
2. **Static IP Whitelisting Not Managed by Application:**
   * Airline partners frequently require egress/ingress IP whitelisting. This must be managed via cloud security groups or Kubernetes NetworkPolicies rather than Spring Security.

### Upgrade Path to Hardened Production
* Deploy an enterprise ingress gateway (Envoy / Traefik / NGINX Ingress) configured with client certificate validation and IP allowlists mapped to provider endpoints.

---

## 4. External Provider Connectivity & Airline Integrations

### Current Implementation Baseline
* All 11 registered adapters implement fail-closed interfaces with structured metadata and audit records.
* Adapters are classified as `CONFIGURED_NOT_VERIFIED`, `SANDBOX_CONNECTED`, or `SANDBOX_CONTRACT_TESTED`.
* No adapter is permitted to claim `PRODUCTION_VERIFIED` until genuine partner agreements and production credentials are deployed.

### Known Limitations
1. **Simulated Sandbox Booking & Ticketing:**
   * In sandbox environments (e.g. Sabre DevStudio, Amadeus for Developers), booking requests generate simulated PNRs. Real ticket issuance (EMD / e-ticket numbers) cannot be generated without an accredited IATA/ARC number and airline Ticketing Authority.
2. **NDC Schema Heterogeneity:**
   * IATA NDC messages (OrderCreate, OrderView) vary widely across airline versions (e.g., NDC 17.2, 18.2, 21.3). The current Flyo XML adapter handles standard AIDX and generic NDC schemas; bespoke airline XML extensions will require per-airline mapping profiles.

### Upgrade Path to Hardened Production
* Secure commercial IATA/ARC sponsorship or partner with an accredited Travel Management Company (TMC) / Consolidator to obtain real Ticketing Authority and certified NDC API keys.

---

## 5. Machine Learning Models & Predictive Airspace

### Current Implementation Baseline
* Version: `v2.0.0-multiseason`
* Models: Calibrated CatBoost / XGBoost classifiers and regressors trained on 2023–2024 US Bureau of Transportation Statistics (BTS) data.
* Airspace Guard: Live inference client inspects airport IATA codes; non-US flights (e.g. Indian domestic `6E501` HYD-BOM) immediately return `UNSUPPORTED_AIRSPACE` and fall back to deterministic aviation radar status and Open-Meteo weather telemetry.

### Known Limitations
1. **US Domestic Geographic Restriction:**
   * The ML models were trained exclusively on US domestic flights. They cannot be used to predict delays or cancellations for Indian (DGCA), European (Eurocontrol), or international cross-border routes without producing invalid predictions.
2. **Missing Airline-Internal Operational Telemetry:**
   * Predictive models rely on historical weather and flight status. They do not have visibility into real-time airline crew duty limitations, mechanical logbooks, or ground aircraft rotations.

### Upgrade Path to Hardened Production
* Retain the existing architectural separation: deterministic operational truth (AIDX/ADS-B radar) holds **Primary Authority**; ML predictions provide advisory prioritization only.
* Train regional models using Eurocontrol / DGCA historical datasets as client pilots expand internationally.

---

## 6. Distributed Saga Coordinator

### Current Implementation Baseline
`RebookingSagaCoordinator` executes an asynchronous multi-step rebooking sequence (Reserve Inventory -> Issue Virtual Card -> Ticket Flight -> Notify Passenger) with database-backed persistence (`saga_executions`, `saga_step_logs`), lease recovery, retry policies, and automated compensation routines.

### Known Limitations
1. **In-Process Background Thread Execution:**
   * Sagas are scheduled and executed across local JVM thread pools. If an application pod crashes during step execution, the saga is recovered by another instance through database lease expiration (after 10 minutes). This introduces a recovery delay.
2. **Manual Intervention for Ambiguous Financial Compensations:**
   * If a virtual card refund fails during compensation, the saga transitions to `FAILED` and logs a discrepancy record for human operator resolution rather than attempting unbounded blind retries.

### Upgrade Path to Hardened Production
* For enterprise tier operations (>10,000 concurrent disruptions), evaluate wrapping saga definitions into a durable workflow engine such as **Temporal.io** or **AWS Step Functions**, while keeping Flyo's existing deterministic compensation actions intact.
