# STAGE 13B: Operations Control Certification & Provider Resilience

**System:** Flyo Rescue Agent — Airline OCC Operations Console  
**Milestone:** Stage 13B Runtime Certification & Operational Usability  
**Status:** PASS & CERTIFIED  

---

## 1. Operations Control Architecture

The Operations Control view (`#view-ops`) serves as the operational command and infrastructure governance hub for corporate travel desk leads and OCC supervisors.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ STAGE 7 CONTROL · Enterprise Disruption Operations Control Center                     │
│ Active Cases: 3 | Manual Desk: 1 | Failed/Escalated: 0 | Resolved: 5 | [⟳ Refresh Ops] │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ 1. External Provider Health Matrix (Amadeus, WhatsApp, Stripe, PostgreSQL, Redis)       │
│ 2. Distributed Saga Orchestration Monitor & Reconciliation Queue                       │
│ 3. Ticketing Desk Manual Intervention Desk (Timeout & 409 Conflict Handling)           │
│ 4. Regional Hub Disruption Radar (Weather, Visibility, Holding Delays)                 │
│ 5. Operational ML Model Health & Drift Telemetry (p95 Latency, PSI Drift, Calibration) │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. External Provider Health Matrix & State Taxonomy

To prevent operational misdirection, the console enforces an unambiguous provider state taxonomy:
- **`HEALTHY`:** Circuit breaker `CLOSED`; average response latency $< 500$ms; zero error rate.
- **`DEGRADED`:** Circuit breaker `CLOSED`; p95 latency $> 2,000$ms or minor transient retry rate ($< 5\%$).
- **`UNAVAILABLE`:** Circuit breaker `OPEN`; provider unreachable or returning HTTP 5xx errors.
- **`NOT_CONFIGURED`:** API keys or credentials absent; adapter in strict **Fail-Closed** mode.
- **`UNKNOWN`:** Heartbeat probe timed out or pending first check.

### Real-Time Provider Inventory
| Provider Service | Interface Adapter | Circuit Breaker Policy | Current Runtime State | Operational Guarantee |
| :--- | :--- | :--- | :---: | :--- |
| **Amadeus GDS Portal** | `AmadeusNdcBookingAdapter` | 3 consecutive failures $\to$ 30s open | `SANDBOX / HEALTHY` | Never fabricates bookings if unconfigured. |
| **Meta WhatsApp API** | `MetaWhatsAppClient` | HMAC-SHA256 signature verification | `SANDBOX / ACTIVE` | Fails closed if app secret mismatch. |
| **Stripe Issuing** | `SandboxPaymentAdapter` | MCC 3000 Merchant Category Lock | `SANDBOX / HEALTHY` | Tokenized single-use VCC issuance. |
| **PostgreSQL 16 & Redis** | `PostgreSqlStorageEngine` | Optimistic locking (`@Version`) | `LIVE / HEALTHY` | Zero uncoordinated concurrent writes. |
| **Aviation Radar** | `AviationRadarClient` | Unconfigured API Key Detection | `FAIL CLOSED` | Reports unconfigured rather than fake data. |
| **Open-Meteo Weather** | `OpenMeteoWeatherService` | 5s HTTP timeout fallback | `LIVE / HEALTHY` | Live temperature and visibility ingestion. |

---

## 3. Distributed Saga Monitor & Manual Intervention Desk

### 3.1 Saga Lifecycle State Tracking
The saga execution worker (`SagaExecutionWorker`) continuously tracks active sagas in PostgreSQL:
- **`RUNNING`:** Sub-process dispatched to Playwright / GDS engine.
- **`PENDING`:** Waiting for human operator approval or 2FA OTP verification.
- **`COMPLETED`:** All saga steps succeeded, PNR verified, and payment settled.
- **`FAILED`:** Retries exhausted without external commitment.
- **`RECONCILIATION_REQUIRED`:** Provider timeout occurred after payment pre-authorization; awaiting background polling to determine ticket issuance status before releasing hold.
- **`MANUAL_INTERVENTION`:** Ambiguous error (e.g. HTTP 409 seat contention); routed to human desk.

### 3.2 Manual Intervention Desk Queue
Cases routed to the Ticketing Desk Manual Queue expose:
- **Case ID & Passenger Name:** E.g. `CASE-409-CONCURRENCY (Vikram Malhotra)`
- **Disruption Reason:** Detailed GDS error string (`HTTP 409 Conflict: Selected fare class sold out during checkout`).
- **Queue Age:** Duration since escalation (e.g. `12 minutes ago`).
- **Next Safe Action:** Explicit operator instruction (`Inspect airline portal for alternative seat availability before triggering retry`).
