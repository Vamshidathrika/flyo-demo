# FLYO — STAGE 1: OBSERVABILITY & OPERATOR RECOVERY REPORT

**Author:** SRE, Observability & Forward Deployment Team  
**Date:** November 6, 2026  
**Status:** IMPLEMENTED & OPERATIONAL  
**Target Controller:** `com.flyo.rescue.controller.SagaMonitoringController`  

---

## 1. Overview

Stage 1 equips airline operations centers (OCCs) and travel management desks with real-time operational visibility into asynchronous event processing, active rebooking sagas, and failed execution states.

All administrative monitoring endpoints are secured under `/api/admin/**` and require `SUPERVISOR` or `ADMIN` role authentication.

---

## 2. Operator REST API Endpoints

### 1. Outbox System Metrics
- **Endpoint:** `GET /api/admin/outbox/metrics`
- **Role Required:** `SUPERVISOR`, `ADMIN`
- **Sample Response:**
```json
{
  "pendingEvents": 0,
  "deadLetterEvents": 0,
  "oldestPendingCreatedAt": null,
  "timestamp": "2026-11-06T11:30:21.000"
}
```

### 2. Dead-Letter Event Inspection
- **Endpoint:** `GET /api/admin/outbox/events?status=DEAD_LETTER`
- **Role Required:** `SUPERVISOR`, `ADMIN`
- **Description:** Returns all events that failed after 5 retries. Operators can inspect the payload, last error code, and error trace.

### 3. Manual Outbox Retry Trigger
- **Endpoint:** `POST /api/admin/outbox/events/{eventId}/retry`
- **Role Required:** `SUPERVISOR`, `ADMIN`
- **Description:** Resets a dead-letter event's status to `PENDING`, sets `retry_count=0`, and clears locks, allowing the dispatcher to immediately reprocess the event.

### 4. Saga System Metrics
- **Endpoint:** `GET /api/admin/sagas/metrics`
- **Role Required:** `SUPERVISOR`, `ADMIN`
- **Sample Response:**
```json
{
  "pendingSagas": 0,
  "runningSagas": 0,
  "waitingForExternalResult": 0,
  "retryPendingSagas": 0,
  "manualInterventionRequired": 0,
  "completedSagas": 1,
  "failedSagas": 0
}
```

### 5. Saga Inspection by Case ID or Status
- **Endpoint:** `GET /api/admin/sagas?status=FAILED` or `GET /api/admin/sagas?caseId=C1`
- **Role Required:** `SUPERVISOR`, `ADMIN`
- **Description:** Returns a list of saga executions matching status or case filter.

### 6. Detailed Saga Audit Trail & Step Logs
- **Endpoint:** `GET /api/admin/sagas/{sagaId}`
- **Role Required:** `SUPERVISOR`, `ADMIN`
- **Sample Response:**
```json
{
  "saga": {
    "sagaId": "saga_009b73f2-1ee5-4702-a21e-cfa9c6751d31",
    "caseId": "C1",
    "sagaType": "REBOOKING_SAGA",
    "currentStep": "COMPLETED",
    "status": "COMPLETED",
    "attemptCount": 1,
    "maxAttempts": 3,
    "correlationId": "corr_4d3b8e72-1c2a-4f5e-9a1b-3c4d5e6f7a8b",
    "idempotencyKey": "rebook_saga_C1_O2",
    "createdAt": "2026-11-06T11:30:20.612",
    "updatedAt": "2026-11-06T11:30:20.666"
  },
  "stepLogs": [
    {
      "id": "slog_1a2b3c4d-...",
      "stepName": "AUTHORIZE_PAYMENT",
      "action": "AUTHORIZE",
      "status": "SUCCEEDED",
      "inputPayload": "extraCost=4200",
      "outputPayload": "cardId=VCC-1,token=tok_sandbox_001",
      "durationMs": 4
    },
    {
      "id": "slog_2b3c4d5e-...",
      "stepName": "DISPATCH_REBOOK",
      "action": "EXECUTE",
      "status": "SUCCEEDED",
      "inputPayload": "flight=FL301",
      "outputPayload": "newPnr=M7SAGA1",
      "durationMs": 28
    },
    {
      "id": "slog_3c4d5e6f-...",
      "stepName": "VERIFY_TICKET",
      "action": "VERIFY",
      "status": "SUCCEEDED",
      "inputPayload": "pnr=M7SAGA1",
      "outputPayload": "E-ticket verified & case marked DONE",
      "durationMs": 12
    }
  ]
}
```

### 7. Manual Saga Retry Trigger
- **Endpoint:** `POST /api/admin/sagas/{sagaId}/retry`
- **Role Required:** `SUPERVISOR`, `ADMIN`
- **Description:** Allows an operator who has resolved an external issue (e.g., cleared airline portal blockage or topped up treasury limit) to reset a `FAILED` or `WAITING_FOR_EXTERNAL_RESULT` saga to `PENDING` for automatic resumption.

---

## 3. Scheduled Telemetry Logs

Both `OutboxDispatcherWorker` and `SagaExecutionWorker` emit structured metric summaries every 60 seconds:
- `[Outbox Worker] Periodic health check: 0 pending, 0 dead-letter.`
- `[Saga Worker] Periodic health check: 0 pending, 0 waiting for external result, 0 manual intervention required.`
