# STAGE 6 — OBSERVABILITY, METRICS & DISTRIBUTED TRACING

## 1. Executive Summary
A mission-critical airline disruption platform cannot rely on raw console logs or unmonitored background jobs. Stage 6 integrated end-to-end production observability into Flyo, featuring SLF4J MDC correlation tracking across asynchronous boundaries, OpenTelemetry-compatible tracing headers, Prometheus metric instrumentation via Micrometer, structured health/readiness endpoints, and error classification.

## 2. Distributed Tracing & MDC Correlation
- **Correlation Filter (`CorrelationMdcFilter.java`)**:
  - Inspects incoming HTTP requests for `X-Correlation-ID` and `X-Trace-ID` headers.
  - Automatically generates RFC 4122 UUID identifiers if headers are absent.
  - Populates SLF4J MDC keys: `correlationId`, `traceId`, `tenantId`.
  - Echoes correlation headers back in HTTP response headers for end-to-end client tracking.
- **Asynchronous Task Propagation**: MDC context is propagated into Saga workers and scheduled executor threads to ensure unbroken trace continuity across distributed saga steps.

## 3. Prometheus Domain Metrics (`FlyoMetricsService.java`)
Flyo exposes production operational metrics at `/actuator/prometheus`:
- `flyo.provider.latency`: Timer measuring external airline and payment API latencies tagged by `provider` (e.g. `amadeus`, `aviationstack`, `open-meteo`, `stripe`) and `endpoint`.
- `flyo.booking.failures`: Counter tracking rejected or failed bookings tagged by `provider`, `failure_type`, and `carrier`.
- `flyo.saga.recovery`: Counter tracking worker crash recoveries and expired lease redeliveries tagged by `recovery_type`.
- `flyo.payment.discrepancies`: Counter recording reconciliation mismatches, orphan authorizations, and capture discrepancies.
- `flyo.tenant.operations`: Counter tracking per-tenant operational throughput tagged by `tenant_id` and `operation`.

## 4. Test Verification Evidence (`Stage6ObservabilityTest.java`)
- `testCorrelationIdInMdcAndResponseHeaders()`: Verified `X-Correlation-ID` extraction, injection into MDC, and propagation in response headers.
- `testPrometheusEndpointExposesCustomFlyoMetrics()`: Polled `/actuator/prometheus` and verified custom Flyo counters and timers are actively registered and formatted for Prometheus scraping.
- `testProviderLatencyMetricRecorded()`: Verified `FlyoMetricsService.recordProviderLatency` increments metrics accurately.
- `testHealthAndReadinessEndpoints()`: Polled `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` and verified status `UP`.

**Result: 4/4 Tests Passed Cleanly.**
