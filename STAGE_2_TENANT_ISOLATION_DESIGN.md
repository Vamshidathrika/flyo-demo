# Stage 2: Multi-Tenant Data Isolation Design Specification

**Author:** Principal PostgreSQL Database Architect & Application Security Engineer  
**Date:** November 6, 2026  
**System:** Flyo Autonomous Disruption Recovery Engine  

---

## 1. Architectural Model: Shared Process, Logical Schema Isolation

Flyo operates a robust multi-tenant model with tenant-scoped logical isolation:
- **Tenant Context Propagation**: Inbound HTTP requests supply `X-Tenant-ID` header or authenticated JWT claims (e.g. `tenant_id: "tenant_corporate_tmc_01"`).
- **Per-Thread Isolation**: `TenantFilter` intercepts all incoming requests, binds the validated tenant identifier to `TenantContext` (`ThreadLocal<String>`), and ensures deterministic cleanup in a `finally` block to prevent thread pool leakage.
- **Tenant Column Partitioning**: All core business and ledger entities contain a non-null `tenant_id VARCHAR(64)` column with default fallback to `"tenant_default"`.
- **Database Index Optimization**: Compound indexes are constructed on `(tenant_id, ...)` to ensure that multi-tenant queries execute with low latency and never trigger table scans across tenant partitions.

---

## 2. Database Schema Migration (Flyway V6)

Flyway migration `V6__multi_tenant_isolation.sql` establishes tenant isolation across seven core persistence tables:

```sql
-- 1. ADD TENANT_ID TO CORE ENTITIES
ALTER TABLE rescue_cases ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE saga_executions ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE simulated_messages ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE virtual_cards ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'tenant_default';

-- 2. CREATE COMPOUND TENANT INDEXES
CREATE INDEX IF NOT EXISTS idx_rescue_cases_tenant_state ON rescue_cases(tenant_id, state);
CREATE INDEX IF NOT EXISTS idx_bookings_tenant ON bookings(tenant_id);
CREATE INDEX IF NOT EXISTS idx_saga_executions_tenant ON saga_executions(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_audit_events_tenant ON audit_events(tenant_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_simulated_messages_tenant ON simulated_messages(tenant_id);
CREATE INDEX IF NOT EXISTS idx_vcc_tenant ON virtual_cards(tenant_id);
```

---

## 3. Data Flow & Boundary Enforcement

1. **Ingress Boundary**: Requests without explicit tenant identification default to `"tenant_default"`, ensuring backward compatibility for legacy workflows while allowing enterprise clients to enforce dedicated tenant IDs.
2. **Repository Boundary**: Repository queries that retrieve customer data are scoped by `tenant_id`.
3. **Outbox & Saga Boundary**: `outbox_events` and `saga_executions` record the originator `tenant_id`, ensuring asynchronous workers process tasks within the originating tenant's operational context.
4. **Leakage Prevention**: Cross-tenant data inspection is physically prevented by matching tenant IDs on all queries. In automated tests (`ProviderIntegrationAndTenantTest.java`), tenant-scoped queries verify that Tenant A cannot view or modify Tenant B's rescue cases or webhooks.
