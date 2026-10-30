# Meta WhatsApp Cloud API Integration Plan for Flyo Operations

**Goal:** Enable complete platform and disruption automation control via official Meta WhatsApp Cloud API chat, allowing a Flyo Operator to query bookings, simulate flight disruptions, evaluate ranked recovery options, approve rebooking, trigger autonomous browser rebooking, toggle portal failure modes, or run full-auto rescue workflows directly from WhatsApp chat.

---

## Step 1 — Meta Cloud API Configuration & Webhook Security Ingress
- **Intent**: Implement configuration properties, webhook challenge verification, and HMAC-SHA256 signature verification for inbound Meta WhatsApp webhooks.
- **Components**:
  - `WhatsAppConfig`: properties (`phone-number-id`, `access-token`, `verify-token`, `app-secret`, `operator-phone`, `enabled`).
  - `WhatsAppWebhookController`:
    - `GET /api/whatsapp/webhook`: handles Meta verification (`hub.mode`, `hub.challenge`, `hub.verify_token`).
    - `POST /api/whatsapp/webhook`: validates `X-Hub-Signature-256` header using `app-secret`.
- **Acceptance Criteria**:
  - `GET /api/whatsapp/webhook` with matching `verify_token` returns HTTP 200 with raw `hub.challenge` string.
  - `POST /api/whatsapp/webhook` validates HMAC-SHA256 signature; rejects tampered payloads with 401/403.
  - Deduplicates events using Meta Message ID to guarantee strict idempotency.

---

## Step 2 — Outbound Meta WhatsApp Client & Interactive Messaging
- **Intent**: Build HTTP client to call Meta Graph API (`https://graph.facebook.com/v21.0/{phone_number_id}/messages`) to dispatch text messages and interactive quick-reply buttons.
- **Components**:
  - `MetaWhatsAppClient`: RestClient / WebClient service with Bearer auth token and resilient error handling.
  - Interactive message generator for quick action buttons: `[Approve O2]`, `[Approve O3]`, `[Reject & Escalate]`.
  - Mock/offline mode fallback when access token is not configured or in unit test mode.
- **Acceptance Criteria**:
  - Outbound client dispatches valid Meta JSON payload with phone number formatting (e.g. `919876543210`).
  - Unit tests verify serialization of text and interactive button payloads without live network calls.

---

## Step 3 — WhatsApp Command Interpreter & Autonomous Bot Workflow
- **Intent**: Parse inbound operator messages (both text and button replies) and route them to Flyo backend domain services.
- **Commands**:
  - `HELP` / `MENU`: display full command cheatsheet and status.
  - `STATUS` / `LIST`: format and send Today's Live Bookings summary with disruption flags.
  - `DISRUPT <bookingId>` / `CANCEL <bookingId>`: trigger disruption simulation on B1, compute impact, rank options, and reply with interactive approval card.
  - `OPTIONS [caseId]`: display options O2, O3, O1 with buffer math and fare difference.
  - `APPROVE <optionId>` (e.g. `APPROVE O2`): verify case is in `AWAITING_APPROVAL`, lock option, set actor to `WhatsApp Operator`, unlock rebooking gate.
  - `REJECT [caseId] [reason]`: escalate case, record audit log, block automated rebooking.
  - `REBOOK [caseId]`: trigger Playwright worker, reply with confirmed PNR `R7X2K9` and send notification to passenger.
  - `AUTO RESCUE <bookingId>`: autonomous pipeline executing Disruption &rarr; Impact &rarr; Rank &rarr; Auto-approve safe Rank 1 &rarr; Browser Rebook &rarr; Notify.
  - `BREAK PORTAL` / `RESTORE PORTAL`: toggle mock airline portal outage directly from chat.
- **Acceptance Criteria**:
  - Inbound text or button payload triggers appropriate domain action in `CaseService`.
  - Non-whitelisted numbers receive unauthorized notice; only registered Flyo Operator phone number can execute approvals.
  - Invalid commands return helpful formatted menu.

---

## Step 4 — End-to-End Automated Testing & Live Verification Suite
- **Intent**: Deliver Spring Boot integration tests verifying the complete WhatsApp control lifecycle from challenge verification to end-to-end autonomous rescue.
- **Components**:
  - `WhatsAppWebhookTest`: challenge verification, HMAC signature validation, invalid token rejection.
  - `WhatsAppCommandBotTest`: mock inbound WhatsApp webhook payloads for `STATUS`, `DISRUPT B1`, `APPROVE O2`, `REBOOK C1`, `AUTO RESCUE B1`, and `BREAK PORTAL`.
- **Acceptance Criteria**:
  - All tests pass cleanly in `mvn test`.
  - Verification proves zero regression against all existing 10 MVP acceptance checks.

---

## Step 5 — Production Deployment, Ngrok Tunneling & Setup Guide
- **Intent**: Provide documentation and scripts for running locally with ngrok / Cloudflare Tunnel and configuring the Meta Developer Console.
- **Components**:
  - Update `README.md` with step-by-step Meta Cloud API setup (App ID, Phone Number ID, Webhook URL configuration, System User Access Token).
  - Quickstart command or helper script for local webhook tunnel testing.
- **Acceptance Criteria**:
  - Clear, reproducible guide for setting up webhook callback URL and verify token in Meta Developer Portal.
