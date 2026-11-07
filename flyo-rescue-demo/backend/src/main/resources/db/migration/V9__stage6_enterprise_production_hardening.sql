-- =============================================================================
-- FLYO DISRUPTION RECOVERY PLATFORM: SCHEMA MIGRATION V9
-- STAGE 6: ENTERPRISE PRODUCTION HARDENING & DISTRIBUTED RELIABILITY
-- Database Compatibility: PostgreSQL 14+ / H2 In-Memory (Test Harness)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. OPTIMISTIC CONCURRENCY CONTROL FOR SAGA EXECUTIONS & PAYMENT TRANSACTIONS
-- -----------------------------------------------------------------------------
ALTER TABLE saga_executions ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;
UPDATE saga_executions SET version = 0 WHERE version IS NULL;

ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;
UPDATE payment_transactions SET version = 0 WHERE version IS NULL;

-- -----------------------------------------------------------------------------
-- 2. DURABLE FINANCIAL LEDGER ENHANCEMENTS FOR PAYMENT TRANSACTIONS
-- -----------------------------------------------------------------------------
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS transaction_type VARCHAR(32) DEFAULT 'CAPTURE';
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS reconciled BOOLEAN DEFAULT FALSE;
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS reconciled_at TIMESTAMP;
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(256);
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS audit_metadata TEXT;

UPDATE payment_transactions SET transaction_type = 'CAPTURE' WHERE transaction_type IS NULL;
UPDATE payment_transactions SET reconciled = FALSE WHERE reconciled IS NULL;

-- -----------------------------------------------------------------------------
-- 3. HIGH-PERFORMANCE INDEXES FOR PRODUCTION SCALE & AUDIT REPORTING
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_rescue_cases_tenant_created ON rescue_cases(tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_rescue_cases_flight_state ON rescue_cases(flight, state);
CREATE INDEX IF NOT EXISTS idx_payment_transactions_tenant_time ON payment_transactions(tenant_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_payment_idempotency ON payment_transactions(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_saga_executions_tenant_status ON saga_executions(tenant_id, status);
