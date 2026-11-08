# FLYO RESCUE — STAGE 9: RESCUE WORKFLOW & STATE MACHINE

## 1. Lifecycle State Machine
Every passenger disruption case in Flyo Rescue transitions through a durable database-backed state machine.

```mermaid
stateDiagram-v2
    [*] --> DETECTED: Disruption Telemetry Ingested
    DETECTED --> VALIDATED: Multi-source Evidence Confirmed
    VALIDATED --> IMPACT_ASSESSED: Passenger Connection Risk Evaluated
    IMPACT_ASSESSED --> ALTERNATIVES_FOUND: Real GDS Offers Found
    ALTERNATIVES_FOUND --> PROPOSAL_CREATED: Ranked Options Prepared
    PROPOSAL_CREATED --> AWAITING_APPROVAL: Dispatched to WhatsApp/Operator
    AWAITING_APPROVAL --> APPROVED: Passenger/Operator Consents
    AWAITING_APPROVAL --> CANCELLED: Passenger Declines / Refund Chosen
    APPROVED --> EXECUTION_IN_PROGRESS: VCC Issued & Order Submitted
    EXECUTION_IN_PROGRESS --> WAITING_FOR_EXTERNAL_RESULT: Provider Processing
    WAITING_FOR_EXTERNAL_RESULT --> CONFIRMED: Provider Order & PNR Verified
    WAITING_FOR_EXTERNAL_RESULT --> RECONCILIATION_REQUIRED: Provider Timeout / Ambiguous
    RECONCILIATION_REQUIRED --> CONFIRMED: Reconciled via Order Lookup
    RECONCILIATION_REQUIRED --> FAILED: Order Failed at Gateway
    FAILED --> MANUAL_INTERVENTION_REQUIRED: Escalated to Desk Agent
    CONFIRMED --> [*]: Passenger Boarded / Rescued
```

---

## 2. State Transition Safeguards

1. **Transactional Outbox**: All state transitions generate immutable audit events in the `audit_logs` table within the same ACID database transaction.
2. **Idempotency Keys**: Rebooking requests carry deterministic keys formatted as `rebook-{caseId}-{optionId}-{timestamp}` to prevent duplicate provider charges.
3. **No Autonomous Financial Execution on Pure ML**: An ML disruption probability score *never* executes rebooking or card issuance without passing policy approval gates or verified disruption signals.
4. **Ambiguous Outcomes Fail-Safe**: When an external GDS or Payment provider times out, the system enters `WAITING_FOR_EXTERNAL_RESULT` or `RECONCILIATION_REQUIRED`. It never assumes success or blindly retries.
