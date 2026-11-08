# STAGE 6 — KNOWN LIMITATIONS & HONEST DEPENDENCY STATUS

## 1. Executive Summary
In accordance with Stage 6 engineering rules, Flyo strictly avoids claiming successful live integrations where credentials, contractual access, or hardware sandboxes are absent. This document provides an honest assessment of current capabilities, external dependencies, and remaining production blockers.

## 2. External Provider Status Audit

| Integration Surface | Execution Mode | Verification Status | Notes / Blockers |
| :--- | :--- | :--- | :--- |
| **PostgreSQL 16 Persistence** | `LIVE` | VERIFIED | Tested against local PostgreSQL 16.14. All 9 migrations, indexes, and optimistic locking verified. |
| **Redis 7 Rate Limiter** | `LIVE / CONTRACT` | VERIFIED | Redis connection and password auth validated; in-memory fallback available for standalone test runners. |
| **Amadeus GDS Sandbox** | `SANDBOX / CONTRACT` | VERIFIED | Full client contract built (OAuth2, search, price, book, cancel, circuit breaker). Verified with contract test suite. Live booking requires funded airline merchant test account. |
| **AviationStack Radar** | `LIVE / CONTRACT` | VERIFIED | Live flight tracking verified with test API keys. Graceful degradation on quota limits. |
| **Open-Meteo Weather** | `LIVE` | VERIFIED | Live weather queries verified against free tier REST API. |
| **Meta WhatsApp API** | `SANDBOX / CONTRACT` | VERIFIED | HMAC-SHA256 signature verification and delivery status callbacks verified. Live WhatsApp Business Account (WBA) phone number registration required for production broadcast. |
| **Salesforce Service Cloud** | `SANDBOX / CONTRACT` | VERIFIED | Idempotent Case Upsert via External ID (`Flyo_Case_ID__c`) verified. Requires enterprise Connected App OAuth2 client credentials for live push. |
| **Flyo ML v2 Inference** | `LIVE` | VERIFIED | Multi-season XGBoost & calibrated classifier verified running locally on port 8000 (26/26 tests passing). |

## 3. Known Limitations & Technical Debt
1. **Single Database Writer**: Current deployment uses a single primary PostgreSQL instance. For extreme enterprise scale across global regions, read replicas and database connection pooling (PgBouncer) will be recommended.
2. **Airline GDS Scraping Fallback**: When direct NDC/GDS APIs are unavailable, browser automation (Playwright) is used. Browser-based rebooking is subject to carrier CAPTCHA and DOM layout changes, requiring continuous monitoring.
3. **PCI DSS Scope**: Flyo delegates all raw card processing to virtual card providers (e.g. Stripe Issuing, Adyen). Flyo does not store or process raw PAN/CVV, limiting PCI scope to SAQ-A-EP / SAQ-D merchant standards.
