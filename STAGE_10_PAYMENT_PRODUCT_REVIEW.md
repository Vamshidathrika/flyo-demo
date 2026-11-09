# STAGE 10: PAYMENT PRODUCT REVIEW & FINANCIAL ARCHITECTURE AUDIT
**Flyo Disruption Management Platform**  
**Financial Engineering Analysis:** Treasury Disbursement vs. Merchant Payment Acceptance

---

## 1. Executive Summary & Problem Formulation

A critical finding of Stage 10 is that **Stripe Issuing is NOT a universal payment product for all airline disruption use cases**. Treating Stripe Issuing as an all-purpose payment gateway leads to architectural confusion.

In airline disruption management, there are two opposing financial flows:
1. **Outbound Disbursement (Airline Supplier Payment)**: Paying airlines/hotels for rebooked tickets.
2. **Inbound Merchant Acceptance (Passenger Upgrades / Co-pays)**: Charging a passenger when they opt for a cabin upgrade or co-pay.

---

## 2. Product Separation Matrix

| Financial Operation | Target Business Direction | Appropriate Payment Product | Implementation Class in Flyo | KYC & Compliance Prerequisite |
| :--- | :--- | :--- | :--- | :--- |
| **Virtual Card Issuance** | Outbound to Airline | **Stripe Issuing** (`/v1/issuing/cards`) | `StripeIssuingPaymentAdapter` | Corporate KYC, Treasury deposit, Commercial Card Agreement |
| **Airline Checkout Auth** | Outbound to Airline | **Airline Merchant Gateway** / BSP | GDS / Carrier checkout | Restricted to Airline MCC 3000-3350 |
| **Passenger Co-Pay Auth** | Inbound from Passenger | **Stripe PaymentIntents** (`/v1/payment_intents`) | `PaymentSettlementService` | Standard Stripe Merchant Account, 3DS / SCA |
| **Payment Capture** | Inbound / Outbound | **Stripe Charges / Issuing Authorization** | `PaymentSettlementService.capturePayment` | Webhook verification (`charge.captured`) |
| **Payment Void** | Reversal | **Stripe Auth Reversal / Refund** | `PaymentSettlementService.voidAuthorization` | Instant pre-settlement release |
| **Dispute & Refund** | Passenger Welfare | **Stripe Refunds** (`/v1/refunds`) | `PaymentSettlementService.refundCapturedPayment`| Idempotent refund with ledger update |
| **Financial Reconciliation**| Internal Audit | **Flyo Durable Ledger** | `PaymentSettlementService.runFinancialReconciliation` | Dual-entry verification against `virtual_cards` and `payment_transactions` |

---

## 3. PCI-DSS v4.0 Compliance Verification

Flyo operates strictly under **SAQ A-EP / SAQ D-Merchant** compliance:
- **No Raw Primary Account Numbers (PAN)**: Only tokenized IDs (`tok_...`) and masked last-4 digits are persisted.
- **Strict CVV/CVC Non-Storage**: In accordance with PCI-DSS Requirement 3.2, security codes are never stored on disk, logged in log files, or committed to audit tables.
- **Category Code Filtering**: Virtual cards are locked to Merchant Category Codes 3000–3350 (Airlines) and 7011 (Hotels).
- **Hard Expiry & Balance Caps**: Virtual cards expire within 24 hours of case resolution and carry an exact spending limit equal to the approved fare delta.

---

## 4. Webhook Security & Idempotency

- **HMAC-SHA256 Signature Verification**: `StripeSignatureValidator` enforces timing-safe HMAC validation using the configured webhook secret.
- **Idempotency Keys**: All financial state mutations require an `idempotencyKey` (e.g. `idem_RESCUE_CASE_{caseId}_TXN`), preventing duplicate debits.
- **Optimistic Concurrency Control**: `PaymentTransaction` entities use JPA `@Version` to prevent race conditions during concurrent settlements.
