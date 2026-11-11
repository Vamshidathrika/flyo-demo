# Stage 14 — Zero Hardcoded Operational Data Audit Report

## Audit Scope & Objective
As mandated by Stage 14 architecture enforcement, the entire Flyo platform codebase was audited using an automated AST and regex scanner (`scripts/scan_hardcoded_data.py`). The audit inspects all Java, Python, JavaScript, HTML, and SQL files across backend, frontend, ML, agent scripts, database migrations, and demo fixtures.

The objective is to guarantee that:
1. **Airport Rules & MCT**: Zero static in-memory MCT maps exist in production service logic; all rules must be database-driven via `airport_mct_rules`.
2. **Passenger Identities**: Zero hardcoded passenger names exist in production services or controllers; passenger context must be dynamically retrieved from the database.
3. **PNR & Tickets**: Zero hardcoded PNRs or ticket numbers exist in production booking/rebooking paths.
4. **Mock Fallback Assumptions**: Zero silent unverified mock fallbacks; simulation mode must be explicitly tagged and disclosed.

---

## Scanner Execution Results

- **Scanner Script**: `python3 scripts/scan_hardcoded_data.py`
- **Output Artifact**: `STAGE_14_HARDCODED_DATA_AUDIT.json` (and `SOURCE_CODE_HARDCODE_SCANNER_RESULTS.json`)
- **Total Files Scanned**: 324
  - **Production Files**: 260
  - **Test / Fixture Files**: 64

### Category Breakdown

| Finding Classification | Permitted Policy | Occurrences Detected | Compliance Status |
| :--- | :--- | :--- | :--- |
| **Test Fixtures** | Permitted in `src/test/`, `tests/`, `mock/` | 60 | **COMPLIANT** |
| **Seed Data** | Permitted in `V1__`, `V11__`, `FlyoDataSeeder.java` | 2 | **COMPLIANT** |
| **Production Code Violations** | Strictly Prohibited | **0** | **100% CLEAN** |
| **Critical Production Violations** | Strictly Prohibited (Hardcoded MCT matrix) | **0** | **100% CLEAN** |

---

## Detailed Findings Analysis

### 1. Airport Minimum Connecting Time (MCT) Architecture
- **Audited Rule**: `HARDCODED_MCT_MATRIX` & `HARDCODED_AIRPORT_MCT_MAP`
- **Findings**:
  - `AirportMctRuleService.java` loads all connection thresholds dynamically from PostgreSQL table `airport_mct_rules`.
  - Zero hardcoded `matrix.put("DEL", ...)` or in-memory static maps in production Java classes.
  - Test suites (`MctRulesTest`, `AirportMctRuleServiceTest`) populate mock rules dynamically or query test H2 schema.

### 2. Passenger Identity Provenance
- **Audited Rule**: `HARDCODED_PASSENGER_NAME`
- **Findings**:
  - Production controllers (`CaseController`, `RebookController`, `WhatsAppWebhookController`) dynamically read passenger identities from `booking.getPassengerName()`.
  - Static references to `"Ananya Sharma"` are strictly confined to test fixtures (`ApprovalGateTest`, `Stage10BEndToEndRescueWorkflowTest`, `OriginalPassengerWorkflowDefectsRegressionTest`) and demo seeder `FlyoDataSeeder.java` (gated by `flyo.seeding.enabled=true`).

### 3. Financial Card Suffix Provenance
- **Audited Rule**: `HARDCODED_CARD_SUFFIX`
- **Findings**:
  - Virtual Card tokens and last-4 digits are dynamically generated upon card issuance by `SandboxPaymentAdapter` (`VCC-` prefix with random hex identifier) or retrieved from Stripe API.
  - Zero hardcoded `8821` card numbers in production UI templates.

---

## Audit Certification
The automated hardcoded operational data scanner confirmed that the production runtime contains **0 hardcoded MCT matrices**, **0 hardcoded passenger identities in production service code**, and **0 unprovenanced operational shortcuts**.
