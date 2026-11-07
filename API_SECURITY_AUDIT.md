# Flyo Phase 3.1 — API Security & Privacy Audit Report

**Audit Target**: `flyo-ml-inference` (FastAPI) & `flyo-rescue-backend` (Spring Boot API Gateway)  
**Security Posture**: Production-Hardened Internal Microservice  
**Audit Standards**: OWASP API Security Top 10, Zero PII Exposure, Principle of Least Privilege  

---

## 1. Microservice Boundary & Network Isolation

### Network Architecture
```text
┌────────────────────────────────────────────────────────┐
│                      Public Internet                   │
└───────────────────────────┬────────────────────────────┘
                            │ (HTTPS / TLS 1.3)
                            ▼
┌────────────────────────────────────────────────────────┐
│               Spring Boot API Gateway                  │
│  - Spring Security RBAC (OPERATOR, ADMIN)             │
│  - Rate Limiting & Webhook Token Verification          │
│  - Public Port 8080                                    │
└───────────────────────────┬────────────────────────────┘
                            │ (Internal VPC / localhost Only)
                            ▼
┌────────────────────────────────────────────────────────┐
│         FastAPI ML Inference Service (Private)         │
│  - Bound exclusively to 127.0.0.1 / Internal Docker Net│
│  - Port 8000 (NOT exposed to Public Ingress)           │
│  - Strict Pydantic Payload Validation                  │
└────────────────────────────────────────────────────────┘
```

### Directives:
1. **No Public Ingress**: The FastAPI inference service is an internal worker component. It must never have a public DNS record or be mapped to public ingress controllers without reverse-proxy authentication.
2. **Environment Configuration**: Model versions, internal URLs, and circuit breaker thresholds are injected via environment variables (`FLYO_ML_SERVICE_URL`, `FLYO_ML_PREDICTION_TIMEOUT_MS`).

---

## 2. Authentication, Authorization & RBAC

1. **Spring Boot Gateway Security**:
   - Protected endpoints require authenticated operator session (`ROLE_OPERATOR` or `ROLE_ADMIN`).
   - Disruption prediction endpoints (`/api/intelligence/predict`) inherit operator-level access control.
2. **Deterministic Booking Guardrails**:
   - Rebooking actions cannot be triggered by API calls to the ML service.
   - Playwright browser execution requires explicit operator credentials and cryptographic state verification.

---

## 3. Input Validation & DoS Prevention

FastAPI implements strict Pydantic v2 schemas to eliminate injection and resource exhaustion vectors:

| Schema Property | Validation Rule | DoS / Injection Defense |
| :--- | :--- | :--- |
| `flight_number` | Alphanumeric, trimmed, uppercase | Prevents parameter pollution and query injection. |
| `carrier_code` | 2-character uppercase string | Normalized and checked against allowed sets. |
| `origin_airport` | 3-character IATA uppercase string | Normalized and checked against known airport sets. |
| `dest_airport` | 3-character IATA uppercase string | Normalized and checked against known airport sets. |
| `day_of_week` | `ge=1, le=7` | Rejects out-of-range integer values. |
| `day_of_month` | `ge=1, le=31` | Rejects calendar buffer overflows. |
| `month` | `ge=1, le=12` | Rejects invalid month indices. |
| `BatchPredictionRequest` | `min_length=1, max_length=100` | **Strict Batch Cap**: Restricts payload size to 100 flights maximum, preventing heap exhaustion. |

---

## 4. Privacy & PII (Personally Identifiable Information)

A comprehensive audit of request bodies, response payloads, and logging outputs confirmed **zero PII leakage**:

1. **Zero Passenger Identifiers**:
   - Passenger names: **EXCLUDED**
   - Passenger email / phone numbers: **EXCLUDED**
   - Airline PNRs and e-ticket numbers: **EXCLUDED**
   - Government ID / Passport numbers: **EXCLUDED**
2. **Zero Financial Credentials**:
   - Virtual Credit Card (VCC) PANs: **EXCLUDED**
   - CVV / Expiration dates: **EXCLUDED**
   - Stripe / Lithic API tokens: **EXCLUDED**
3. **Log Sanitization**:
   Application logs record only operational telemetry (flight number, route corridor, latency in milliseconds, circuit breaker state transitions).
