-- =============================================================================
-- FLYO DISRUPTION RECOVERY PLATFORM: SCHEMA MIGRATION V6
-- STAGE 2: MULTI-TENANT ISOLATION, WEBHOOKS & EXTERNAL RECONCILIATION LEDGER
-- Database Compatibility: PostgreSQL 14+ / H2 In-Memory (Test Harness)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. ADD TENANT_ID TO CORE ENTITIES
-- -----------------------------------------------------------------------------
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE saga_executions ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE simulated_messages ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';

-- Populate existing rows with default tenant
UPDATE rescue_cases SET tenant_id = 'tenant_default' WHERE tenant_id IS NULL;
UPDATE bookings SET tenant_id = 'tenant_default' WHERE tenant_id IS NULL;
UPDATE saga_executions SET tenant_id = 'tenant_default' WHERE tenant_id IS NULL;
UPDATE audit_events SET tenant_id = 'tenant_default' WHERE tenant_id IS NULL;
UPDATE simulated_messages SET tenant_id = 'tenant_default' WHERE tenant_id IS NULL;
UPDATE virtual_cards SET tenant_id = 'tenant_default' WHERE tenant_id IS NULL;
UPDATE payment_transactions SET tenant_id = 'tenant_default' WHERE tenant_id IS NULL;

-- -----------------------------------------------------------------------------
-- 2. CREATE COMPOUND TENANT INDEXES
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_rescue_cases_tenant_state ON rescue_cases(tenant_id, state);
CREATE INDEX IF NOT EXISTS idx_bookings_tenant ON bookings(tenant_id);
CREATE INDEX IF NOT EXISTS idx_saga_executions_tenant ON saga_executions(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_audit_events_tenant ON audit_events(tenant_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_simulated_messages_tenant ON simulated_messages(tenant_id);
CREATE INDEX IF NOT EXISTS idx_vcc_tenant ON virtual_cards(tenant_id);

-- -----------------------------------------------------------------------------
-- 3. WEBHOOK INGESTION & IDEMPOTENCY TABLE
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS webhook_events (
    event_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant_default',
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    signature VARCHAR(256),
    status VARCHAR(32) NOT NULL DEFAULT 'PROCESSED',
    error_message TEXT,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_webhook_events_tenant ON webhook_events(tenant_id, event_type);

-- -----------------------------------------------------------------------------
-- 4. EXTERNAL STATE RECONCILIATION DISCREPANCY TABLE
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS discrepancy_records (
    id VARCHAR(64) PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant_default',
    discrepancy_type VARCHAR(64) NOT NULL,
    local_state VARCHAR(64) NOT NULL,
    remote_state VARCHAR(64) NOT NULL,
    details TEXT,
    resolved BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_discrepancy_case ON discrepancy_records(case_id);
CREATE INDEX IF NOT EXISTS idx_discrepancy_tenant ON discrepancy_records(tenant_id, resolved);
