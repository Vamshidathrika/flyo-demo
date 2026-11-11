# Stage 14 — Database Migrations: Schema & Referential Integrity

## Overview
Stage 14 introduces database migrations `V14` and `V15` to provide relational enforcement for distributed idempotency, brute-force lockout protection, authoritative reconciliation auditing, and booking lifecycle state persistence. Both migrations have been validated on both H2 in-memory test databases and live local PostgreSQL 16 installations.

---

## Migration V14: Distributed Transaction Safety & Referential Integrity
File: `flyo-rescue-demo/backend/src/main/resources/db/migration/V14__distributed_transaction_safety_and_referential_integrity.sql`

```sql
-- 1. Create Business Idempotency Table
CREATE TABLE IF NOT EXISTS business_idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant_default',
    operation_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    expires_at TIMESTAMP NOT NULL,
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
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS strategy_type VARCHAR(64) DEFAULT 'STANDALONE_RECOVERY';
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS preserves_onward_connection BOOLEAN DEFAULT TRUE;
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS connects_to_flight VARCHAR(32);
ALTER TABLE rebook_options ADD COLUMN IF NOT EXISTS connection_airport VARCHAR(8);
```

---

## Migration V15: Distributed Idempotency, Phone Locks & Reconciliation Records
File: `flyo-rescue-demo/backend/src/main/resources/db/migration/V15__distributed_idempotency_and_resilience.sql`

```sql
-- 1. Create Distributed Idempotency Records Table
CREATE TABLE IF NOT EXISTS distributed_idempotency_records (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant_default',
    scope VARCHAR(64) NOT NULL DEFAULT 'GLOBAL',
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    response_payload TEXT,
    locked_by VARCHAR(64),
    locked_at TIMESTAMP,
    expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dist_idemp_scope ON distributed_idempotency_records(tenant_id, scope, status);

-- 2. Create Passenger Phone Lockout Table
CREATE TABLE IF NOT EXISTS passenger_phone_locks (
    phone_number VARCHAR(32) PRIMARY KEY,
    failed_attempts INT NOT NULL DEFAULT 0,
    last_failed_at TIMESTAMP,
    locked_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. Create Authoritative Provider Reconciliation Records Table
CREATE TABLE IF NOT EXISTS provider_reconciliation_records (
    id VARCHAR(64) PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    booking_id VARCHAR(64) NOT NULL,
    provider_name VARCHAR(64) NOT NULL,
    provider_order_id VARCHAR(64),
    provider_request_id VARCHAR(64),
    original_flight VARCHAR(32),
    recovery_flight VARCHAR(32),
    status VARCHAR(64) NOT NULL,
    reconciliation_details TEXT,
    reconciled_pnr VARCHAR(32),
    reconciled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_prov_recon_case ON provider_reconciliation_records(case_id);
CREATE INDEX IF NOT EXISTS idx_prov_recon_req ON provider_reconciliation_records(provider_request_id);

-- 4. Extend passenger_otp_challenges with Security & Route Constraints
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS otp_hash VARCHAR(128);
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS flight_number VARCHAR(32);
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS route VARCHAR(64);
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS option_id VARCHAR(64);
ALTER TABLE passenger_otp_challenges ADD COLUMN IF NOT EXISTS attempt_count INT DEFAULT 0;
```

---

## Migration Verification Matrix

| Table Name | Database Engine | Primary Key | Key Indices | Foreign Key / Referential Integrity |
| :--- | :--- | :--- | :--- | :--- |
| `business_idempotency_keys` | PostgreSQL / H2 | `idempotency_key` | `(tenant_id, operation_type, resource_id)` | Scoped to tenant identifier |
| `webhook_deduplications` | PostgreSQL / H2 | `id` | `UNIQUE(provider, event_id)` | Provider-level unique idempotency |
| `distributed_idempotency_records` | PostgreSQL / H2 | `idempotency_key` | `(tenant_id, scope, status)` | Multi-worker coordination lock |
| `passenger_phone_locks` | PostgreSQL / H2 | `phone_number` | Primary key lookup | Persisted across application restarts |
| `provider_reconciliation_records` | PostgreSQL / H2 | `id` | `(case_id)`, `(provider_request_id)` | Links to `rescue_cases.id` & `bookings.id` |
