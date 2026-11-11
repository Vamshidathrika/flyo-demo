-- =============================================================================
-- FLYO DISRUPTION RECOVERY PLATFORM: SCHEMA MIGRATION V14
-- STAGE 14: DISTRIBUTED TRANSACTION INTEGRITY & PRODUCTION RESILIENCE
-- Database Compatibility: PostgreSQL 14+ / H2 In-Memory (Test Harness)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. DISTRIBUTED IDEMPOTENCY RECORDS
-- Replaces all in-memory sets with a durable, cross-instance, restart-proof store.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS distributed_idempotency_records (
    idempotency_key VARCHAR(190) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    scope VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    locked_by VARCHAR(64),
    locked_at TIMESTAMP,
    response_payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dist_idem_scope ON distributed_idempotency_records(scope, status);
CREATE INDEX IF NOT EXISTS idx_dist_idem_expiry ON distributed_idempotency_records(expires_at);

-- -----------------------------------------------------------------------------
-- 2. DURABLE PASSENGER PHONE LOCKS & RATE LIMITS
-- Persists brute-force lockouts and attempt limits across application restarts.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS passenger_phone_locks (
    phone VARCHAR(32) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    last_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lock_reason VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_phone_locks_locked_until ON passenger_phone_locks(locked_until);

-- -----------------------------------------------------------------------------
-- 3. PROVIDER RECONCILIATION RECORDS
-- Reconciles canonical booking vs disruption event vs provider order vs confirmed PNR.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS provider_reconciliation_records (
    id VARCHAR(64) PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    booking_id VARCHAR(64) NOT NULL,
    provider_name VARCHAR(64) NOT NULL,
    provider_order_id VARCHAR(128),
    correlation_id VARCHAR(64) NOT NULL,
    disruption_flight VARCHAR(32),
    recovery_flight VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    discrepancy_details TEXT,
    reconciled_pnr VARCHAR(32),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reconciled_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_prov_recon_case ON provider_reconciliation_records(case_id);
CREATE INDEX IF NOT EXISTS idx_prov_recon_corr ON provider_reconciliation_records(correlation_id);

-- -----------------------------------------------------------------------------
-- 4. HARDEN PASSENGER OTP CHALLENGES SCHEMA
-- Adds route binding, flight binding, and optimistic concurrency versioning.
-- -----------------------------------------------------------------------------
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS bound_route VARCHAR(64);
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS bound_flight VARCHAR(32);
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS lock_version INT DEFAULT 0;
