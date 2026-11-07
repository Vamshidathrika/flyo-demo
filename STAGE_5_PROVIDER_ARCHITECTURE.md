# FLYO STAGE 5 — ENTERPRISE PROVIDER INTEGRATION ARCHITECTURE

**Classification:** Enterprise Systems & Integration Architecture  
**Author:** Principal Enterprise Integration Architect & Forward Deployment Lead  
**Scope:** Flyo Aviation & Travel Partner External Connectors (`flyo-rescue-demo/`)  
**Status:** Certified Architecture & Contract Specification  
**Version:** 5.0.0-PROD  

---

## 1. Architectural Principles & Boundaries

The Flyo Provider Integration Architecture establishes strict decoupling between the internal case-management domain and external airline, GDS, messaging, CRM, and treasury protocols. 

To eliminate integration ambiguity and ensure pilot safety:
1. **Never conflate mock simulations with live sandboxes:** Every provider adapter explicitly reports its true capability lifecycle state.
2. **Never leak secrets:** All API keys, OAuth secrets, and client credentials are read strictly from environment variables or secure key vaults; they are never persisted in databases, printed in logs, or exposed to the frontend.
3. **Decouple network I/O from database transactions:** Remote HTTP/SOAP calls are dispatched by background workers or saga steps outside heavy database lock windows.
4. **Idempotent requests:** Every mutating provider dispatch requires a deterministic idempotency key and correlation ID.
5. **Fail-closed security:** If external credentials are not present, adapters fail closed with `ProviderNotConfiguredException` and report `NOT_CONFIGURED` or `CONFIGURED_NOT_VERIFIED`, rather than manufacturing fabricated success records.

---

## 2. Component Separation & Layering

The integration boundary is structured into 10 decoupled layers:

```
[ Domain Case / Saga Service ]
              │
              ▼
    [ Provider Interface ]  (e.g., FlightRebookingProvider, TicketVerificationProvider)
              │
              ▼
   [ Provider Adapter Class ] (e.g., AmadeusAlteaAdapter, MetaWhatsAppAdapter)
              │
    ┌─────────┴─────────┐
    ▼                   ▼
[ Auth Provider ]  [ Request / Response Mappers ]
    │                   │
    └─────────┬─────────┘
              ▼
      [ HTTP Transport ]  (java.net.http.HttpClient with TLS 1.3 & Timeout Enforcements)
              │
              ▼
   [ Provider Error Mapper ] (Amadeus / Meta / Salesforce -> Domain Exceptions)
              │
              ▼
[ Capability Evidence Recorder ] (Persists ProviderEvidenceRecord in DB)
```

1. **Domain Service:** Coordinates the business workflow (e.g. `RebookingSagaCoordinator`, `CaseService`). Has zero knowledge of external wire formats (SOAP, JSON, XML).
2. **Provider Interface:** Strongly typed Java contracts in `com.flyo.rescue.integration` defining domain input/output DTOs.
3. **Provider-Specific Adapter:** Implements the interface, delegates to client, and registers metadata in `ProviderCapabilityRegistry`.
4. **Authentication Implementation:** Handles OAuth2 client credentials grants, token caching, and proactive expiry refresh.
5. **HTTP Transport:** Configured with strict connect timeouts (10s), read timeouts (30s), and TLS 1.3 encryption.
6. **Request/Response Mapper:** Translates Flyo domain models to and from external API schemas (e.g., IATA 21.3 NDC JSON).
7. **Provider Error Mapper:** Translates HTTP 400, 401, 403, 404, 429, and 500 responses into structured Flyo exceptions (`ProviderAuthenticationException`, `ProviderRateLimitException`, `ProviderTimeoutException`, `ProviderBusinessRuleException`).
8. **Contract Test Suite:** WireMock and HTTP unit contract tests verifying schema compliance without needing live network access.
9. **Sandbox Integration Test:** Live network tests executing against partner test environments when credentials are provided.
10. **Capability Evidence Recorder:** Automatically records empirical evidence records (`ProviderEvidenceRecord`) in PostgreSQL/Flyway.

---

## 3. Explicit Provider Capability Lifecycle

Every provider connection transitions through explicit, audited states:

| Lifecycle State | Definition | Gate Requirements |
| :--- | :--- | :--- |
| **`NOT_CONFIGURED`** | Adapter is present in code, but no credentials or target endpoints are configured. | Default initial state when environment variables are unset. |
| **`CONFIGURED_NOT_VERIFIED`** | Credentials and URLs are injected, but no network handshake has been conducted. | Configuration validated; awaiting connection probe. |
| **`AUTHENTICATION_VERIFIED`** | Successfully exchanged client credentials for an OAuth2 bearer token or verified API key. | HTTP 200 on OAuth token endpoint; valid JWT/token returned. |
| **`CONNECTIVITY_VERIFIED`** | TCP/TLS handshake complete, and base health endpoint is reachable within SLA. | Ping/health probe responds within 2,500ms. |
| **`SANDBOX_CONTRACT_VERIFIED`** | Request and response payloads verified against provider's official sandbox schema. | WireMock / contract tests pass with 0 schema violations. |
| **`SANDBOX_OPERATION_VERIFIED`** | Complete business transaction (search, order create, retrieval) verified in sandbox. | Authoritative reservation reference created and retrieved. |
| **`PRODUCTION_CONNECTED`** | Reached production host with certified production credentials. | Production TLS certificate validated; production auth verified. |
| **`PRODUCTION_OPERATION_VERIFIED`**| End-to-end production operations certified with reconciliation and SLA proof. | Full production pilot sign-off with audit logging. |
| **`DEGRADED`** | Experiencing latency breaches (> 3,000ms), 5xx error spikes, or upstream downtime. | Circuit breaker half-open or rate-limit throttled. |
| **`DISABLED`** | Administratively suspended or muted by operator command in console. | Operator command executed with reason log. |
| **`UNSUPPORTED`** | Capability not supported by adapter or underlying provider protocol. | Provider lacks required NDC/GDS service. |

---

## 4. Provider Inventory & Real Operational Status

| Provider Code | Domain Role | Implementation Class | Operational Capability State | Transport | Failover / Fallback |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`amadeus_gds`** | Flight Orders & Offers | `AmadeusGdsSandboxClient` | `CONFIGURED_NOT_VERIFIED` / `BLOCKED` | REST / HTTPS (IATA 21.3) | Escalate to Manual Desk |
| **`amadeus_altea`** | PSS & Ticket Verification| `AmadeusAlteaAdapter` | `CONFIGURED_NOT_VERIFIED` | REST / HTTPS | Flag Unverified Ticket |
| **`sabre_sonic`** | GDS Secondary Channel | `SabreGdsBookingAdapter` | `NOT_CONFIGURED` | REST / HTTPS | Amadeus Primary |
| **`openmeteo`** | Airport Meteorology | `OpenMeteoWeatherService` | `PRODUCTION_OPERATION_VERIFIED` | REST / HTTPS (Public API) | Cached METAR Telemetry |
| **`aviation_radar`** | Radar Telemetry | `AviationRadarClient` | `CONFIGURED_NOT_VERIFIED` / `MOCK` | REST / HTTPS | Multi-Source Reconciliation |
| **`meta_whatsapp`** | Passenger Comms | `MetaWhatsAppClient` | `CONFIGURED_NOT_VERIFIED` | REST / Graph API v21.0 | Operator Chat Console |
| **`salesforce_crm`**| Enterprise CRM | `SalesforceServiceCloudAdapter` | `CONFIGURED_NOT_VERIFIED` | REST / OAuth2 (v59.0) | Retained Outbox Event |
| **`treasury_vcc`** | Corporate Virtual Card | `SandboxPaymentAdapter` | `SANDBOX_CONTRACT_VERIFIED` | Local Tokenized Simulator | Escalate to Manual Override |
| **`stripe_issuing`**| Production Treasury | `StripeIssuingPaymentAdapter` | `NOT_CONFIGURED` | REST / HTTPS | Treasury Sandbox |
| **`fastapi_bts_ml`**| Delay/Cancellation ML | `PredictionServiceClient` | `SANDBOX_OPERATION_VERIFIED` | HTTP / JSON Service | Deterministic MCT Fallback |

---

## 5. Security & Secret Isolation Guidelines

1. **Environment Injection:** Credentials must be supplied via environment variables (`AMADEUS_CLIENT_ID`, `AMADEUS_CLIENT_SECRET`, `WHATSAPP_ACCESS_TOKEN`, `SALESFORCE_CLIENT_SECRET`, `STRIPE_SECRET_KEY`).
2. **Secret Masking:** In all logs and audit events, tokens and keys are masked (e.g. `sk_live_...4a8b`, `tok_...998f`).
3. **No Database Persistence of Raw Credentials:** Tenant configurations store references to secret vault aliases, never raw plaintext secrets.
4. **Network Boundaries:** Outbound HTTP connections enforce TLS 1.3 and validate hostname certificates against standard Java trust stores.
