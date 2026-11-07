# Flyo P0 Security and Payment Remediation Plan

**Document Status:** Approved Engineering Remediation Plan  
**Role:** Principal Security Architect & Engineering Director  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Threat Model & Immediate Production Blockers

The forensic audit confirmed that the current Flyo prototype cannot be deployed into a commercial enterprise network due to two catastrophic P0 vulnerabilities:
1. **Unrestricted Anonymous Access:** Consequential REST endpoints (`/api/cases/{id}/approve`, `/api/bookings/rebook`, `/api/payments/issue-card`) are marked `permitAll()` in [`SecurityConfig.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/SecurityConfig.java#L50-L60). Any anonymous client on the internet can trigger cancellations, rebook flights, and issue virtual credit cards.
2. **PCI-DSS Violation in Cardholder Storage:** In [`V1__initial_production_schema.sql`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/resources/db/migration/V1__initial_production_schema.sql#L72-L85), the `virtual_cards` table explicitly stores plaintext Primary Account Numbers (PAN), expiration dates, and Card Verification Values (CVV). PCI-DSS v4.0 Requirement 3.2 strictly prohibits storing Sensitive Authentication Data (SAD) after authorization under any circumstance.

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                              REMEDIATED SECURITY TOPOLOGY                              │
 │                                                                                        │
 │  PUBLIC INTERNET (TLS 1.3 / mTLS)                                                      │
 │  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
 │  │ API Gateway / Reverse Proxy (WAF, Rate Limiting, CORS, DDoS Mitigation)          │  │
 │  └───────────────────────────┬──────────────────────────────────────────────────────┘  │
 │                              │                                                         │
 │       ┌──────────────────────┴──────────────────────┐                                  │
 │       ▼                                             ▼                                  │
 │  ┌─────────────────────────┐               ┌─────────────────────────┐                 │
 │  │ Public Ingress Webhooks │               │ Authenticated REST APIs │                 │
 │  │ ─ Meta Signature Verify │               │ ─ JWT Bearer Token Auth │                 │
 │  │ ─ Webhook IP Allowlist  │               │ ─ Tenant Context Filter │                 │
 │  │ ─ Strict Replay Nonce   │               │ ─ RBAC Role Enforcement │                 │
 │  └────────────┬────────────┘               └────────────┬────────────┘                 │
 │               │                                         │                              │
 │               ▼                                         ▼                              │
 │  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
 │  │ Spring Security Filter Chain (Fails Closed: .anyRequest().authenticated())       │  │
 │  └──────────────────────────────────────────────────────────────────────────────────┘  │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Spring Security Architecture Remediation

### 2.1. Strict Fail-Closed Filter Chain
Replace the permissive rule block in `SecurityConfig.java` with a zero-trust, fail-closed policy:

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
    http
        .csrf(csrf -> csrf.disable()) // Stateless APIs using cryptographically signed JWTs
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(headers -> headers
            .frameOptions(frame -> frame.deny()) // Block clickjacking completely
            .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; frame-ancestors 'none'"))
            .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
        )
        .authorizeHttpRequests(auth -> auth
            // 1. Strictly Unauthenticated Public Ingress
            .requestMatchers(HttpMethod.POST, "/api/whatsapp/webhook").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/whatsapp/webhook").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh").permitAll()
            .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info").permitAll()

            // 2. Client Integration Gateway Dedicated Service Ingress
            .requestMatchers("/api/gateway/**").hasAuthority("SCOPE_gateway:ingest")

            // 3. Consequential Disruption Case Management (Role-Gated)
            .requestMatchers(HttpMethod.GET, "/api/cases/**").hasAnyRole("OPERATOR", "SUPERVISOR", "ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/cases/{id}/approve").hasAnyRole("SUPERVISOR", "ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/cases/{id}/reject").hasAnyRole("SUPERVISOR", "ADMIN")

            // 4. Financial & Payment Issuance (Strict Multi-Role Gate)
            .requestMatchers("/api/payments/**", "/api/settlement/**").hasRole("ADMIN")

            // 5. Booking Execution (Role-Gated)
            .requestMatchers(HttpMethod.POST, "/api/bookings/rebook").hasAnyRole("SUPERVISOR", "ADMIN")

            // 6. Actuator & Metrics Hardening
            .requestMatchers("/actuator/prometheus", "/actuator/metrics").hasRole("ADMIN")

            // 7. Complete Fail-Closed Default
            .anyRequest().authenticated()
        )
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
}
```

### 2.2. JWT Validation & Multi-Tenant Context Filter
Every enterprise request must present an `Authorization: Bearer <JWT>` signed by the enterprise identity provider (Keycloak, Okta, Azure AD, or Flyo Auth Server).
* A `JwtAuthenticationFilter` validates token signature, expiration, and extracts:
  - `sub`: Operator user ID (`usr_98124`).
  - `roles`: `["ROLE_OPERATOR", "ROLE_SUPERVISOR"]`.
  - `tenant_id`: Enterprise organization ID (`TENANT-FCM-TRAVEL`).
* The `TenantContextFilter` sets a thread-local variable:
  ```java
  TenantContext.setTenantId(claims.get("tenant_id", String.class));
  ```
* Every database query automatically executes within this tenant boundary using PostgreSQL Row Level Security (RLS).

### 2.3. Debug & Simulation Endpoint Removal
In production profile (`application-prod.properties`):
1. **Disable H2 Console:** Force `spring.h2.console.enabled=false`.
2. **Remove Mock Portals:** `/mock-airline/**` routes must be isolated under a separate `@Profile("dev")` controller and completely unmapped in production.
3. **Isolate Simulation Triggers:** `/api/cases/simulate` and `/api/alerts/poll` must be restricted to internal scheduled jobs or `ROLE_ADMIN` only.

---

## 3. Payment & PCI-DSS Compliance Remediation

### 3.1. Forensic Evidence: Zero Live Cardholder Data
* **Investigation Result:** Inspection of existing databases and tests confirms that **no real credit card numbers or CVVs have ever been ingested or stored**. The existing `virtual_cards` rows contain exclusively synthetic test strings (`411111******1111`, `CVV 891`) created by [`FlyoDataSeeder.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/FlyoDataSeeder.java).
* **Action:** Remediate the schema immediately before connecting to any real corporate card program.

### 3.2. Flyway Migration: `V4__pci_remediation_and_tokenization.sql`

```sql
-- Flyo Disruption Recovery Platform: Schema Migration V4
-- Compliance Target: PCI-DSS v4.0 Requirement 3.2 & SAQ A/SAQ A-EP Boundary Isolation

-- 1. Drop Prohibited Sensitive Authentication Data (SAD)
ALTER TABLE virtual_cards DROP COLUMN IF EXISTS cvv;

-- 2. Deprecate Plaintext Card Numbers
ALTER TABLE virtual_cards DROP COLUMN IF EXISTS card_number;

-- 3. Introduce Issuer Tokenization & Display Metadata
ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS card_token_id VARCHAR(128) NOT NULL;
ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS last_four VARCHAR(4) NOT NULL;
ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS card_brand VARCHAR(32) DEFAULT 'Mastercard';
ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS expiration_month_year VARCHAR(7) NOT NULL;
ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS spending_limit_cents INT NOT NULL;
ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS merchant_category_code VARCHAR(4) DEFAULT '3000';

-- 4. Index on Secure Issuer Token
CREATE UNIQUE INDEX IF NOT EXISTS idx_virtual_cards_token ON virtual_cards(card_token_id);
```

### 3.3. PCI-DSS Assessment Boundary Strategy
Flyo will **never** become an in-scope Cardholder Data Environment (CDE).
* Flyo delegates payment card issuance and transaction clearing entirely to licensed Payment Service Providers (PSPs) or commercial corporate banking partners (e.g., Stripe Issuing, Lithic, or HDFC/ICICI Corporate Virtual Card APIs).
* When a rebooking requires payment, Flyo calls the provider's tokenized API to generate a single-use virtual card token.
* The provider's PCI-compliant proxy injects the payment credentials directly into the airline or GDS payment gateway. Flyo servers only store the non-sensitive token reference (`tok_issuing_98124`) and masked display digits (`5424`).
* This architecture places Flyo squarely under **PCI-DSS SAQ A**, drastically reducing audit scope and liability.
