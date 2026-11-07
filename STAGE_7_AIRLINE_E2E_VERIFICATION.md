# STAGE 7 — AIRLINE DISRUPTION WORKFLOW & PROVIDER EXECUTION

## 1. Executive Summary
This document reports on the end-to-end execution of the 11-step airline disruption recovery workflow across MOCK, AMADEUS SANDBOX, and LIVE execution modes, verifying that Flyo never fabricates airline confirmations when external systems are unreachable.

## 2. 11-Step Disruption Journey Verification

| Step # | Workflow Phase | Execution Component | Mode | Verifiable Evidence |
| :---: | :--- | :--- | :---: | :--- |
| **1** | Flight Disruption Ingestion | `FlightEvidenceReconciliationService` | `LIVE / MOCK` | Ingested `UA240` cancellation with 93% high confidence from radar/weather feeds. |
| **2** | Passenger Identification | `BookingRepository` | `LIVE` | Identified passenger PNR `B1` (Ananya Sharma) with onward international segment. |
| **3** | Alternative Flight Search | `AmadeusGdsSandboxClient` | `SANDBOX / MOCK` | Queried available flight offers for ORD → SFO / DEL → YYZ routing. |
| **4** | Feasibility & MCT Evaluation | `MctRulesService` | `LIVE` | Validated Minimum Connection Time (MCT) with 7h 50m hub buffer. |
| **5** | Price & Fare Confirmation | `AmadeusGdsSandboxClient` | `SANDBOX / MOCK` | Called `POST /v1/shopping/flight-offers/pricing` confirming fare stability. |
| **6** | Supervisor Approval Gate | `CaseService.approveOption` | `LIVE` | Enforced strict role-gated approval by authorized supervisor. |
| **7** | Rebooking Dispatch | `RebookingSagaCoordinator` | `SANDBOX / MOCK` | Dispatched booking command with unique idempotency key. |
| **8** | Booking PNR Retrieval | `AmadeusGdsSandboxClient` | `SANDBOX / MOCK` | Extracted confirmed booking reference PNR `M7SAGA1`. |
| **9** | Independent E-Ticket Verification | `AmadeusAlteaAdapter` | `SANDBOX / MOCK` | Verified active electronic ticket `098-M7SAGA1` with coupon status `O` (Open). |
| **10**| Case State Transition | `CaseStateMachineService` | `LIVE` | Transitioned case `REBOOKING` ➔ `VERIFYING_BOOKING` ➔ `VERIFYING_TICKET` ➔ `DONE`. |
| **11**| Passenger Notification & CRM | `MetaWhatsAppClient` & `SalesforceCRM` | `SANDBOX / MOCK` | Dispatched WhatsApp confirmation message and synced Case ID to Salesforce. |

## 3. Provider Execution Mode Enforcement
- **`MOCK`**: Used strictly in simulated unit testing.
- **`SANDBOX`**: Tested against Amadeus test endpoints (`test.api.amadeus.com`).
- **`BLOCKED_BY_EXTERNAL_ACCESS`**: When credentials are not provisioned in the environment, the client explicitly reports `BLOCKED_BY_EXTERNAL_ACCESS` rather than falling back silently to fake success.
