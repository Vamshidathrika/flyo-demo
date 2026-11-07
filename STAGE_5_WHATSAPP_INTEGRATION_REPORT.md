# FLYO STAGE 5 — META WHATSAPP CLOUD API INTEGRATION REPORT

**Classification:** Enterprise Communications & Passenger Messaging Report  
**Target:** Meta WhatsApp Business Cloud API (Graph API v21.0) & Webhook Event Pipeline  
**Scope:** Outbound Interactive Notifications, Inbound Webhooks, OTP Security, Delivery Reconciliation  
**Status:** Certified Architecture & Contract Verified  
**Date:** November 2026  

---

## 1. Executive Summary

Passenger communications during flight cancellations require instantaneous, interactive, and transparent messaging. In Stage 4, `MetaWhatsAppClient` returned `true` (simulating success) when unconfigured, causing `RebookingSagaCoordinator` to record `notification_status = 'DELIVERED'` without transmitting remote HTTP packets.

In Stage 5, this messaging surface was audited and hardened:
1. **Official Graph API Contract Enforcement:** Outbound messages target `https://graph.facebook.com/v21.0/{phoneNumberId}/messages` using Bearer token authentication.
2. **Provider Message ID (`wamid`) Tracking:** Outbound dispatches parse Meta's JSON response `messages[0].id` and persist the authoritative `wamid` for status correlation.
3. **Delivery Receipt & Webhook Ingestion:** Inbound Meta delivery, read, and failure webhooks (`/api/whatsapp/webhook`) are processed with HMAC-SHA256 signature verification and message deduplication.
4. **Honest Capability State:** When `WHATSAPP_PHONE_NUMBER_ID` or `WHATSAPP_ACCESS_TOKEN` is unset or contains `mock_`, the adapter reports `CONFIGURED_NOT_VERIFIED` / `NOT_CONFIGURED`, and outbound logs reflect simulation without claiming real provider delivery receipts.

---

## 2. Interactive Message Workflow & Template Structure

| Step | Trigger | Message Format | Interactive Action Buttons |
| :--- | :--- | :--- | :--- |
| **Step 2** | Flight Alert Ingestion (`CANCELLED`) | Proactive Cancellation Notice | `[RESCUE MY TRIP]` / `[ABANDON RESCUE]` |
| **Step 3** | Passenger taps `RESCUE MY TRIP` | Immediate Rescue Acknowledgment | Informational text |
| **Step 6** | Alternatives Search Complete | Ranked Alternative Options Display | Dynamic option selection buttons (e.g. `[SELECT 1: AI-840 ₹0]`) |
| **Step 7 & 8**| Option Selected | Itinerary Preview & OTP Request | Dynamic confirmation (Scenario A: Positive Fare, Scenario B: Zero Fare) |
| **Step 11** | E-Ticket Verified | Verified Confirmation & PNR | `[Check Status]` / `[View Audit Trail]` |
| **Step 12** | Passenger taps `ABANDON RESCUE`| Abandon Confirmation & Refund Notice | Informational text |

---

## 3. Webhook Security & Idempotency Pipeline

1. **HMAC-SHA256 Signature Validation:** 
   - Meta supplies `X-Hub-Signature-256: sha256={hash}`.
   - Validated against application secret (`WHATSAPP_APP_SECRET`) via `MetaWhatsAppSignatureValidator`.
   - Replay attacks and forged payloads are rejected with HTTP 403 Forbidden.
2. **Event Deduplication:**
   - Meta webhooks frequently retry. Each webhook event extracts Meta's `wamid` or message ID as an idempotency key.
   - Duplicate events hit `WebhookEventRepository.findByEventIdAndTenantId()` and return HTTP 200 immediately without re-triggering case transitions.
3. **Synthetic Test Data Rule:**
   - Automated test suites (`WhatsAppPassengerRescueWorkflowTest`, `WhatsAppCommandBotTest`, `WhatsAppWebhookTest`) use strictly synthetic phone numbers (e.g. `+91 98765 43210`, `+91 00000 00000`). No live passenger phone numbers are used in automated test runs.

---

## 4. Current Operational Status

- **API Endpoint:** `https://graph.facebook.com/v21.0/{id}/messages`
- **Environment Status:** `WHATSAPP_PHONE_NUMBER_ID` and `WHATSAPP_ACCESS_TOKEN` default to development mock values.
- **Capability State:** `CONFIGURED_NOT_VERIFIED` / `SANDBOX_CONTRACT_TESTED`.
- **Classification:** **VERIFIED_MOCK** in development; ready for partner System User Token injection for client pilot.
