# FLYO — STAGE 4 DEPLOYMENT READINESS CHECKLIST
## Production Configuration, Environment Boundaries, Flyway Migrations & Rollback Plans

**Classification:** Flyo DevOps & Release Engineering  
**Version:** 4.0.0-PROD-STAGE4  
**Date:** November 2026  
**Audited Location:** `flyo-rescue-demo/backend/src/main/resources/`

---

## 1. Environment Configuration & Secrets Management

Flyo uses strictly decoupled 12-factor application configuration. All sensitive credentials must be injected via environment variables; hardcoded defaults fall back to sandboxes only when explicitly allowed in development profiles.

| Environment Variable | Target System | Production Requirement | Default / Fallback Mode |
| :--- | :--- | :--- | :--- |
| `SPRING_DATASOURCE_URL` | PostgreSQL DB | `jdbc:postgresql://{host}:5432/flyodb` | In-memory H2 for integration tests |
| `SPRING_DATASOURCE_USERNAME`| PostgreSQL DB | Dedicated Flyo app user | Application user |
| `SPRING_DATASOURCE_PASSWORD`| PostgreSQL DB | Vault-injected secret | Local password |
| `FLYO_JWT_SECRET` | Security Authn | Minimum 256-bit cryptographic hex key | Hardened fallback secret |
| `FLYO_WEBHOOK_SECRET` | Webhook Auth | HMAC-SHA256 signature secret | Hardened fallback secret |
| `AMADEUS_CLIENT_ID` | PSS / E-Ticketing | Amadeus Enterprise Client ID | Sandbox mock credentials |
| `AMADEUS_CLIENT_SECRET` | PSS / E-Ticketing | Amadeus Secret Key | Sandbox mock credentials |
| `STRIPE_API_KEY` | Treasury VCC | Stripe Issuing Production Secret Key | `tok_sandbox` generator |
| `WHATSAPP_TOKEN` | Passenger Comms | Meta System User Access Token | Local terminal logger |
| `SALESFORCE_CLIENT_ID` | Enterprise CRM | Salesforce Connected App Client ID | Sandbox composite mock |

---

## 2. Database Migration Status (Flyway V1–V8)

All migrations are fully forward-compatible and idempotent:
- `V1__init_schema.sql`: Base tables (`bookings`, `rescue_cases`, `rebook_options`, `audit_events`).
- `V2__audit_and_virtual_cards.sql`: `virtual_cards` and `payment_transactions`.
- `V3__stage0_security_and_pci.sql`: Redaction of CVV and full card numbers; addition of `card_token_id`.
- `V4__stage1_outbox_and_saga.sql`: `outbox_events`, `saga_executions`, `saga_step_logs`.
- `V5__stage2_tenant_and_providers.sql`: Addition of `tenant_id` across all entities.
- `V6__stage3_webhook_and_rate_limiting.sql`: `processed_webhook_events`, `distributed_rate_limits`.
- `V7__stage3_provider_capabilities.sql`: `provider_capabilities`, `provider_evidence_records`.
- `V8__stage4_workflow_integrity.sql`: Workflow integrity fields (`correlation_id`, `ticket_number`, `ticket_status`, `notification_status`, `crm_sync_status`).

**Flyway Verification:** Clean execution against PostgreSQL 15+ verified in `PostgreSqlProductionIntegrationTest`.

---

## 3. Container & Kubernetes Readiness

- **Health Endpoints:**
  - Liveness probe: `/actuator/health/liveness` (Returns `{"status": "UP"}`).
  - Readiness probe: `/actuator/health/readiness` (Checks DB connectivity and outbox worker status).
- **Graceful Shutdown:** Configured with `server.shutdown=graceful` and `spring.lifecycle.timeout-per-shutdown-phase=30s`, ensuring active sagas release worker leases before SIGTERM completes.

---

## 4. Rollback & Disaster Recovery Procedures

1. **Blue/Green Deployment:** Flyo supports stateless blue/green deployment. Because schema migration `V8` only adds non-destructive nullable columns, older application versions can run alongside the new release without conflict.
2. **Database Rollback:** If migration rollback is required, nullable columns added in `V8` can be dropped without data loss to existing `V1`–`V7` schemas.
3. **Outbox Recovery on Failure:** If an application node crashes during rebooking dispatch, the 60-second lease expiration automatically releases the saga lock for pickup by surviving nodes.
