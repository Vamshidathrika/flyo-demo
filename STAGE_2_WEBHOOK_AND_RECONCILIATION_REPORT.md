# Stage 2: Webhook Integration & External-State Reconciliation Report

**Author:** Distributed Systems Architect & Application Security Engineer  
**Date:** November 6, 2026  
**System:** Flyo Autonomous Disruption Recovery Engine  

---

## 1. Webhook Security & Ingestion Architecture

External disruption feeds, payment settlement notifications, and passenger communication responses arrive at `/api/webhooks/incoming`. To prevent spoofing, tampering, and replay attacks, the endpoint enforces a three-tier defense in depth implemented in `WebhookEventProcessor.java`:

### 1.1 HMAC-SHA256 Cryptographic Signature Verification
- Inbound webhooks must supply the signature in the `X-Flyo-Signature` header (or `X-Hub-Signature-256`).
- Flyo computes `HmacSHA256(rawPayload, signingSecret)` and performs a **constant-time array comparison** (`MessageDigest.isEqual`) to prevent timing side-channel attacks.
- If the signature does not match, the request is immediately rejected with HTTP 401 Unauthorized.

### 1.2 Timestamp-Based Replay Protection
- Inbound requests must supply a UNIX epoch timestamp in `X-Flyo-Timestamp`.
- If `|currentEpoch - timestamp| > 300` seconds (5-minute tolerance window), the webhook is rejected with HTTP 400 Bad Request (`EXPIRED_TIMESTAMP`).
- This guarantees that captured webhook traffic cannot be replayed maliciously at a later time.

### 1.3 Strict Idempotency & Deduplication
- Every webhook carries a unique `X-Flyo-Event-ID`.
- Ingestion checks the `webhook_events` persistence table.
- If the `eventId` already exists, Flyo records an idempotent cache hit and returns HTTP 200 OK (`IDEMPOTENT_DUPLICATE`) without re-processing business logic or creating duplicate transactions.

---

## 2. External-State Reconciliation Subsystem

In production aviation networks, state discrepancies inevitably arise between Flyo's local database and remote systems of record due to manual agent overrides, airline host schedule adjustments, or gateway timeouts.

`ExternalStateReconciliationService.java` continuously audits local state against remote airline PSS and payment gateways:

### 2.1 Reconciled Discrepancy Types
1. **`REMOTE_PNR_VOID_OR_CANCELLED`**: Local Flyo case is marked `DONE` with a confirmed PNR, but the airline PSS host reports the PNR cancelled or ticket coupon `VOID`.
2. **`LOCAL_REBOOKING_REMOTE_CONFIRMED`**: Local Flyo case is in `REBOOKING` (e.g. following a network timeout), but the remote airline host has already confirmed the reservation.
3. **`PAYMENT_SETTLEMENT_ORPHANED`**: A virtual card authorization was issued, but settlement was never confirmed by the payment gateway within the 24-hour settlement window.

### 2.2 Discrepancy Record Lifecycle
- When a discrepancy is detected, a durable record is written to `discrepancy_records`.
- An immutable audit trail event (`RECONCILIATION_DISCREPANCY_DETECTED`) is logged with full divergence details.
- Supervisors can inspect unresolved discrepancies via `GET /api/admin/reconciliations/discrepancies` and record resolutions via `POST /api/admin/reconciliations/{id}/resolve`.

---

## 3. Automated Verification Evidence

The webhook processor and reconciliation service were verified in `ProviderIntegrationAndTenantTest.java`:
- `testWebhookIngestionValidHmac`: **PASS** (Valid HMAC ingested and persisted in `webhook_events`).
- `testWebhookIngestionInvalidHmac`: **PASS** (Forged signature rejected with HTTP 401 `INVALID_SIGNATURE`).
- `testWebhookReplayProtection`: **PASS** (Timestamp older than 300s rejected with HTTP 400 `EXPIRED_TIMESTAMP`).
- `testWebhookIdempotentDeduplication`: **PASS** (Duplicate event returns `IDEMPOTENT_DUPLICATE` without duplicating records).
- `testExternalStateReconciliation`: **PASS** (Detects `DONE` vs `CANCELLED` drift, creates discrepancy record, logs audit event, and confirms resolution).
