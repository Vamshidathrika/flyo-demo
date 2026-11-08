# FLYO — DATABASE & BACKEND ARCHITECTURE AUDIT

**Date:** November 6, 2026  
**Auditor:** Principal Database Architect & Senior Backend Engineer  
**Scope:** Relational Schema (PostgreSQL 15 / H2), Flyway Migrations, Transaction Boundaries, Spring Boot 3.3.4 Backend APIs, FastAPI Endpoints  
**Operating Mode:** STRICT AUDIT ONLY — Zero database migrations, schema edits, or API changes performed.

---

## 1. Executive Summary & Verdict

### **VERDICT: ROBUST FOUNDATIONS COMPROMISED BY CRITICAL COMPLIANCE AND SECURITY GAPS**

The database and backend services demonstrate strong classical software engineering:
- Flyway migrations are cleanly sequenced (`V1`, `V2`, `V3`).
- Foreign keys and indexes are properly declared.
- Optimistic locking (`@Version`) successfully prevents race conditions during concurrent case approvals.
- Transactional service boundaries (`@Transactional`) ensure atomic rollbacks.

However, the architecture contains **two severe, disqualifying flaws**:
1. **Critical PCI-DSS Failure:** The `virtual_cards` table stores full Cardholder Primary Account Numbers (PANs), Expiration Dates, and Card Verification Values (CVVs) in **unencrypted plaintext**. Storing card security codes (CVV) post-authorization is strictly prohibited under PCI-DSS Requirement 3.2.
2. **Abandoned Outbox Architecture:** The transactional outbox table (`outbox_events`) is populated by business events, but **there is zero background worker, cron poller, or message broker** to dequeue and deliver these events.

---

## 2. Relational Schema & Flyway Migration Audit

The schema is defined across three versioned Flyway migrations in `src/main/resources/db/migration/`:

### Migration V1: `V1__initial_production_schema.sql` (160 lines)
Defines 10 core tables:
1. `bookings`: Master itinerary table (`id`, `passenger_name`, `pnr`, `route`, `flight`, `date_time`, `onward_flight`, `hotel_note`, `status`).
2. `rescue_cases`: Disruption recovery state table (`id`, `booking_id`, `state`, `cancelled_segment`, `flight`, `impact`, `missed_connection`, `approved_option_id`, `approved_by`, `new_pnr`, `version`).
   - *Integrity:* Declares `FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE CASCADE`.
   - *Concurrency:* Includes `version BIGINT DEFAULT 0` mapped to JPA `@Version`.
3. `rebook_options`: Ranked alternative flights (`id`, `case_id`, `flight`, `route`, `departure`, `arrival`, `connection_minutes`, `extra_cost`, `rank`, `saves_connection`).
4. `audit_events`: Append-only audit trail (`id`, `case_id`, `timestamp`, `event`, `actor`, `detail`).
5. `virtual_cards`: Corporate credit card repository (**CRITICAL DEFICIENCY**).
6. `payment_transactions`: Financial settlement ledger (`id`, `case_id`, `virtual_card_id`, `amount`, `vendor`, `status`).
7. `simulated_messages`: Customer communication log (`case_id`, `type`, `recipient`, `text`).
8. `state_transitions`: Formal state machine audit trail (`case_id`, `from_state`, `to_state`, `actor`, `reason`).
9. `outbox_events`: Transactional outbox ledger (`id`, `aggregate_type`, `aggregate_id`, `payload`, `status`).
10. `operator_accounts`: User credential table (`id`, `username`, `password_hash`, `role`).

### Migration V2: `V2__passenger_rescue_workflow.sql` (27 lines)
Extends schema for passenger self-service:
- Adds `passenger_phone`, `passenger_decision`, `selected_option_id`, `passenger_confirmed_amount` to `rescue_cases`.
- Creates `passenger_otp_challenges` (`case_id`, `passenger_phone`, `otp_hash`, `attempts`, `expires_at`, `verified`).

### Migration V3: `V3__aviation_intelligence_and_risk.sql` (9 lines)
Adds risk and meteorological columns:
- Adds `risk_score INT DEFAULT 15`, `risk_level VARCHAR(16)`, `risk_factors VARCHAR(500)` to `rebook_options`.
- Adds `reconciled_confidence INT DEFAULT 95`, `disruption_evidence TEXT`, `weather_summary VARCHAR(500)` to `rescue_cases`.

### Schema Strengths:
- **Clean ANSI SQL:** Fully compatible across both development (H2 in PostgreSQL mode) and production (real PostgreSQL 15/16).
- **Adequate Indexing:** Indexes defined on `idx_bookings_pnr`, `idx_bookings_flight`, `idx_rescue_cases_booking_id`, `idx_rebook_options_case_rank`, and `idx_audit_events_case_time`.

---

## 3. Critical Data Compliance Violations

```sql
-- V1__initial_production_schema.sql (Lines 76-89)
CREATE TABLE IF NOT EXISTS virtual_cards (
    id VARCHAR(36) PRIMARY KEY,
    case_id VARCHAR(36) NOT NULL,
    option_id VARCHAR(36) NOT NULL,
    card_number VARCHAR(32) NOT NULL,       -- CRITICAL: Plaintext PAN
    card_holder VARCHAR(128),
    expiry VARCHAR(16) NOT NULL,            -- CRITICAL: Plaintext Expiry
    cvv VARCHAR(32) NOT NULL,               -- FATAL: Plaintext CVV storage
    amount INT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'INR',
    status VARCHAR(32) NOT NULL DEFAULT 'ISSUED',
    merchant_category VARCHAR(64) DEFAULT '3000 - Airlines',
    issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 1. PCI-DSS Requirement 3.2 Violation (FATAL)
- **Standard:** *"Do not store sensitive authentication data (SAD) after authorization (even if encrypted). SAD includes full track data, card verification code or value (CVV/CVC), and PIN/PIN block."*
- **Current Flyo Code:** `virtual_cards.cvv` stores the 3-digit CVV (`891`) in plaintext in the database indefinitely.
- **Remediation:** Remove `cvv` column from the database entirely. If an automated API requires CVV for a one-time API call, it must be generated in memory, transmitted via TLS, and discarded immediately.

### 2. Unencrypted Primary Account Numbers (PAN)
- **Standard:** PCI-DSS Requirement 3.4 requires PANs to be rendered unreadable anywhere they are stored (using strong cryptography, truncation, or tokenization).
- **Current Flyo Code:** Full 16-digit card numbers (`4532...`) are stored in plain text.
- **Remediation:** Store only masked PAN (`4532-XXXX-XXXX-8821`) and token references provided by the payment issuer (Stripe Issuing / Marqeta).

### 3. PII Storage & Privacy Regulations (GDPR & India DPDP Act 2023)
- `bookings.passenger_name`, `bookings.passenger_phone`, and `bookings.hotel_note` are stored unencrypted without access control or cryptographic salting.
- There is no automated data retention or right-to-be-forgotten deletion mechanism.

---

## 4. Transaction Management & Concurrency Audit

### Optimistic Locking & Race Conditions
- In `RescueCase.java`, the entity declares:
  ```java
  @Version
  private Long version;
  ```
- **Concurrency Test Verification:** In `ConcurrencyAndLoadTest.java`, 10 concurrent threads attempt to approve conflicting options for Case `C1`. Exactly 1 thread succeeds (HTTP 200); 9 threads receive `OptimisticLockingFailureException` (HTTP 409 Conflict).
- **Verdict:** Optimistic concurrency control is **verified and working properly**.

### Transaction Boundaries & Atomicity
- All state-altering operations in `CaseService.java` and `RebookingOrchestrationService.java` are decorated with `@Transactional`.
- If Playwright rebooking fails, payment settlement is rolled back atomically, preventing financial leakage.

### The Dead Outbox Pattern
- `V1__initial_production_schema.sql` establishes `outbox_events` table.
- `OutboxService.java` provides `publishEvent()`, `getPendingEvents()`, and `markCompleted()`.
- **Audit Finding:** A comprehensive codebase search reveals **zero `@Scheduled` methods and zero message consumers** that read from `outbox_events`. Events written to this table accumulate forever without being published to any message bus.

---

## 5. Backend REST API Production Readiness

| Controller | Base Path | Endpoints | Input Validation | Auth & RBAC | Status |
| :--- | :--- | :-: | :---: | :---: | :---: |
| `CaseController` | `/api/cases` | 7 | Partial | **NONE (`permitAll`)** | **VULNERABLE** |
| `FlightAlertController` | `/api/alerts` | 3 | Basic | **NONE (`permitAll`)** | **VULNERABLE** |
| `IngestionController` | `/api/ingest` | 1 | Basic | **NONE (`permitAll`)** | **VULNERABLE** |
| `PaymentController` | `/api/payments` | 2 | None | **NONE (`permitAll`)** | **VULNERABLE** |
| `WhatsAppWebhookController`| `/api/whatsapp` | 8 | Signature checked on live Meta | **NONE (`permitAll`)** | **VULNERABLE** |
| `AviationIntelligenceController`| `/api/intelligence` | 4 | None | **NONE (`permitAll`)** | **VULNERABLE** |
| `MockAirlineController` | `/mock-airline` | 4 | None | **NONE (`permitAll`)** | **DEBUG ROUTE** |
| `FastAPI Inference` | `:8000/predict` | 5 | Pydantic Schema | API Key on monitor only | **PARTIAL** |

### Critical Backend Deficiencies:
1. **Zero Input Sanitization:** String inputs (`passengerName`, `flight`, `route`) are not sanitized against XSS or path manipulation before persistence.
2. **Missing Rate Limiting:** No token bucket or Redis rate limiter protects endpoints against DDoS or API exhaustion.
3. **Missing Pagination:** `GET /api/cases` executes `findAll()`, loading the entire database into memory. Under real operations (10,000 cases), this will cause JVM OutOfMemoryError.
4. **Debug Controllers in Production:** `MockAirlineController.java` (`/mock-airline/toggle-break`) is compiled and deployed in the production JAR, allowing unauthorized external manipulation of service health.
