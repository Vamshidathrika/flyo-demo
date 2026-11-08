# FLYO — STAGE 3 CLIENT PILOT RUNBOOK
## Standard Operating Procedures for Forward Deployment Engineers & Client Operators

**Audience:** Forward Deployment Engineers (FDEs), Client Operations Supervisors, Airline OCC Duty Managers  
**Version:** Stage 3 Controlled Pilot Baseline  
**Environment:** Client Staging / Certified Sandbox Boundary

---

## 1. Pilot Objectives & Operational Scope

The Stage 3 controlled pilot demonstrates and validates the Flyo Rescue Agent within a prospective client's operational workflow without touching live airline inventory or exposing production financial capital.

### Pilot Boundaries:
- **Tenancy**: Dedicated tenant partition (e.g. `tenant_pilot_alpha`).
- **Data Ingestion**: Staging PNR feeds or historical disruption replays.
- **Approvals**: Mandatory 2-person human supervisor approval on all rebooking actions.
- **Rebooking Execution**: Bounded to airline sandbox endpoints or staging GDS environments.
- **Payment Execution**: Stripe Issuing test keys or client sandbox virtual credit cards. Zero real currency transfer.

---

## 2. Daily Pilot Operating Procedures

### Phase 1: Shift Handover & System Health Inspection
At the start of each operational shift, the FDE and Client Supervisor must execute the following health checks:
1. **Container & Application Status**:
   ```bash
   curl -s http://localhost:8080/actuator/health | jq .
   ```
   *Expected Response:* `{"status": "UP"}`
2. **Provider Sandbox Connectivity**:
   - Query `/api/admin/integrations/providers` to ensure all pilot adapters report `SANDBOX_CONNECTED` or `SANDBOX_CONTRACT_TESTED`.
   - Ensure rate limit metrics show active shared database coordination:
     ```bash
     curl -s -H "Authorization: Bearer $SUPERVISOR_JWT" http://localhost:8080/api/admin/rate-limits/metrics
     ```
3. **Outbox & Saga Queue Inspection**:
   - Check pending outbox events:
     ```bash
     curl -s -H "Authorization: Bearer $SUPERVISOR_JWT" http://localhost:8080/api/admin/outbox/metrics
     ```
   - Verify `deadLetterEvents == 0` and `manualInterventionSagas == 0`.

---

## 3. Incident Triage & Emergency Response SOPs

### SOP 1: Upstream Provider Timeout during Rebooking Dispatch
*Symptom:* Network disconnect or 504 Gateway Timeout when dispatching rebooking to carrier sandbox.
*Automated Action:*
- Coordinator marks step as `AMBIGUOUS` and enters `WAITING_FOR_EXTERNAL_RESULT`.
- Does **NOT** retry immediately (prevents duplicate booking).
*Operator Action:*
1. Locate case in Console (`/api/cases/{caseId}`).
2. Click **Reconcile with Carrier**.
3. If carrier host verifies reservation exists, PNR is accepted and state advances to `VERIFYING_TICKET`.
4. If carrier host confirms reservation is absent, click **Retry Dispatch** or **Compensate VCC**.

### SOP 2: Passenger Discrepancy Escalation
*Symptom:* Flight status API reports flight ON_TIME, but passenger reports cancellation at airport.
*Operator Action:*
1. Open case discrepancy ledger: `/api/cases/{caseId}/discrepancies`.
2. Inspect Tier 1 OCC / PSS status vs Tier 2 radar status.
3. If confirmed cancelled in PSS, override radar status using operator authorization token.
4. Record operator justification in immutable audit trail.

### SOP 3: Immediate Tenant Quarantine / Kill-Switch
*Symptom:* Client security alert or credential revocation.
*Operator Action:*
```bash
# Disable outbound provider dispatches immediately:
curl -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  http://localhost:8080/api/admin/integrations/providers/{providerId}/disable
```
All in-flight commands transition to `MANUAL_INTERVENTION_REQUIRED` without data loss.
