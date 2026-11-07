# FLYO — STAGE 1: APPLICATION SECURITY REVIEW

**Author:** Principal Application Security Engineer  
**Date:** November 6, 2026  
**Status:** COMPLIANT WITH DEFENSIVE BASELINE  
**Focus Area:** Stage 1 Outbox, Saga Engine, Admin Recovery Endpoints, and Event Data Protection  

---

## 1. Security Architecture & Threat Modeling

Stage 1 introduces asynchronous event staging, background distributed workers, and administrative recovery endpoints. The following threat vectors were analyzed and hardened:

### Threat Model Matrix

| Threat Vector | Risk Description | Stage 1 Mitigation | Verification |
| :--- | :--- | :--- | :--- |
| **Unauthorized Queue Poisoning** | Malicious injection of unapproved saga commands into the database | Rebooking Saga enforces explicit human approval check (`hasApproval`) inside the transactional coordinator. Direct DB inserts without approval throw `ApprovalRequiredException`. | Tested in `testSagaRejectionWithoutApproval` |
| **Administrative Privilege Escalation** | Unauthorized users accessing `/api/admin/**` to drain DLQ or force saga steps | Spring Security filter chain gates `/api/admin/**` behind `hasAnyRole('SUPERVISOR', 'ADMIN')`. Class-level `@PreAuthorize` provides defense-in-depth. | Tested in `testAdminEndpointsForbiddenForNonAdmin` |
| **Cardholder Data Leakage in Outbox Envelopes** | Storing plaintext PANs or CVVs in `outbox_events.payload` | Stage 0 PCI remediation preserved. Outbox payloads strictly contain surrogate tokens (`cardTokenId`), masked PANs (`•••• •••• •••• 2316`), and non-sensitive identifiers. | Verified in outbox payload serializations |
| **Duplicate Financial Debits via Worker Race** | Concurrent workers claiming the same outbox or saga record simultaneously | PostgreSQL `FOR UPDATE SKIP LOCKED` and atomic worker lease updates ensure exclusive single-worker ownership per row. Idempotency keys prevent duplicate entity generation. | Tested in `testOutboxBatchClaimingAndLeaseLocking` |
| **Denial of Service via Poison Pill Events** | Malformed event crashing worker thread repeatedly | Worker wraps step dispatch in `try-catch`, tracks attempt counts, increments exponential backoff, and routes fatal payloads to `DEAD_LETTER` after 5 attempts. | Tested in `testOutboxExponentialBackoffAndDeadLetter` |

---

## 2. Role-Based Access Control (RBAC) Hardening

The Spring Security configuration in `SecurityConfig.java` enforces explicit role gating for Stage 1 operations:

```java
// Stage 1 Administrative Endpoints (Supervisor & Admin Only)
auth.requestMatchers("/api/admin/**").hasAnyRole("SUPERVISOR", "ADMIN");

// Payment & Settlement Control
auth.requestMatchers("/api/payments/**", "/api/settlement/**").hasRole("ADMIN");

// Consequential Disruption & Approval Controls
auth.requestMatchers(HttpMethod.POST, "/api/cases/{id}/approve").hasAnyRole("SUPERVISOR", "ADMIN");
auth.requestMatchers(HttpMethod.POST, "/api/cases/{id}/reject").hasAnyRole("SUPERVISOR", "ADMIN");
auth.requestMatchers(HttpMethod.POST, "/api/cases/simulate").hasAnyRole("SUPERVISOR", "ADMIN");

// Strict Fail-Closed Catch-All
auth.anyRequest().authenticated();
```

---

## 3. Data Protection in Event Payloads & Step Logs

All event payloads staged in `outbox_events` and historical entries in `saga_step_logs` adhere to privacy and PCI data protection requirements:
- **No CVV persistence:** CVV is nowhere in the database schema or event payloads.
- **No Plaintext PAN persistence:** Card numbers are represented exclusively via masked strings (`•••• •••• •••• 2316`) and gateway token references (`tok_sandbox_*` / `tok_1Q...`).
- **Audit Immutability:** Step logs record duration, provider status codes, and anonymized references, preventing leakage of passenger PII while preserving full evidentiary accountability.
