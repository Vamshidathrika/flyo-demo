# FLYO STAGE 5 — CLIENT PILOT READINESS & RUNBOOK

**Document ID:** `FLYO-STG5-PILOT-016`  
**Classification:** Operational Deployment & Client Onboarding Guide  
**Application:** Flyo Rescue System (`flyo-rescue-demo`)  
**Date:** November 2026  
**Auditor:** Principal Forward Deployment Engineer & Airline Solutions Architect  

---

## 1. Executive Summary

This runbook defines the operational readiness criteria, onboarding procedures, human-in-the-loop control gates, and operating constraints for deploying Flyo in a **controlled client pilot** with an airline or travel management partner.

The pilot deployment is configured with an **airtight safety envelope**: 100% human supervisor approval gating on every financial rebooking, strict passenger volume caps, fail-closed handling on ambiguous provider responses, and dedicated multi-tenant isolation.

---

## 2. Pilot Readiness Checklist

| Readiness Domain | Item | Verification Criteria | Status |
|---|---|---|---|
| **Core Architecture** | 10-Layer Decoupled Provider Foundation | Domain services completely separated from provider SDKs. | **VERIFIED** |
| **Booking Safety** | Anti-Duplicate Booking on Timeout | Timeouts escalate to `MANUAL_INTERVENTION_REQUIRED`; zero blind retries. | **VERIFIED** |
| **Human Controls** | Supervisor Approval Gate | `/api/cases/{id}/approve` strictly enforced before saga execution. | **VERIFIED** |
| **Connection Safety** | MCT Matrix & Unlisted Fallback | Unlisted airports require manual connection review. | **VERIFIED** |
| **Security & Auth** | Fail-Closed JWT & Tenant Isolation | Cross-tenant access rejected with HTTP 403. | **VERIFIED** |
| **Secret Management** | Production Secret Validator | App halts boot if production secrets are weak or missing. | **VERIFIED** |
| **Frontend Pipeline** | Reproducible Build & Test | Zero `node -c`; native AST parsing, secret scan, minification, and manifest. | **VERIFIED** |
| **Automated Testing** | 235 Passing Automated Tests | 206 backend + 26 ML + 3 frontend tests passing with 0 errors. | **VERIFIED** |
| **External Access** | Partner GDS / PSS Sandbox | Partner credentials provisioned in Vault before live pilot cutover. | **PENDING PARTNER CREDENTIALS** |

---

## 3. Client Tenant Onboarding Playbook

### Step 1: Tenant Provisioning
Register the partner airline tenant via Flyo administrative CLI or migration script:
```sql
INSERT INTO tenant_organizations (
    tenant_id, organization_name, iata_code, base_currency, max_daily_budget, created_at
) VALUES (
    'tenant_partner_air', 'Partner Airlines International', 'PA', 'USD', 25000.00, NOW()
);
```

### Step 2: Vault Secret Binding
Inject the partner's verified sandbox or production API keys into the secrets vault:
- `AMADEUS_CLIENT_ID` / `AMADEUS_CLIENT_SECRET` (or Sabre EPR credentials)
- `META_WHATSAPP_PHONE_ID` / `META_WHATSAPP_BEARER_TOKEN`
- `SALESFORCE_CLIENT_ID` / `SALESFORCE_CLIENT_SECRET`
- `STRIPE_ISSUING_KEY` / `STRIPE_ACCOUNT_ID`

### Step 3: SLA & Policy Configuration
Define the airline's disruption compensation and rebooking policies in `tenant_policies`:
- Maximum auto-approved rebooking cost (e.g., $450 USD).
- Preferred partner alliance carriers (e.g., Star Alliance, SkyTeam, Oneworld).
- Approved meal voucher allowance (e.g., $35 USD).
- WhatsApp template namespace approved by Meta.

### Step 4: Operator & Supervisor Account Provisioning
Create initial accounts with BCrypt-12 hashed credentials:
- Role `OPERATOR`: Disruption monitoring, passenger communication, itinerary review.
- Role `SUPERVISOR`: Consequential approval authority for flight rebooking and virtual card issuance.
- Role `AUDITOR`: Read-only access to saga audit logs and financial reconciliation ledgers.

---

## 4. Operator Dashboard Runbook

### Primary Workflow: Disruption to Rescue
1. **Disruption Ingestion:**
   Flight cancellation or severe delay (> 120 minutes) appears on the dashboard with ML delay confidence score and priority classification (High / Medium / Low).
2. **Automated Alternative Calculation:**
   Flyo calculates optimal rescue options based on seat availability, minimum connection times, and partner agreements.
3. **Supervisor Review & Approval (The Mandatory Human Gate):**
   A human supervisor reviews the itinerary, passenger connection feasibility, and cost. The supervisor clicks **Approve Solution**.
4. **Saga Rebooking Execution:**
   Flyo executes the rebooking saga:
   - Reserves seats in carrier sandbox.
   - Verifies ticket issuance independently.
   - Issues passenger virtual meal/hotel card.
   - Dispatches WhatsApp flight notification with interactive confirmation buttons.
   - Synchronizes case notes with Salesforce Service Cloud.

---

## 5. Emergency Runbook & Fail-Closed Procedures

### Scenario A: External Provider Socket Timeout
- **Symptom:** GDS or airline host does not respond within 5,000ms during booking execution.
- **System Behavior:** Rebooking saga halts immediately. State transitions to `STATE_MANUAL_INTERVENTION_REQUIRED`. Case is routed to the **Supervisor Emergency Queue**.
- **Operator Action:**
  1. Open the Case Details drawer.
  2. Inspect the `carrierCorrelationId` and `idempotencyKey`.
  3. Query the airline host terminal (e.g., Sabre Native Terminal or Amadeus Portal) to verify if PNR was created.
  4. If PNR exists: click **Confirm External Booking** to resume saga.
  5. If PNR does not exist: click **Retry with Alternative Flight** or **Refund**.

### Scenario B: Emergency Kill Switch
If an upstream data provider begins broadcasting erroneous cancellations or corrupted telemetry:
- An administrator executes:
  ```bash
  curl -X POST https://flyo-rescue.internal/api/admin/kill-switch/activate \
    -H "Authorization: Bearer ${ADMIN_JWT}" \
    -H "Content-Type: application/json" \
    -d '{"reason": "Upstream radar feed corruption", "tenantId": "tenant_partner_air"}'
  ```
- All automated rebooking sagas are instantly suspended; cases enter read-only queue.

---

## 6. Pilot Operating Envelope (Phase 1 Guardrails)

During the initial 30-day client pilot, the system enforces strict guardrails:
1. **Daily Volume Cap:** Maximum 50 passenger rescues per 24-hour window.
2. **Airport Hub Restriction:** Operations restricted to 2 approved pilot hubs (e.g., DEL and BOM).
3. **Financial Limit:** Maximum $500 USD per passenger rebooking / virtual card allocation.
4. **100% Human Approval:** Zero autonomous background rebookings; every single case requires explicit supervisor sign-off.
5. **Double Verification:** Ticket status must return `OPEN` or `ISSUED` before WhatsApp confirmation is sent to the passenger.

---

*Report signed by Principal Forward Deployment Engineer & Airline Solutions Architect, November 2026.*
