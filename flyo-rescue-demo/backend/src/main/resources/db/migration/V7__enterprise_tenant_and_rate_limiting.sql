-- =============================================================================
-- FLYO DISRUPTION RECOVERY PLATFORM: SCHEMA MIGRATION V7
-- STAGE 3: ENTERPRISE TENANT BOUNDARIES, DISTRIBUTED RATE LIMITING & PROVIDER EVIDENCE
-- Database Compatibility: PostgreSQL 14+ / H2 In-Memory (Test Harness)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. OPERATOR ACCOUNTS & ENVELOPE TENANT COLUMNS
-- -----------------------------------------------------------------------------
ALTER TABLE operator_accounts ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE operator_accounts ADD COLUMN IF NOT EXISTS authorized_tenants VARCHAR(512) DEFAULT 'tenant_default';
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE saga_step_logs ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';

-- Update existing records to default tenant
UPDATE operator_accounts SET tenant_id = 'tenant_default', authorized_tenants = 'tenant_default' WHERE tenant_id IS NULL;
UPDATE operator_accounts SET authorized_tenants = '*' WHERE username = 'admin';
UPDATE outbox_events SET tenant_id = 'tenant_default' WHERE tenant_id IS NULL;
UPDATE payment_transactions SET tenant_id = 'tenant_default' WHERE tenant_id IS NULL;
UPDATE saga_step_logs SET tenant_id = 'tenant_default' WHERE tenant_id IS NULL;

-- -----------------------------------------------------------------------------
-- 2. COMPOUND TENANT INDEXES
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_outbox_events_tenant ON outbox_events(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_payment_transactions_tenant ON payment_transactions(tenant_id);
CREATE INDEX IF NOT EXISTS idx_saga_step_logs_tenant ON saga_step_logs(tenant_id);

-- -----------------------------------------------------------------------------
-- 3. DISTRIBUTED RATE LIMITING TABLE (CROSS-INSTANCE COORDINATION)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS shared_rate_limits (
    limit_key VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant_default',
    request_count INT NOT NULL DEFAULT 1,
    window_start_ms BIGINT NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rate_limits_tenant ON shared_rate_limits(tenant_id);

-- -----------------------------------------------------------------------------
-- 4. PROVIDER CAPABILITY EVIDENCE LEDGER
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS provider_evidence_records (
    id VARCHAR(64) PRIMARY KEY,
    provider_id VARCHAR(64) NOT NULL,
    provider_name VARCHAR(128) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    api_version VARCHAR(32) NOT NULL,
    capability_tested VARCHAR(128) NOT NULL,
    status VARCHAR(64) NOT NULL,
    contract_test_result VARCHAR(32) NOT NULL,
    auth_result VARCHAR(32) NOT NULL,
    limitations TEXT,
    last_health_check TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    source_of_verification VARCHAR(128) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_provider_evidence ON provider_evidence_records(provider_id, environment);
