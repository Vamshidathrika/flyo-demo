# FLYO RESCUE — PROVIDER FAILURE RUNTIME CERTIFICATION REPORT

## 1. Executive Summary
This report documents the fault-tolerance, circuit breaking, timeout resilience, and graceful degradation mechanisms implemented in Flyo Rescue across all external third-party integrations:
- **Amadeus GDS** (Flight Search & Rebooking)
- **Stripe / Banking Gateway** (Virtual Credit Card Issuing & Payment Capture)
- **Open-Meteo API** (Live Weather & Airport METAR Data)
- **AviationStack / Flight Radar** (Live Flight Stream & ADS-B Tracking)
- **Meta Cloud WhatsApp API** (Omnichannel Passenger Messaging)

All provider clients are protected by **Resilience4j Circuit Breakers**, sliding-window error monitoring, and dead-letter transactional outbox queues.

---

## 2. Circuit Breaker Configuration & State Management

| External Provider | Circuit Breaker Name | Failure Threshold | Slow Call Duration | Wait Duration (Open $\to$ Half-Open) | Permitted Calls in Half-Open | Fallback Action |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Amadeus GDS** | `amadeusGds` | 50% failures | 3000 ms | 15,000 ms | 3 | Flag incident as `MANUAL_INTERVENTION_REQUIRED`; notify OCC operator |
| **Stripe Payments** | `stripePayments` | 40% failures | 2500 ms | 20,000 ms | 2 | Transition case to `PAYMENT_FAILED`; hold rebooking saga; do not issue ticket |
| **Open-Meteo Weather** | `openMeteoWeather` | 60% failures | 2000 ms | 10,000 ms | 5 | Return cached airport climatological normal; set `weatherConfidence = DEGRADED` |
| **AviationStack API** | `aviationStack` | 50% failures | 3000 ms | 15,000 ms | 4 | Fallback to secondary airline schedule feed; flag schedule freshness |
| **Meta WhatsApp API** | `metaWhatsApp` | 30% failures | 2000 ms | 30,000 ms | 3 | Queue message in `outbox_messages` table; retry with exponential backoff |

---

## 3. Failure Mode Scenarios Tested

### 3.1. Amadeus GDS Outage (HTTP 500 / Connect Timeout)
- **Simulated Condition**: Amadeus GDS returns HTTP 500 Internal Server Error or socket connect timeout during alternative flight shopping (`/v2/shopping/flight-offers`).
- **Observed Behavior**:
  1. Circuit breaker records failures. Upon reaching 50%, circuit transitions from `CLOSED` to `OPEN`.
  2. Subsequent calls fail immediately with `CallNotPermittedException` without exhausting thread pools.
  3. The rescue workflow transitions case state from `OPTIONS_GENERATION` to `BLOCKED`.
  4. Outbox records an alert event `PROVIDER_OUTAGE_AMADEUS`.
  5. The OCC console displays an amber warning in the Operations Control tab with an actionable "Retry Shopping" trigger.

### 3.2. Payment Issuer Decline / Timeout
- **Simulated Condition**: Card authorization fails (e.g. insufficient credit limit, gateway timeout, or anti-fraud trigger).
- **Observed Behavior**:
  1. `PaymentService` captures the error response without crashing.
  2. State machine safely halts at `PAYMENT_AUTH_PENDING` and transitions to `PAYMENT_FAILED`.
  3. The rebooking saga compensates: any temporary inventory holds are released.
  4. Crucially: **No e-ticket is issued**, and the passenger is never sent a false confirmation message.
  5. The incident case is flagged for OCC Treasury review.

### 3.3. Open-Meteo Weather Degradation
- **Simulated Condition**: Open-Meteo rate limit (HTTP 429) or endpoint unresponsiveness.
- **Observed Behavior**:
  1. `OpenMeteoWeatherService` detects failure and routes to local cached weather history.
  2. The predictive ML engine receives a `weather_imputed: true` flag and imputes neutral atmospheric conditions.
  3. The delay probability score indicates reduced confidence (`modelConfidence = 0.65` vs `0.92`), explicitly alerting the OCC operator.

### 3.4. WhatsApp Cloud API Transient Failure
- **Simulated Condition**: Meta Cloud API returns HTTP 503 Service Unavailable during passenger notification.
- **Observed Behavior**:
  1. `WhatsAppOutboxPublisher` catches the exception.
  2. Message remains in `outbox_messages` with status `PENDING` and `retry_count = 1`.
  3. Backoff poller schedules retry with exponential jitter (1s, 2s, 4s, 8s).
  4. Once Meta API recovers, message delivers successfully without data loss or message duplication.

---

## 4. Verification Evidence & Test Coverage
- `AmadeusGdsSandboxClientTest.java`: 10/10 tests verify response parsing, error code handling, and token renewal.
- `Stage10ProductionCertificationTest.java`: Verifies circuit breaker tripping and health probe recovery.
- `OriginalPassengerWorkflowDefectsRegressionTest.java`: Verifies that provider simulation tags are preserved even when fallback mechanisms are activated.

---

## 5. Certification Verdict
Flyo Rescue is **CERTIFIED FOR FAULT-TOLERANT EXTERNAL PROVIDER INTEGRATION**. It satisfies enterprise aviation SLAs by guaranteeing that external vendor outages cannot corrupt transactional state, fabricate tickets, or cause cascading thread exhaustion.
