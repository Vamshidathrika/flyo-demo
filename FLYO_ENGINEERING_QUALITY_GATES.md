# FLYO RESCUE — ENGINEERING QUALITY GATES SPECIFICATION (STAGE 11)
**Framework**: `eval-harness` Gating & Antigravity Global Verification Rule  
**Scope**: Pre-Commit, Pre-PR, and Pre-Deployment Verification Standards

---

## 1. Quality Gate Hierarchy

To prevent regressions, broken builds, or security vulnerabilities from entering the codebase, Flyo Rescue enforces a five-layer deterministic quality gate hierarchy:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        FLYO QUALITY GATES                              │
├────────────────────────────────────────────────────────────────────────┤
│ Layer 5: Production Certification Scripts & PostgreSQL Rehearsal       │
│          (./scripts/stage10-production-certification)                  │
├────────────────────────────────────────────────────────────────────────┤
│ Layer 4: Provider Boundary Contract & Fail-Closed Tests                │
│          (ProviderAdaptersIntegrationTest, Stripe Sandbox Tests)       │
├────────────────────────────────────────────────────────────────────────┤
│ Layer 3: Enterprise Integration & Multi-Tenant Security Tests          │
│          (Stage3EnterpriseSecurityTest, IdempotencyTest, SagaReliability│
├────────────────────────────────────────────────────────────────────────┤
│ Layer 2: Core Domain Unit Tests (Backend Java + Python ML)             │
│          (287 Java tests, 36 Python tests)                             │
├────────────────────────────────────────────────────────────────────────┤
│ Layer 1: Static Typecheck, AST Linting & Secret Leakage Scanners       │
│          (npx tsc --noEmit, build.mjs, forbidden pattern scanner)     │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Gate Definitions & Mandatory Pass Criteria

### Layer 1: Static Typecheck, AST & Secret Scanners
- **Command**: `cd flyo-rescue-demo/agent && npx tsc --noEmit` and `cd flyo-rescue-demo/frontend && npm run build`
- **Pass Criteria**:
  - TypeScript compiler reports **0 errors**.
  - JavaScript AST validation passes cleanly.
  - Secret scanner confirms 0 unencrypted private keys, Stripe live keys, AWS credentials, or hardcoded passwords.

### Layer 2: Core Domain Unit Tests
- **Backend Command**: `mvn -f flyo-rescue-demo/backend/pom.xml test`
- **ML Command**: `./ml/.venv/bin/python -m unittest discover -s ml/tests`
- **Pass Criteria**:
  - All 287 backend Java tests pass with 0 failures and 0 errors.
  - All 36 Python ML tests pass with 0 failures and 0 errors.
  - Seeding is verified absent under `prod`, `staging`, and default profiles (`FlyoDataSeederProfileTest`).
  - Demo authentication is verified absent under non-demo profiles (`DemoAuthenticationProfileTest`).

### Layer 3: Multi-Tenant Security & Distributed Resilience
- **Command**: `mvn -f flyo-rescue-demo/backend/pom.xml test -Dtest=Stage3EnterpriseSecurityTest,IdempotencyTest,Stage6DistributedSagaReliabilityTest`
- **Pass Criteria**:
  - Cross-tenant case, booking, and payment access blocked with HTTP 403 Forbidden.
  - Concurrent booking execution produces exactly 1 airline reservation and 1 payment charge.
  - Orphaned saga step leases are reclaimed cleanly without duplicate blind retries.

### Layer 4: Provider Boundary Contract Tests
- **Command**: `mvn -f flyo-rescue-demo/backend/pom.xml test -Dtest=ProviderAdaptersIntegrationTest,Stage9BAdversarialProductionConfigTest`
- **Pass Criteria**:
  - Unconfigured Amadeus NDC adapter throws `ProviderNotConfiguredException` (never invents PNR).
  - Unconfigured Stripe adapter throws `ProviderNotConfiguredException` (never generates synthetic card).
  - Unconfigured radar client returns `Optional.empty()` without simulating flights.
  - PartnerPortalPlaywrightAdapter is strictly isolated from production via `@Profile("!prod")`.

### Layer 5: Production Certification & Disaster Recovery
- **Command**: `./scripts/stage10-production-certification`
- **Pass Criteria**:
  - Clean PostgreSQL deployment with 10 pure DDL Flyway migrations contains exactly 0 seed records.
  - Database disaster recovery rehearsal passes via `pg_dump` and `pg_restore` into a test database with 100% schema integrity.
  - ML model artifact SHA-256 checksums match expected calibration manifests.

---

## 3. Strict Conflation Boundaries

1. **Unit Test vs Live Provider**: A passing unit or sandbox test must **never** be cited as evidence of a live commercial airline booking or actual money movement.
2. **Demo OTP vs 2FA Security**: Automated OTP prefill is purely a local demo convenience; it is strictly prohibited from bypassing human operator authorization.
3. **Execution Modes**: Code must explicitly run under its true tier (`MOCK_DEMO`, `SANDBOX_PROVIDER`, or `LIVE_PROVIDER`).
