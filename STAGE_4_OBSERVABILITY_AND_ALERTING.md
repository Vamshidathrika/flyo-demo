# FLYO — STAGE 4 OBSERVABILITY, METRICS & ALERTING SPECIFICATION
## Distributed Tracing, Micrometer Actuator Metrics & Enterprise SLA Alerting

**Classification:** Flyo Site Reliability Engineering (SRE) Specification  
**Version:** 4.0.0-PROD-STAGE4  
**Date:** November 2026  
**Audited Location:** `flyo-rescue-demo/backend/src/main/resources/application.properties` & Actuator Endpoints

---

## 1. Observability Architecture

Flyo provides full three-pillar observability (Metrics, Traces, Structured Logs) across all distributed operations:

```
                      Flyo Request / Event
                                │
                 [Distributed Correlation ID]
                                │
         ┌──────────────────────┼──────────────────────┐
         ▼                      ▼                      ▼
  [Micrometer Metrics]    [Structured Logs]     [Audit Trail & Outbox]
  - Prometheus /actuator  - Logback JSON MDC    - StateTransition Table
  - P99 Saga Latency      - Tenant, Correlation - SagaStepLog Table
  - Outbox Lag / Queue    - Causation ID        - Immutable Audit Log
```

---

## 2. Micrometer & Prometheus Metric Endpoints

Actuator endpoint exposed at `/actuator/prometheus` (and `/actuator/health`). Key custom operational metrics include:

| Metric Name | Type | Description | Target SLA Threshold |
| :--- | :--- | :--- | :--- |
| `flyo.rescue.saga.duration` | Histogram / Timer | End-to-end saga execution time per step | $P99 < 8.0\text{s}$ |
| `flyo.rescue.outbox.backlog` | Gauge | Number of unprocessed events in outbox queue | $< 50\text{ events}$ |
| `flyo.rescue.outbox.oldest_event_age_seconds`| Gauge | Age of oldest unconsumed outbox event | $< 15\text{s}$ |
| `flyo.rescue.provider.latency` | Timer | Outbound API call latency per provider | $P95 < 2.5\text{s}$ |
| `flyo.rescue.provider.errors` | Counter | External API failure count partitioned by provider | $< 0.5\%\text{ of calls}$ |
| `flyo.rescue.approval.ttl_expired`| Counter | Cases where human approval expired ($> 30\text{m}$) | Monitored |
| `flyo.rescue.ticket_verification.failed`| Counter| Electronic ticket validation failures | Alerts desk |

---

## 3. Distributed Tracing & Causal Context

Flyo propagates three identifiers across all threads, saga workers, and external webhooks:

1. **`correlation_id`:** Originates at the initial disruption alert or client API call (e.g. `corr_717dd07e-...`). Preserved across outbox events, saga steps, and database entities.
2. **`causation_id`:** Identifies the immediate parent command or event that caused this transition (e.g. `CMD_APPROVE_OPTION` caused `SAGA_INITIATED`).
3. **`tenant_id`:** Injected into Logback MDC to prevent cross-tenant log pollution.

---

## 4. Production Alerting Rules (Prometheus / Alertmanager)

```yaml
groups:
  - name: flyo-operational-alerts
    rules:
      - alert: FlyoOutboxLagHigh
        expr: flyo_rescue_outbox_backlog > 100
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Transactional Outbox backlog exceeds 100 events"
          description: "Outbox dispatcher may be blocked or database connection pool saturated."

      - alert: FlyoSagaManualInterventionSpike
        expr: rate(flyo_rescue_ticket_verification_failed_total[5m]) > 0.05
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "E-ticket validation failures spike"
          description: "Carrier host issuing reservations without valid open coupons. Manual ticketing desk required."

      - alert: FlyoProviderDown
        expr: rate(flyo_rescue_provider_errors_total[5m]) / rate(flyo_rescue_provider_latency_count[5m]) > 0.20
        for: 3m
        labels:
          severity: critical
        annotations:
          summary: "Provider error rate exceeds 20%"
          description: "Provider adapter {{ $labels.provider }} is failing. Circuit breaker will engage."
```
