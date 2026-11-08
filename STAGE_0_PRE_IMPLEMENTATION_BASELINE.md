# Stage 0 Pre-Implementation Baseline & Forensic Security Audit

**Document Status:** Approved Pre-Implementation Baseline  
**Role:** Principal Application Security Engineer & Forward Deployment Engineer  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  
**Execution Phase:** Stage 0 Security Containment  

---

## 1. Environment & Codebase Baseline

### 1.1. Git & Working Tree State
* **Current Branch:** `main` (synchronized with `origin/main`).
* **Active Components:**
  - Spring Boot 3.3.4 Java backend (`flyo-rescue-demo/backend`)
  - FastAPI ML inference & monitoring service (`ml/service`)
  - Playwright browser automation worker (`flyo-rescue-demo/agent`)
  - Static operator HTML/JS console (`flyo-rescue-demo/backend/src/main/resources/static`)
* **Current Flyway Migration Version:** `V3__aviation_intelligence_and_risk.sql` applied.
* **Verified Runtime Test Baseline:**
  - Spring Boot Backend: Exactly **116 unit & integration tests run across 28 test classes** (0 failures, 0 errors, 0 skipped via Maven Surefire).
  - Python FastAPI & ML Suite: Exactly **26 unit tests run across 4 test files** (0 failures, 0 errors via `unittest`).

---

## 2. Forensic Pre-Implementation Inspection

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                               STAGE 0 PRE-IMPLEMENTATION AUDIT                                   │
├───────────────────────┬─────────────────────────────┬─────────────────────────────┬──────────────┤
│ Component             │ Inspected Source File       │ Current Observed Behavior   │ Risk Level   │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Security Filter Chain │ `SecurityConfig.java`       │ Consequential REST APIs     │ CRITICAL P0  │
│                       │                             │ marked `.permitAll()`       │ (Anon Auth)  │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Card Data Persistence │ `VirtualCard.java`          │ Persistent schema stores    │ CRITICAL P0  │
│                       │ `V1__initial_production...` │ `cardNumber`, `expiry`, and │ (PCI-DSS     │
│                       │ `SandboxPaymentAdapter.java`│ `cvv` in database table     │ Req 3.2 SAD) │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Production Endpoints  │ `MockAirlineController.java`│ Mock airline HTML routes    │ HIGH P0      │
│                       │ `application-prod.prop`     │ mapped in prod profile      │ (Attack Surf)│
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Database Console      │ `application.properties`    │ `h2.console.enabled=true`   │ HIGH P0      │
│                       │ `SecurityConfig.java`       │ `/h2-console/**` permitAll  │ (DB Exposure)│
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Default Secrets       │ `WhatsAppConfig.java`       │ Hardcoded fallback secrets  │ HIGH P0      │
│                       │ `application-prod.prop`     │ permit boot without env     │ (Credential) │
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ FastAPI Monitoring    │ `ml/service/main.py`        │ Header check exists but has │ MEDIUM P1    │
│                       │                             │ default mock fallback key   │ (Auth Bypass)│
├───────────────────────┼─────────────────────────────┼─────────────────────────────┼──────────────┤
│ Tenant Isolation      │ Core Schema (V1-V3)         │ Zero `tenant_id` columns;   │ ARCHITECTURAL│
│                       │                             │ unpartitioned single tenant │ (Multi-Ten)  │
└───────────────────────┴─────────────────────────────┴─────────────────────────────┴──────────────┘
```

---

## 3. Detailed Component-by-Component Baseline

### 3.1. Spring Security Architecture
* **Framework Version:** Spring Security 6.3.3 (via Spring Boot 3.3.4 starter parent).
* **Current Filter Configuration:**
  In [`SecurityConfig.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/SecurityConfig.java#L39-L62):
  ```java
  .requestMatchers(
      "/", "/index.html", "/style.css", "/app.js", "/favicon.ico",
      "/screenshots/**", "/mock-airline/**", "/h2-console/**",
      "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
  ).permitAll()
  .requestMatchers(
      "/api/whatsapp/**", "/api/alerts/**", "/api/ingest/**",
      "/api/bookings/**", "/api/cases/**", "/api/payments/**",
      "/api/settlement/**", "/api/weather/**", "/api/intelligence/**"
  ).permitAll()
  .anyRequest().authenticated()
  ```
* **Vulnerability:** Consequential mutations (`POST /api/cases/{id}/approve`, `POST /api/cases/{id}/reject`, `POST /api/payments/issue-card`, `POST /api/bookings/rebook`) require zero authentication.
* **Existing Identity Entity:** [`OperatorAccount.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/model/OperatorAccount.java) and [`OperatorAccountRepository.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/repository/OperatorAccountRepository.java) exist with BCrypt password hashing, seeded with `operator`, `admin`, and `auditor` accounts in [`FlyoDataSeeder.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/FlyoDataSeeder.java). However, there is **no login endpoint, no JWT filter, and no token generator**.

### 3.2. Payment & Card Data Persistence
* **Schema Definition:**
  In [`V1__initial_production_schema.sql:72-85`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/resources/db/migration/V1__initial_production_schema.sql#L72-L85):
  ```sql
  CREATE TABLE IF NOT EXISTS virtual_cards (
      id VARCHAR(36) PRIMARY KEY,
      case_id VARCHAR(36) NOT NULL,
      option_id VARCHAR(36) NOT NULL,
      card_number VARCHAR(32) NOT NULL,
      card_holder VARCHAR(128),
      expiry VARCHAR(16) NOT NULL,
      cvv VARCHAR(32) NOT NULL,
      amount INT NOT NULL,
      currency VARCHAR(8) NOT NULL DEFAULT 'INR',
      status VARCHAR(32) NOT NULL DEFAULT 'ISSUED',
      merchant_category VARCHAR(64) DEFAULT '3000 - Airlines',
      issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
  );
  ```
* **Persistent Entity:** [`VirtualCard.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/model/VirtualCard.java) defines persistent properties `cardNumber`, `expiry`, `cvv`.
* **Adapter Generation:** [`SandboxPaymentAdapter.java:60-80`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/payment/SandboxPaymentAdapter.java#L60-L80) generates synthetic 16-digit card numbers and 3-digit CVVs, saving them directly into the SQL database.
* **Forensic Data Audit:** An audit of existing PostgreSQL schemas, Flyway seeders, and application logs confirms that **no real customer cardholder data or live CVVs exist in the repository**. All records are synthetic mock values (`453298...`, `CVV 891`).
* **PCI-DSS Violation:** Storing any CVV (even synthetic in a test environment mirroring production) violates PCI-DSS Requirement 3.2.

### 3.3. Endpoint Inventory & Mappings

| Endpoint Route | HTTP Method | Current Access | Intended Production Access |
| :--- | :--- | :--- | :--- |
| `/api/auth/login` | `POST` | *Does not exist* | **PUBLIC** (Throttled login) |
| `/api/cases/**` | `GET` | `permitAll()` | **ROLE_OPERATOR, SUPERVISOR, ADMIN** |
| `/api/cases/{id}/approve` | `POST` | `permitAll()` | **ROLE_SUPERVISOR, ADMIN** |
| `/api/cases/{id}/reject` | `POST` | `permitAll()` | **ROLE_SUPERVISOR, ADMIN** |
| `/api/bookings/rebook` | `POST` | `permitAll()` | **ROLE_SUPERVISOR, ADMIN** |
| `/api/payments/**` | `ALL` | `permitAll()` | **ROLE_ADMIN** |
| `/api/whatsapp/webhook` | `GET, POST` | `permitAll()` | **PUBLIC** (Meta HMAC-SHA256 signature verified) |
| `/api/alerts/poll` | `POST` | `permitAll()` | **ROLE_ADMIN, SERVICE** |
| `/mock-airline/**` | `ALL` | `permitAll()` | **DISABLED IN PRODUCTION** (`@Profile("dev")`) |
| `/h2-console/**` | `ALL` | `permitAll()` | **DISABLED IN PRODUCTION** (`@Profile("dev")`) |
| `/actuator/health` | `GET` | `permitAll()` | **PUBLIC** (Liveness/Readiness probe) |
| `/actuator/**` (metrics) | `GET` | `permitAll()` | **ROLE_ADMIN** |

### 3.4. Tenant & Object-Level Authorization Baseline
* **Observation:** The current relational database tables (`bookings`, `rescue_cases`, `rebook_options`, `virtual_cards`) contain **no `tenant_id` or `organization_id` foreign key columns**.
* **Impact:** Multi-tenant row-level partitioning does not exist in the current application.
* **Stage 0 Policy:** In strict adherence to the mandate ("Do not fabricate a tenant migration as part of Stage 0"), tenant isolation is formally registered as a **Stage 1 architectural requirement**. In Stage 0, we enforce role-based access control (RBAC) and object-level ID verification based on operator username/roles.

---

## 4. Stage 0 Remediation Specification Summary

1. **SEC-01 (Authentication & RBAC):**
   - Add `io.jsonwebtoken:jjwt-api:0.12.5`, `jjwt-impl:0.12.5`, `jjwt-jackson:0.12.5` to `pom.xml`.
   - Implement `JwtTokenProvider.java` validating signature (HMAC-SHA256 allowlisted), issuer (`flyo-auth`), subject, expiration, and roles.
   - Implement `JwtAuthenticationFilter.java` intercepting requests with `Authorization: Bearer <token>`.
   - Implement `AuthController.java` exposing `POST /api/auth/login` validating username/password against `OperatorAccountRepository` with BCrypt.
   - Rewrite `SecurityConfig.java` to enforce fail-closed `.anyRequest().authenticated()`.
2. **SEC-02 (PCI Remediation):**
   - Author Flyway migration `V4__pci_remediation.sql` dropping `cvv`, dropping plaintext `card_number`, adding `card_token_id` and `last_four`.
   - Refactor `VirtualCard.java` removing `cvv` and `cardNumber`; add `cardTokenId` and `lastFour`.
   - Update `SandboxPaymentAdapter.java` to issue tokenized references (`card_token_id = "tok_sandbox_..."`), zero CVVs in DB.
   - Update `PaymentController.java` ensuring DTOs expose only masked card numbers and zero CVVs.
3. **SEC-03 (PII & Leakage Controls):**
   - Implement `LoggingMaskingUtil.java` or log pattern filters to sanitize passenger phone numbers, names, and card tokens.
   - Ensure `GlobalExceptionHandler.java` never leaks internal stack traces or database errors in API error responses.
4. **SEC-04 (Production Profile Containment):**
   - Add `@Profile("dev")` to `MockAirlineController.java`.
   - Explicitly disable H2 console in `application-prod.properties` (`spring.h2.console.enabled=false`).
   - Restrict Actuator exposure to `health` only.
5. **SEC-05 (Secret Startup Enforcement):**
   - Implement a Spring `ApplicationRunner` / `@PostConstruct` validating that production profile rejects missing or default secrets (`flyo_secure_password_2026`, `mock_access_token`).
6. **SEC-06 (FastAPI Monitoring Auth):**
   - Update `ml/service/main.py` to enforce constant-time comparison (`secrets.compare_digest`) on `X-Flyo-Monitoring-Key`, rejecting empty or placeholder keys in production.
7. **SEC-07 (API Abuse Protection):**
   - Add in-memory login attempt throttling (Bucket4j or ConcurrentHashMap sliding window) on `/api/auth/login`.
   - Configure restrictive CORS rejecting wildcard origins when credentials are supported.
