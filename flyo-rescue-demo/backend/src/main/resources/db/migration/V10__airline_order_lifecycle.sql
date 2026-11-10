-- FLYO DISRUPTION RECOVERY PLATFORM: SCHEMA MIGRATION V10
-- STAGE 10: REAL AIRLINE PILOT & AIRLINE ORDER LIFECYCLE
-- Database Compatibility: PostgreSQL 14+ / H2 In-Memory (Test Harness)
-- =============================================================================

CREATE TABLE IF NOT EXISTS airline_order_records (
    id VARCHAR(64) PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant_default',
    flight_number VARCHAR(32) NOT NULL,
    carrier_code VARCHAR(8) NOT NULL,
    passenger_name VARCHAR(128) NOT NULL,
    order_lifecycle_state VARCHAR(32) NOT NULL, -- FLIGHT_OFFER_SELECTED, ORDER_CREATED, PNR_CONFIRMED, TICKET_ISSUED, CANCELLED, FAILED
    provider_order_id VARCHAR(128),
    gds_pnr VARCHAR(16),
    ticket_number VARCHAR(64),
    ticketing_status VARCHAR(32),
    coupon_status VARCHAR(32),
    amount INT NOT NULL DEFAULT 0,
    currency VARCHAR(8) NOT NULL DEFAULT 'INR',
    correlation_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    raw_provider_payload TEXT,
    version BIGINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_airline_order_case ON airline_order_records(case_id);
CREATE INDEX IF NOT EXISTS idx_airline_order_pnr ON airline_order_records(gds_pnr);
CREATE INDEX IF NOT EXISTS idx_airline_order_correlation ON airline_order_records(correlation_id);
CREATE INDEX IF NOT EXISTS idx_airline_order_tenant ON airline_order_records(tenant_id, created_at);

CREATE TABLE IF NOT EXISTS pilot_workflow_executions (
    id VARCHAR(64) PRIMARY KEY,
    correlation_id VARCHAR(128) NOT NULL UNIQUE,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant_default',
    case_id VARCHAR(64),
    booking_id VARCHAR(64),
    flight_number VARCHAR(32) NOT NULL,
    disruption_reason VARCHAR(256) NOT NULL,
    pax_name VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    current_step INT NOT NULL DEFAULT 1,
    total_steps INT NOT NULL DEFAULT 15,
    approved_by VARCHAR(64),
    approved_at TIMESTAMP,
    provider_name VARCHAR(64),
    order_id VARCHAR(128),
    gds_pnr VARCHAR(16),
    ticket_number VARCHAR(64),
    execution_trace TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_pilot_workflow_correlation ON pilot_workflow_executions(correlation_id);
CREATE INDEX IF NOT EXISTS idx_pilot_workflow_case ON pilot_workflow_executions(case_id);
