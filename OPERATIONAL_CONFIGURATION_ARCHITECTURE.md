# FLYO RESCUE — OPERATIONAL CONFIGURATION ARCHITECTURE

## 1. Executive Summary & Architecture Contract

Flyo Rescue enforces a **Zero Hardcoded Operational Data** architecture contract across all tiers of the system (Backend, Machine Learning Inference Engine, Operator Console Frontend, and WhatsApp Concierge Interface). No operational variables—including airport minimum connect times (MCT), airline flight schedules, passenger profiles, tenant routing, payment card instruments, or rebooking rules—may exist as static literals, hardcoded dictionaries, or inline conditionals in application business logic.

All operational configurations are strictly decoupled into three authoritative layers:
1. **Dynamic Relational Configuration Storage (`airport_mct_rules`, `tenant_configs`, `operator_accounts`)**: Managed via schema-versioned database migrations and dynamic runtime caches with explicit audit provenance and administrative approval states.
2. **Environment & Profile Configuration (`application-*.properties`, 12-Factor Environment Variables)**: Controlled by active Spring / OS environment variables with strict fail-closed validation at startup.
3. **Live Operational State (`rescue_cases`, `flight_segments`, `rebook_options`, `payment_transactions`)**: Sourced on-demand from authoritative aviation APIs (Amadeus, AviationStack, Open-Meteo) or persisted relational entities.

---

## 2. Configuration Tier Hierarchy

```
+-----------------------------------------------------------------------------------+
|                        12-FACTOR ENVIRONMENT INJECTION                            |
|  - FLYO_DATABASE_URL / USER / PASSWORD                                            |
|  - FLYO_JWT_SECRET (>= 32 bytes high-entropy)                                     |
|  - AMADEUS_API_KEY / SECRET / OFFICE_ID                                            |
|  - WHATSAPP_META_ACCESS_TOKEN / APP_SECRET / OPERATOR_PHONE                       |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
|                       SPRING BOOT PROFILE ISOLATION                               |
|  [prod]   Strict validation, 0 demo seeds, fail-closed remote APIs, HTTPS, CSP     |
|  [demo]   Sandbox adapters enabled, UI simulator drawer, demo operator bypass     |
|  [test]   H2 / Postgres in-memory fixtures, idempotent transactional rollbacks    |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
|               DATABASE-DRIVEN OPERATIONAL RULES & PROVENANCE                      |
|  - Table: `airport_mct_rules`                                                      |
|    * Versioned regulatory connection times (Domestic->Domestic, Dom->Intl, etc.)  |
|    * Source authority metadata (DGCA, IATA SSIM, AAI AIP)                         |
|    * Approval status: APPROVED / PENDING_REVIEW / REJECTED                        |
|  - Table: `tenants` & `operator_accounts`                                         |
|    * Multi-tenant data segregation (tenant_default, tmc_corp_01)                  |
|    * Role-Based Access Control (ROLE_OPERATOR, ROLE_SUPERVISOR, ROLE_ADMIN)       |
+-----------------------------------------------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
|                   DYNAMIC RUNTIME REPOSITORIES & CACHING                          |
|  - `MctRulesService`: DB-backed query -> fall-through to manual review lock       |
|  - `DistributedRateLimiter`: Shared rate limits in `shared_rate_limits` table     |
|  - `CanonicalItineraryService`: Normalizes segments into `flight_segments` table   |
+-----------------------------------------------------------------------------------+
```

---

## 3. Dynamic Airport MCT Configuration Subsystem

### 3.1 Prior Hardcoded Anti-Pattern (Eliminated in Stage 13C)
Prior to Stage 13C, airport Minimum Connect Times were statically compiled into a Java `Map.of()` inside `MctRulesService.java`:
```java
// REJECTED HARDCODED PATTERN (Eliminated):
private static final Map<String, Integer> PUBLISHED_MCT_MATRIX = Map.of(
    "DEL", 120, "BOM", 120, "BLR", 90, "HYD", 75, "LHR", 90, "FRA", 90
);
```
**Fatal Flaws in Anti-Pattern**:
- Omitted connection-type granularity (Domestic-to-Domestic vs Domestic-to-International vs Terminal Change).
- Static values could not be updated during live air traffic management ground stops or fog procedures.
- Any unlisted airport (e.g. MAA, CCU, PNQ, DXB) defaulted to an arbitrary fabricated fallback.
- Zero provenance or regulatory audit trail.

### 3.2 Dynamic Database Schema (`airport_mct_rules`)
Implemented via Flyway Migration `V11__airport_mct_rules.sql`:
```sql
CREATE TABLE IF NOT EXISTS airport_mct_rules (
    id VARCHAR(64) PRIMARY KEY,
    airport_code VARCHAR(3) NOT NULL,
    connection_type VARCHAR(32) NOT NULL, -- D_TO_D, D_TO_I, I_TO_D, I_TO_I
    mct_minutes INT NOT NULL,
    source_authority VARCHAR(128) NOT NULL, -- e.g. "AAI AIP India ENR 1.4"
    rule_version VARCHAR(32) NOT NULL,      -- e.g. "v2026.1"
    approval_status VARCHAR(32) NOT NULL,   -- "APPROVED", "PENDING_REVIEW"
    requires_terminal_change BOOLEAN DEFAULT FALSE,
    terminal_change_mct_minutes INT DEFAULT NULL,
    effective_from TIMESTAMP NOT NULL,
    effective_to TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    notes TEXT
);

CREATE UNIQUE INDEX idx_airport_conn_type ON airport_mct_rules(airport_code, connection_type);
```

### 3.3 Authoritative Resolution Workflow (`MctRulesService.java`)
```java
@Transactional(readOnly = true)
public MctEvaluationResult evaluateConnection(String airportCode, String connType, int scheduledMinutes) {
    Optional<AirportMctRule> ruleOpt = mctRuleRepository.findByAirportCodeAndConnectionType(airportCode, connType);

    if (ruleOpt.isEmpty() || !"APPROVED".equalsIgnoreCase(ruleOpt.get().getApprovalStatus())) {
        log.warn("[MCT Engine] No approved MCT rule in database for airport {} (type: {}). Triggering Mandatory Manual Review.",
                airportCode, connType);
        return new MctEvaluationResult(
                airportCode,
                connType,
                scheduledMinutes,
                -1,
                false, // knownAirportMct = false
                true,  // requiresManualReview = true (SAFETY LOCK)
                false, // compliant = false
                "MCT_UNLISTED_REQUIRES_MANUAL_REVIEW",
                "NO_APPROVED_RULE"
        );
    }

    AirportMctRule rule = ruleOpt.get();
    int requiredMct = rule.getMctMinutes();
    boolean compliant = scheduledMinutes >= requiredMct;

    return new MctEvaluationResult(
            airportCode,
            connType,
            scheduledMinutes,
            requiredMct,
            true,  // knownAirportMct = true
            !compliant, // requiresManualReview = true if tight connection
            compliant,
            compliant ? "COMPLIANT_BUFFER" : "TIGHT_CONNECTION_RISK",
            rule.getSourceAuthority() + " [" + rule.getRuleVersion() + "]"
    );
}
```

---

## 4. Multi-Tenant Operational Configuration

Flyo Rescue manages multi-tenancy strictly through database-level column isolation (`tenant_id`) enforced across all JPA entities and audited queries:
- **`TenantContext`**: ThreadLocal storage seeded by HTTP headers (`X-Tenant-ID`), JWT claims, or authenticated operator session.
- **Tenant Isolation Entities**:
  * `rescue_cases.tenant_id`
  * `bookings.tenant_id`
  * `state_transitions.tenant_id`
  * `payment_transactions.tenant_id`
  * `audit_events.tenant_id`
- **Tenant Validation**: Cross-tenant data access attempts trigger `TenantAccessDeniedException` (HTTP 403) and log an immutable `SECURITY_CROSS_TENANT_VIOLATION` audit event.

---

## 5. Startup Configuration Verification (`ProductionSecurityValidator.java`)

When booted with `SPRING_PROFILES_ACTIVE=prod`, the backend executes fail-closed configuration verification before accepting HTTP traffic:
1. **Secret Entropy**: `flyo.security.jwt.secret` must be $\ge 32$ characters and not contain weak words (`secret`, `password`, `demo`).
2. **Database Cleanliness**: `flyo.seeding.enabled` must be `false`; `BookingService.resetSeedBookings()` throws `UnsupportedOperationException`.
3. **Provider Configuration**: GDS, WhatsApp, and Payment adapters must be configured with authorized credentials or explicitly configured sandboxes.
4. **MCT Rules Initialized**: The `airport_mct_rules` table must contain validated regulatory rules.

---

## 6. Architecture Compliance Summary

| Configuration Category | Storage Medium | Modification Path | Fail-Safe Behavior |
| :--- | :--- | :--- | :--- |
| **Airport Minimum Connect Times** | Database table `airport_mct_rules` | Flyway migration or Admin API | Fail-closed: Flags `requiresManualReview = true` |
| **Operator Credentials & RBAC** | Database table `operator_accounts` | Admin bootstrap or Admin API | Zero default accounts in production; 401 Unauthorized |
| **Tenant Policies & Rules** | Database table `tenant_configs` | Admin portal | Defaults to standard tenant policy |
| **Payment Virtual Cards** | Provider Tokenization (Stripe/Adyen) | Real-time payment gateway API | PCI-DSS masked (`•••• 9542`), zero hardcoded card suffixes |
| **Flight Status & Schedules** | Aviation GDS & Radar APIs | Live polling / Webhook ingress | Fallback to weather-correlated model estimation |
