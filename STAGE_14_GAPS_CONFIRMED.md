# Stage 14 — Gaps Confirmed: Production Defects & Architectural Deficiencies

## Overview
Prior to the execution of Stage 14, an exhaustive audit was performed against the codebase to identify real concurrency vulnerabilities, distributed consistency gaps, and failure recovery risks. This document cataloged all identified deficiencies along with their severity, reproduction mechanics, and architectural risk.

---

## Confirmed Technical Deficiencies

| Deficiency ID | Category | Component | Description | Production Risk |
| :--- | :--- | :--- | :--- | :--- |
| **GAP-14-01** | P0 Distributed Concurrency | `PassengerOtpService` | OTP failure counters held in in-memory map; restarting the application wiped failure count, enabling unlimited brute-force attacks. | Credential brute-force, unauthorized passenger impersonation. |
| **GAP-14-02** | P0 Concurrency / Replay | `RebookingOrchestrationService` | Multiple simultaneous clicks or concurrent HTTP calls could enter rebooking workflow concurrently, resulting in duplicate virtual card issuances and double bookings on airline portals. | Financial loss, duplicate inventory commitments, disrupted passenger ticketing. |
| **GAP-14-03** | P0 Webhook Deduplication | `WhatsAppWebhookController` | In-memory message deduplication set (`Set<String>`) lost on process restart or across clustered nodes; duplicate Meta delivery triggers duplicate notifications and outbox tasks. | Double passenger notification, spamming, confused customer journey. |
| **GAP-14-04** | P0 Data Provenance & Integrity | `AuthoritativeReconciliationService` | Lack of atomic multi-way cross-validation between canonical booking, flight segments, alert, recovery option, and confirmed PNR. Mismatched routes (e.g. BLR $\rightarrow$ HYD booking receiving HYD $\rightarrow$ DEL options) could be approved. | Itinerary contamination, passengers stranded at wrong hub airports. |
| **GAP-14-05** | P1 Provider Fault Recovery | `RebookingOrchestrationService` | Socket read timeouts during Playwright or GDS submission caused unhandled exceptions or naive retries, potentially creating secondary duplicate bookings when the first succeeded on the carrier host. | Double-booked PNRs, unvoided virtual cards. |
| **GAP-14-06** | P1 Cryptographic Hardening | `PassengerOtpService` | Plaintext OTP generation and storage in relational tables without cryptographic hashing. | Security vulnerability under database credential compromise or logs exposure. |
| **GAP-14-07** | P1 Outbox & State Consistency | `CaseStateMachineService` | Missing dedicated fail-closed transition to `DATA_RECONCILIATION_REQUIRED` from all workflow states when discrepancy is detected by external or asynchronous audit. | Indefinite execution under corrupted or conflicting itinerary state. |

---

## Detailed Failure Analysis

### 1. The Duplicate Rebooking Hazard (GAP-14-02)
- **Mechanism**: A passenger or operator clicks "Approve & Rebook" or submits `REBOOK C1` via WhatsApp while a previous rebook request is already in-flight over a high-latency carrier connection.
- **Root Cause**: The service previously checked `case.getState().equals("DONE")`. While the first request was in state `REBOOKING`, the second request passed the check, dispatched a second Playwright session, generated a second Virtual Card, and booked a second seat.
- **Resolution**: Implemented distributed mutual exclusion via `DistributedIdempotencyService.tryAcquire("REBOOK_EXECUTION:" + caseId)`. The second caller is immediately blocked and receives 409 Conflict.

### 2. The Timeout Double-Booking Dilemma (GAP-14-05)
- **Mechanism**: Carrier reservation portal takes 32 seconds to process credit card payment; HTTP client times out at 30 seconds.
- **Root Cause**: The client threw an exception, recorded failure, and retried. Meanwhile, the carrier completed the transaction and issued PNR `ABC123`. The retry then booked a second seat with PNR `DEF456`.
- **Resolution**: Catch provider timeouts and enter `STATE_PROVIDER_OUTCOME_UNKNOWN` followed by `STATE_RECONCILING`. Probe airline host using `providerRequestId`. Record authoritative audit in `provider_reconciliation_records`. If not created, safely void VCC authorization and transition to `STATE_BOOKING_FAILED`.

### 3. In-Memory OTP Reset Vulnerability (GAP-14-01)
- **Mechanism**: Attacker makes 2 bad OTP guesses, container restarts or rolls over to a new node, attacker gets 3 fresh guesses.
- **Resolution**: Dedicated PostgreSQL table `passenger_phone_locks`. Every failed guess atomically increments `failed_attempts` in the database. When count reaches 3, `locked_until` is set to `NOW() + 15 MINUTES`. The lockout persists across all container restarts and cluster nodes.
