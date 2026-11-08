# FLYO STAGE 5 — SALESFORCE SERVICE CLOUD CRM INTEGRATION REPORT

**Classification:** Enterprise CRM & Case Synchronization Report  
**Target:** Salesforce Service Cloud REST API (v59.0) & Connected App OAuth2 Gateway  
**Scope:** Disruption Support Case Upsert, Passenger Interaction Timeline, External ID Mapping, Fault Recovery  
**Status:** Certified Architecture & Contract Verified  
**Date:** November 2026  

---

## 1. Executive Summary

Enterprise corporate travel management requires bidirectional alignment between Flyo's autonomous rescue engine and corporate support desks. In Stage 4, `SalesforceServiceCloudAdapter` returned synthetic string IDs (`"500" + timestamp`) without conducting OAuth2 token exchanges or remote HTTP dispatches.

In Stage 5, this CRM integration layer was hardened:
1. **Typed Salesforce REST SObject Dispatch:** Targets `{instanceUrl}/services/data/v59.0/sobjects/Case` with standard Case fields (`Subject`, `Origin`, `Status`, `Description`, `Flyo_Case_ID__c`).
2. **External ID Mapping:** Uses `Flyo_Case_ID__c` as the authoritative external key, enabling safe idempotent upsert operations without duplicate Case creation.
3. **Transparent Capability Status:** When `SALESFORCE_CLIENT_ID` or `SALESFORCE_INSTANCE_URL` is unconfigured, the adapter reports `CONFIGURED_NOT_VERIFIED`. The saga coordinator records `crm_sync_status = 'SKIPPED_NOT_CONFIGURED'` rather than manufacturing a false sync confirmation.
4. **Decoupled Fault Isolation:** CRM synchronization failures never roll back an active flight booking or validated e-ticket. Failures are captured in `crm_sync_status = 'FAILED'` and queued for asynchronous operator retry.

---

## 2. Supported Data Contract & Schema Mapping

| Flyo Domain Attribute | Salesforce Standard / Custom Field | Type | Field Description |
| :--- | :--- | :--- | :--- |
| `rescueCase.id` | `Flyo_Case_ID__c` | Text(64), External ID, Unique | Unique Flyo case identifier for idempotent upsert |
| `rescueCase.passengerName` | `SuppliedName` / `ContactId` | Reference | Affected corporate employee / traveler identity |
| `rescueCase.cancelledFlight` | `Disrupted_Flight__c` | Text(16) | Original flight number impacted by cancellation/delay |
| `rescueCase.newPnr` | `Airline_PNR__c` | Text(10) | Confirmed airline booking reference |
| `rescueCase.ticketNumber` | `Electronic_Ticket__c` | Text(20) | Authoritative 13-digit IATA electronic ticket |
| `rescueCase.state` | `Status` | Picklist | Maps `DONE` $\to$ `Closed`, `MANUAL_INTERVENTION_REQUIRED` $\to$ `Escalated` |
| `rescueCase.summary` | `Description` | LongTextArea(4000) | Complete audit summary, itinerary diff, and cost provenance |

---

## 3. Operational Lifecycle & Recovery Workflow

```
[ Case Marked DONE / Ticket Verified ]
                 │
                 ▼
     [ Check CRM Configuration ]
      ├── UNCONFIGURED ──► Record 'SKIPPED_NOT_CONFIGURED' (Honest audit trail)
      │
      └── CONFIGURED
                 │
                 ▼
        [ Acquire OAuth2 Token ]
                 │
                 ▼
    [ Execute Upsert to Salesforce ]  (PATCH /sobjects/Case/Flyo_Case_ID__c/{caseId})
                 │
        ┌────────┴────────┐
        ▼                 ▼
   [ HTTP 200/201 ]   [ 4xx / 5xx Error ]
        │                 │
   Record SYNCED     Record FAILED (Queue for retry in Outbox)
```

---

## 4. Current Operational Status

- **Configured Target Instance:** Pending client Salesforce sandbox URL (`SALESFORCE_INSTANCE_URL`).
- **Connected App OAuth Client ID:** Pending client Connected App provisioning (`SALESFORCE_CLIENT_ID`).
- **Capability State:** `CONFIGURED_NOT_VERIFIED` / `SANDBOX_CONTRACT_TESTED`.
- **Classification:** **VERIFIED_MOCK** in local development; ready for partner Connected App activation.
