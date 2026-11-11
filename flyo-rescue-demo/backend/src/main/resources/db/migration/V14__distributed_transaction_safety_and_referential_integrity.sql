-- =============================================================================
-- FLYO DISRUPTION RECOVERY PLATFORM: SCHEMA MIGRATION V14
-- STAGE 14: DISTRIBUTED TRANSACTION SAFETY & AUTHORITATIVE RECONCILIATION
-- Database Compatibility: PostgreSQL 14+ / H2 In-Memory (Test Harness)
-- =============================================================================

-- 1. Create Business Idempotency Table for Distributed Rebooking and Mutations
CREATE TABLE IF NOT EXISTS business_idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant_default',
    operation_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    response_payload TEXT,
    locked_by VARCHAR(64),
    locked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_biz_idemp_tenant_res ON business_idempotency_keys(tenant_id, operation_type, resource_id);

-- 2. Create Webhook Ingestion Deduplication Table
CREATE TABLE IF NOT EXISTS webhook_deduplications (
    id VARCHAR(64) PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL DEFAULT 'GENERIC_WEBHOOK',
    payload_hash VARCHAR(128),
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant_default',
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    status VARCHAR(32) NOT NULL DEFAULT 'PROCESSED'
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_webhook_prov_event ON webhook_deduplications(provider, event_id);

-- 3. Extend rescue_cases with Provider Unknown-Outcome and Multi-Segment Recovery Strategy
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS provider_request_id VARCHAR(64);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS provider_timeout_count INT DEFAULT 0;
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS provider_reconciliation_status VARCHAR(64) DEFAULT 'NONE';
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS recovery_strategy VARCHAR(64) DEFAULT 'REPLACE_DISRUPTED_SEGMENT_PRESERVE_ONWARD';
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS last_reconciled_at TIMESTAMP;
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS reconciled_by VARCHAR(64);

-- 4. Extend rebook_options with Strategy and Connection Semantics
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS recovery_strategy VARCHAR(64) DEFAULT 'REPLACE_DISRUPTED_SEGMENT_PRESERVE_ONWARD';
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS preserves_onward_connection BOOLEAN DEFAULT TRUE;
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS reroutes_entire_journey BOOLEAN DEFAULT FALSE;
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS baggage_through_checked BOOLEAN DEFAULT FALSE;

-- 5. Referential Integrity & Consistency Indexes
CREATE UNIQUE INDEX IF NOT EXISTS idx_flight_seg_booking_idx ON flight_segments(booking_id, segment_index);
CREATE INDEX IF NOT EXISTS idx_cases_booking ON rescue_cases(booking_id);
CREATE INDEX IF NOT EXISTS idx_options_booking ON rebook_options(booking_id);
