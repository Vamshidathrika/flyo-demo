# Flyo Rescue Agent — WhatsApp Rescue Workflow Provider Status

This document provides an engineering inventory of all external and infrastructure providers involved in the WhatsApp-first passenger flight recovery workflow.

---

## 1. Provider Integration Matrix

| Provider Domain | Production Adapter | Sandbox / Fallback Adapter | Production Readiness Status | Configuration Properties |
| :--- | :--- | :--- | :--- | :--- |
| **Messaging & Passenger Comm** | `MetaWhatsAppClient` (Graph API v20.0) | Local In-Memory & Webhook Simulator | **Production Ready** (Meta Cloud API credentials configured via env vars) | `flyo.whatsapp.access-token`<br>`flyo.whatsapp.phone-number-id`<br>`flyo.whatsapp.app-secret` |
| **Inbound Webhook Security** | `WhatsAppSignatureVerifier` | Local Bypass for `mock_` secrets | **Production Ready** (Strict HMAC-SHA256 signature verification) | `flyo.whatsapp.app-secret` |
| **Flight Cancellation Ingress** | `AviationRadarClient` | Synthetic Radar Mock / Feed Simulator | **Production Ready** (Deduplicated ingestion by Flight + Date) | `flyo.radar.api-key`<br>`flyo.radar.provider-url` |
| **Alternative Flight Discovery** | `AmadeusFlightSearchAdapter` / `SabreFlightSearchAdapter` | `SandboxFlightSearchAdapter` | **Production Ready** (Deterministic ranking with MCT connection preservation) | `flyo.gds.amadeus.client-id`<br>`flyo.gds.amadeus.client-secret` |
| **Payment Settlement** | `StripeIssuingPaymentAdapter` | `SandboxPaymentAdapter` | **Production Ready** (Single-use VCC issuance locked to MCC 3000 Airlines) | `flyo.payment.stripe.api-key`<br>`flyo.payment.provider` |
| **Airline Rebooking Worker** | Playwright Browser Worker (`agent/src/index.ts`) | Mock Airline Portal (`/mock-airline/*`) | **Production Ready** (Headless browser rebooking with 3 retries & screenshot capture) | `flyo.agent.port`<br>`flyo.agent.service-url` |
| **Database & Persistence** | PostgreSQL 16 (HikariCP Pool) | H2 In-Memory (Test profile only) | **Production Ready** (Flyway V1 & V2 migrations verified) | `spring.datasource.url`<br>`spring.datasource.username` |

---

## 2. In-Depth Domain Review

### 2.1 Meta WhatsApp Cloud API
- **Endpoint**: `https://graph.facebook.com/v20.0/{phoneNumberId}/messages`
- **Security**: Inbound requests to `/api/whatsapp/webhook` are cryptographically verified using HMAC-SHA256 signatures against `X-Hub-Signature-256`. Invalid signatures are rejected with `403 Forbidden`.
- **Deduplication**: `processedMessageIds` set ensures duplicate webhook deliveries from Meta are processed idempotently without resending notifications.
- **Mock Mode**: When `access-token` is absent or begins with `mock_`, messages are logged to `WhatsAppChatLogService` and `SimulatedMessageRepository`, allowing testing and demo execution without incurring Meta API fees.

### 2.2 Payment & Corporate Virtual Cards (VCC)
- **Policy**: When a passenger confirms an option with positive fare difference (Scenario B, e.g. +₹4,200), Flyo issues a dedicated single-use corporate virtual card.
- **Constraints**:
  - Bound to specific `caseId` and approved `optionId`.
  - Amount capped exactly to `passengerConfirmedAmount`.
  - Merchant Category Code restricted to `MCC 3000` (Airlines).
  - Single-use authorization prevents replay or unauthorized post-booking charges.
- **Security Boundary**: Passenger OTP confirms passenger acceptance of the itinerary and extra fare. Banking 3D-Secure authentication remains strictly inside the banking provider flow; Flyo never requests banking OTPs, card numbers, or CVVs via WhatsApp.

### 2.3 Carrier Rebooking (Playwright Automation & GDS)
- **Engine**: Headless Chromium instance running under Node.js 20.
- **Resilience**:
  - Automatically navigates to airline portal checkout.
  - Injects verified passenger details, flight selection, and VCC credentials.
  - Implements 3 exponential retries on 500/504 gateway failures.
  - Captures full-page DOM and screenshot artifacts on failure before escalating to `FAILED_ESCALATED` with manual next steps.
- **Verification**: Rebooking is never marked complete until the external portal confirms the transaction and returns a verified PNR.

### 2.4 State Machine & Auditing
- **Database Schema**: Managed by Flyway migrations:
  - `V1__initial_production_schema.sql`: Bookings, rescue cases, options, audit events, VCCs, payments.
  - `V2__passenger_rescue_workflow.sql`: Passenger contact fields, `passenger_otp_challenges` table with index on `phone` and `case_id`.
- **Integrity**: Every state transition is recorded in `case_state_transitions` and queued in the durable `outbox_events` table for reliable event dispatch.
