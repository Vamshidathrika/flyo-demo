# STAGE 10: PAYMENT & TREASURY ARCHITECTURE CERTIFICATION
**Flyo Disruption Management & Automated Passenger Rescue Platform**  
**Assessment Date:** November 8, 2026 | **Author:** Senior Payments & Financial Infrastructure Architect

---

## 1. Architectural Distinction: Issuing vs. Merchant Acquiring

A critical architectural finding from the Stage 10 audit was the conflation between **Card Issuing** and **Merchant Payment Processing**:

```
                       +---------------------------------------+
                       |        FLYO RESCUE PLATFORM           |
                       +---------------------------------------+
                                  /                 \
        (Outbound Supplier Disbursement)      (Inbound Passenger Payment)
                                /                     \
                               v                       v
               +-----------------------+       +-----------------------+
               |    STRIPE ISSUING     |       |    STRIPE PAYMENTS    |
               | (Virtual Cards / VCC) |       |   (PaymentIntents)    |
               +-----------------------+       +-----------------------+
                           |                               |
                           v                               v
                 Pays Airline / GDS              Collects Fare Diff from
                 (IndiGo, Air India)              Passenger Credit Card
```

### 1.1 Outbound Disbursement (Stripe Issuing)
- **Role**: Flyo issues a single-use or restricted-use Virtual Corporate Card (VCC) loaded with the approved rescue budget (e.g., ₹4,200) to settle the rebooking fare directly with the airline or GDS.
- **Implementation**: Handled by `StripeIssuingPaymentAdapter` and `PaymentSettlementService.authorizePayment()`.
- **Security**: Restricted to Airline Merchant Category Codes (MCC 3000–3350). Card details are never persisted in plaintext (PCI DSS v4.0 Requirement 3.2).

### 1.2 Inbound Passenger Collections (Merchant Acquiring)
- **Role**: If a disruption rescue option requires the passenger to co-pay a fare difference beyond policy limits, Flyo must charge the passenger's personal credit card or corporate account.
- **Requirement**: Stripe `PaymentIntents` or `Charges` API with 3D Secure 2 (3DS) authentication.
- **Status in Stage 9**: Was unrepresented; the system treated VCC creation as the only payment step.
- **Stage 10 Hardening**: The ledger model now explicitly tracks transaction direction (`DISBURSEMENT_VCC` vs `PASSENGER_COLLECTION`).

---

## 2. Complete Payment & Settlement Lifecycle Verification

The payment lifecycle enforces strict state transitions and double-entry ledger semantics:

```
[INIT] ---> AUTHORIZED (VCC Issued) ---> CAPTURED (Flight Ticketed) ---> RECONCILED
                 |                                |
                 v                                v
         VOIDED (Saga Aborted)           REFUNDED (Ticket Voided)
```

### 2.1 Authorized Phase (`TYPE_AUTH`)
- Single-use virtual card generated with strict spending limit and expiration.
- Card number masked (`•••• •••• •••• 1234`), CVV excluded from persistent storage.
- Idempotency key enforced to prevent double card generation on network retries.

### 2.2 Captured Phase (`TYPE_CAPTURE`)
- Executed when airline issues ticket confirmation.
- Amount captured cannot exceed authorized limit.
- Verified in `PaymentSettlementService.capturePayment()`.

### 2.3 Void & Refund Phase (`TYPE_VOID` / `TYPE_REFUND`)
- If downstream GDS ticketing fails, the saga compensation worker triggers `voidAuthorization()` to cancel the card hold immediately.
- If a post-ticketing cancellation occurs, `refundCapturedPayment()` initiates refund reconciliation.

---

## 3. Financial Reconciliation Engine Verification

The automated reconciliation worker (`PaymentSettlementService.runFinancialReconciliation()`):
1. Queries all unreconciled transactions (`reconciled = false`).
2. Compares transaction amount against the virtual card authorized limit.
3. If `amount > card.limit`, transitions transaction to `MANUAL_REVIEW_REQUIRED` and emits metric `flyo.payment.discrepancy.card_limit_exceeded`.
4. If valid, flags transaction as `reconciled = true` with audit timestamp.

---

## 4. Payment Certification Verdict

**PAYMENT ARCHITECTURE: CERTIFIED HARDENED & AUDITED**  
- PCI DSS v4.0 hygiene: Zero plaintext PAN or CVV storage verified.
- Fail-closed behavior: Calling capture or void against unconfigured Stripe credentials throws `ProviderNotConfiguredException`.
- Treasury boundary clearly documented between outbound VCC issuance and inbound passenger payment collection.
