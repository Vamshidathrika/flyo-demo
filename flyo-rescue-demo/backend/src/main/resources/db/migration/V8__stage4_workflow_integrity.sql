-- =============================================================================
-- FLYO DISRUPTION RECOVERY PLATFORM: SCHEMA MIGRATION V8
-- STAGE 4: END-TO-END RESCUE WORKFLOW, INDEPENDENT TICKET VERIFICATION & AUDIT
-- Database Compatibility: PostgreSQL 14+ / H2 In-Memory (Test Harness)
-- =============================================================================

-- 1. ENHANCE RESCUE_CASES WITH INDEPENDENT TICKET, NOTIFICATION & CRM COLUMNS
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS ticket_number VARCHAR(64);
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS ticket_status VARCHAR(32) DEFAULT 'UNVERIFIED';
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS notification_status VARCHAR(32) DEFAULT 'PENDING';
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS crm_sync_status VARCHAR(32) DEFAULT 'PENDING';

CREATE INDEX IF NOT EXISTS idx_rescue_cases_correlation ON rescue_cases(correlation_id);
CREATE INDEX IF NOT EXISTS idx_rescue_cases_ticket ON rescue_cases(ticket_number, ticket_status);

-- 2. ENHANCE STATE_TRANSITIONS FOR FULL AUDITABILITY & CAUSAL TRACEABILITY
ALTER TABLE state_transitions ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE state_transitions ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64);
ALTER TABLE state_transitions ADD COLUMN IF NOT EXISTS causation_id VARCHAR(64);
ALTER TABLE state_transitions ADD COLUMN IF NOT EXISTS external_reference VARCHAR(128);

UPDATE state_transitions SET tenant_id = 'tenant_default' WHERE tenant_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_state_transitions_tenant ON state_transitions(tenant_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_state_transitions_correlation ON state_transitions(correlation_id);

-- 3. ENHANCE SAGA_EXECUTIONS WITH VERIFICATION & SYNC METADATA
ALTER TABLE saga_executions ADD COLUMN IF NOT EXISTS ticket_number VARCHAR(64);
ALTER TABLE saga_executions ADD COLUMN IF NOT EXISTS ticket_status VARCHAR(32) DEFAULT 'UNVERIFIED';
ALTER TABLE saga_executions ADD COLUMN IF NOT EXISTS notification_status VARCHAR(32) DEFAULT 'PENDING';
ALTER TABLE saga_executions ADD COLUMN IF NOT EXISTS crm_sync_status VARCHAR(32) DEFAULT 'PENDING';
