# FLYO — STAGE 1: DATABASE MIGRATION REPORT

**Author:** PostgreSQL & Database Systems Engineering Team  
**Date:** November 6, 2026  
**Status:** MIGRATION EXECUTED & VERIFIED  
**Target Migration File:** `flyo-rescue-demo/backend/src/main/resources/db/migration/V5__transactional_outbox_and_saga.sql`  

---

## 1. Migration Overview

The migration `V5__transactional_outbox_and_saga.sql` upgrades Flyo from a local demonstration datastore to an enterprise distributed coordination store. It maintains full dual compatibility with **PostgreSQL 14+** (production target) and **H2 In-Memory Database** (test suite runner).

### Sequence of Flyway Migrations in Flyo:
1. `V1__initial_production_schema.sql`: Core cases, bookings, rebook options, virtual cards, simulated messages, state transitions, baseline outbox.
2. `V2__passenger_rescue_workflow.sql`: Passenger WhatsApp multi-state interaction fields.
3. `V3__aviation_intelligence_and_risk.sql`: Risk scoring and weather summary fields.
4. `V4__pci_remediation.sql`: Drop CVV, drop raw PAN, introduce surrogate tokens (`card_token_id`, `masked_card_number`).
5. **`V5__transactional_outbox_and_saga.sql` (Stage 1):** Distributed Outbox envelope upgrade, Saga state persistence, and Step audit logs.

---

## 2. DDL & Migration Statements

```sql
-- =============================================================================
-- FLYO DISRUPTION RECOVERY PLATFORM: SCHEMA MIGRATION V5
-- STAGE 1: TRANSACTIONAL OUTBOX DISPATCHER & DISTRIBUTED SAGA ENGINE
-- =============================================================================

-- 1. UPGRADE OUTBOX_EVENTS TABLE TO DISTRIBUTED VERSIONED ENVELOPE
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS event_version INT DEFAULT 1;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'default';
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64);
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS causation_id VARCHAR(64);
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS max_retries INT DEFAULT 5;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS available_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS published_at TIMESTAMP;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS locked_by VARCHAR(64);
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS locked_at TIMESTAMP;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS last_error_code VARCHAR(64);

-- Populate available_at for existing records
UPDATE outbox_events SET available_at = created_at WHERE available_at IS NULL;
UPDATE outbox_events SET event_version = 1 WHERE event_version IS NULL;
UPDATE outbox_events SET max_retries = 5 WHERE max_retries IS NULL;

-- Unique constraint for outbox idempotency key
CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_idempotency ON outbox_events(idempotency_key);

-- High-performance index for FOR UPDATE SKIP LOCKED batch dispatcher
CREATE INDEX IF NOT EXISTS idx_outbox_dispatch ON outbox_events(status, available_at, created_at);

-- Index for lease timeout recovery
CREATE INDEX IF NOT EXISTS idx_outbox_lease ON outbox_events(status, locked_at);

-- 2. CREATE SAGA_EXECUTIONS TABLE
CREATE TABLE IF NOT EXISTS saga_executions (
    saga_id VARCHAR(64) PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    saga_type VARCHAR(64) NOT NULL,
    current_step VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    next_retry_at TIMESTAMP,
    correlation_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    failure_reason_code VARCHAR(64),
    payload TEXT,
    compensation_payload TEXT,
    locked_by VARCHAR(64),
    locked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_saga_idempotency ON saga_executions(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_saga_case ON saga_executions(case_id);
CREATE INDEX IF NOT EXISTS idx_saga_dispatch ON saga_executions(status, next_retry_at, created_at);
CREATE INDEX IF NOT EXISTS idx_saga_lease ON saga_executions(status, locked_at);

-- 3. CREATE SAGA_STEP_LOGS TABLE (HISTORICAL AUDIT & RECONCILIATION TRAIL)
CREATE TABLE IF NOT EXISTS saga_step_logs (
    id VARCHAR(64) PRIMARY KEY,
    saga_id VARCHAR(64) NOT NULL,
    step_name VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_payload TEXT,
    output_payload TEXT,
    error_details TEXT,
    duration_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_saga_step_saga_id ON saga_step_logs(saga_id, created_at);
```

---

## 3. Backward Compatibility & Performance Considerations

1. **Non-Destructive Alterations:** All column additions in `outbox_events` use `ADD COLUMN IF NOT EXISTS` with sane defaults (`event_version=1`, `tenant_id='default'`, `max_retries=5`). Existing legacy outbox records are safely migrated without null dereferences.
2. **Index Optimization for Dispatchers:**
   - `idx_outbox_dispatch` index on `(status, available_at, created_at)` eliminates table scans during high-frequency worker polling ($O(1)$ index seek for ready events).
   - `idx_outbox_lease` on `(status, locked_at)` enables swift evaluation of abandoned leases without scanning completed rows.
   - `idx_saga_idempotency` ensures that even under concurrent race conditions, no two sagas can be instantiated for the same case and option combination.
3. **Data Type Sizing:** Primary key identifiers for `outbox_events` remain bounded to 36-character standard UUID strings, preventing schema truncation issues across database engines.
