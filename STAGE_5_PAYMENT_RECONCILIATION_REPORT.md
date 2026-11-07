# FLYO STAGE 5 — PAYMENT CONTROL & FINANCIAL RECONCILIATION REPORT

**Classification:** Corporate Treasury & Financial Governance Report  
**Target:** Virtual Corporate Card Issuance, Spending Policy Gates, Payment Lifecycle, and Reconciliation  
**Scope:** `PaymentSettlementService`, `SandboxPaymentAdapter`, `StripeIssuingPaymentAdapter`, `PaymentTransaction`  
**Status:** Certified Architecture & Contract Verified  
**Date:** November 2026  

---

## 1. Executive Summary

Financial settlements during airline flight rescues involve consequential fund movements. In Stage 4, `SandboxPaymentAdapter` generated synthetic tokens (`tok_sandbox_...`) and masked PANs, and CVV was removed from persistence (`V4__pci_remediation.sql`). However, documentation claimed "PCI DSS v4.0 compliance," which was premature without formal QSA auditing, HSM key management, and third-party Attestation of Compliance (AOC).

In Stage 5, this financial settlement layer was strengthened:
1. **Explicit 12-State Payment Lifecycle:** Defined and enforced across `PaymentTransaction`:
   `NOT_REQUESTED`, `AUTHORIZATION_PENDING`, `AUTHORIZED`, `CAPTURE_PENDING`, `CAPTURED`, `VOID_PENDING`, `VOIDED`, `SETTLEMENT_PENDING`, `SETTLED`, `UNKNOWN`, `FAILED`, `MANUAL_REVIEW_REQUIRED`.
2. **Multi-Tenant Spending Controls:** Strict policy checks enforce that virtual card requests exceeding corporate limits (e.g. ₹50,000 for standard agencies) fail authorization and trigger backward saga compensation.
3. **PCI DSS Demarcation:** Formally classified as an internal tokenized simulation. Clarified that no PAN or CVV is stored, and formal PCI DSS certification is not claimed.
4. **Idempotent Void & Refund:** Implemented and verified automated compensation (`voidAuthorization` and `refundCapturedPayment`) to release card authorization holds upon airline booking failures.

---

## 2. Comprehensive Payment Lifecycle State Machine

```
                        [ Case Approved with Fare > ₹0 ]
                                       │
                                       ▼
                            [ AUTHORIZATION_PENDING ]
                                       │
                       ┌───────────────┴───────────────┐
                       ▼                               ▼
                 [ AUTHORIZED ]                     [ FAILED ]
                       │                               │
                       ▼                               ▼
             [ CAPTURE_PENDING ]          [ Backward Compensation Triggered ]
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
        [ CAPTURED ]         [ UNKNOWN / TIMEOUT ]
             │                   │
             ▼                   ▼
    [ SETTLEMENT_PENDING ]  [ MANUAL_REVIEW_REQUIRED ]
             │
             ▼
        [ SETTLED ]
```

---

## 3. Financial Controls & Spending Limits

| Control Policy | Implementation Mechanism | Enforcement Entity |
| :--- | :--- | :--- |
| **Spending Cap per Disruption** | Hard cap of ₹50,000 per passenger recovery for standard agencies; ₹100,000 for Flyo Corporate | `PaymentSettlementService.verifyAgencySpendingLimit()` |
| **Merchant Category Code (MCC) Lock** | Single-use virtual cards locked strictly to Airline MCCs (`3000`–`3350`) | `VirtualCard.mccRestriction` |
| **Zero-Fare Bypass (Path A)** | Options requiring ₹0 additional fare bypass payment authorization and VCC generation entirely | `RebookingOrchestrationService` (Path A) |
| **Single-Use Instrument Expiry** | Virtual card token expires automatically 4 hours post-authorization | `VirtualCard.expiresAt` |
| **Automated Backward Compensation** | If rebooking fails after payment auth, the saga coordinator voids the card hold immediately | `RebookingSagaCoordinator.triggerCompensation()` |

---

## 4. PCI DSS Compliance & Data Protection Boundaries

Flyo adheres to data minimization:
- **Zero CVV/CVC Storage:** CVV is never stored in database columns, outbox events, or log files.
- **PAN Masking:** Primary Account Numbers are masked to the last 4 digits (e.g. `**** **** **** 4242`).
- **Tokenization:** External card numbers are referenced via tokens (`tok_...`).

**Formal Compliance Statement:** Data minimization is enforced in code and database schemas. However, Flyo does NOT claim formal PCI DSS certification, which requires formal QSA Attestation of Compliance (AOC), Report on Compliance (ROC), and key vault certification.

---

## 5. Current Operational Status

- **Default Operational Gateway:** `SandboxPaymentAdapter` (`SANDBOX_CONTRACT_VERIFIED`).
- **Live Production Gateway:** `StripeIssuingPaymentAdapter` (`NOT_CONFIGURED` / `BLOCKED_BY_EXTERNAL_ACCESS` pending partner Stripe Issuing keys).
- **Classification:** **VERIFIED_MOCK** (Tokenized Treasury Simulator).
