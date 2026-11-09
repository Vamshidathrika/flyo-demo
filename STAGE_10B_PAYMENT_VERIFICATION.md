# STAGE 10B: PAYMENT & TREASURY PROVIDER VERIFICATION REPORT
**Flyo Disruption Management & Automated Passenger Rescue Platform**  
**Audit Date:** November 8, 2026 | **Focus:** PCI DSS v4.0 Hygiene, Double-Entry Accounting & Gateway Isolation

---

## 1. Quad-Tier Payment Architecture Review

To prevent financial leakage and ensure regulatory compliance, Flyo Rescue cleanly separates payments across **four distinct architectural tiers**:

```
+---------------------------------------------------------------------------------------------------+
|                                  FLYO FOUR-TIER PAYMENT ARCHITECTURE                              |
+---------------------------------------------------------------------------------------------------+
|  1. INBOUND MERCHANT ACQUIRING   | Stripe Payments (PaymentIntents, 3DS2, Apple Pay, Cards)       |
|                                  | -> Collects passenger co-payments or fare differentials.       |
|----------------------------------+----------------------------------------------------------------|
|  2. OUTBOUND TREASURY ISSUING    | Stripe Issuing (Virtual Corporate Mastercard / Visa VCC)       |
|                                  | -> Disburses approved rescue budgets directly to airlines.     |
|----------------------------------+----------------------------------------------------------------|
|  3. AIRLINE SETTLEMENT           | IATA BSP / ARC Direct Airline Merchant Settlement (MCC 3000)   |
|                                  | -> Airline captures authorized VCC funds on ticket issuance.   |
|----------------------------------+----------------------------------------------------------------|
|  4. INTERNAL FINANCIAL LEDGER    | PostgreSQL Double-Entry Ledger (`payment_transactions`)        |
|                                  | -> Authorize, Capture, Void, Refund audit trail & reconcile.   |
+---------------------------------------------------------------------------------------------------+
```

---

## 2. Operation-by-Operation Provider Evidence Matrix

| Payment Operation | Architectural Tier | Target Provider API | Current State in Codebase | Provider-Side Evidence Status | Audit Classification |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Issuing Card Creation** | Outbound Treasury | `POST /v1/issuing/cards` | `StripeIssuingPaymentAdapter.issueVirtualCard` | **BLOCKED IN PROD**; Fails closed with `ProviderNotConfiguredException`. Sandbox VCC token verified in test mode. | **BLOCKED_BY_CREDENTIALS** |
| **Card Authorization Hold** | Outbound Treasury | Card auth webhook (`issuing_authorization.request`) | `PaymentSettlementService.authorizePayment` | Verified in sandbox adapter with masked PAN, strict amount ceiling, and ISO 4217 currency checks. | **CONTRACT_TESTED** |
| **Card Transaction Capture** | Airline Settlement | Card settlement webhook (`issuing_transaction.created`)| `PaymentSettlementService.capturePayment` | Pre-flight provider assertion ensures capture delegates to gateway. Fails closed if Stripe is unconfigured. | **CONTRACT_TESTED** |
| **Authorization Void** | Outbound Treasury | Card cancellation API (`POST /v1/issuing/cards/{id}`) | `PaymentSettlementService.voidAuthorization` | Calls `paymentAdapter.voidAuthorization()`. Marks VCC as `VOIDED` during saga rollback. | **CONTRACT_TESTED** |
| **Post-Settlement Refund** | Outbound Treasury | Credit balance refund | `PaymentSettlementService.refundCapturedPayment` | Reconciles refund reference in financial ledger. Fails closed if adapter is unconfigured. | **CONTRACT_TESTED** |
| **Inbound Customer Charge**| Inbound Acquiring | `POST /v1/payment_intents` | N/A (Architecture formalized) | Customer charges currently absorbed by airline policy or corporate contract; merchant gateway pending. | **NOT_IMPLEMENTED** |
| **Automated Reconciliation**| Internal Ledger | Batch reconciliation job | `PaymentSettlementService.runFinancialReconciliation` | Compares transaction amount against card authorization limit. Flags discrepancies to `MANUAL_REVIEW`. | **UNIT_TESTED** |

---

## 3. PCI DSS v4.0 Hygiene Verification

Static analysis and schema inspection verified compliance with **PCI DSS Requirement 3.2**:
- **Zero CVV/CVC Storage**: Neither the database schema (V1–V9) nor Java entity classes (`VirtualCard`, `PaymentTransaction`) define columns for CVV. CVV is never passed to or stored in persistent storage.
- **Masked PAN Display**: All virtual card records store only the last 4 digits (`last_four`) and a provider token reference (`tok_sandbox_...`). Plaintext 16-digit Primary Account Numbers are never persisted.
- **Merchant Category Code (MCC) Enforcement**: Issued virtual cards are programmatically restricted to Airline Merchant Category Codes (`3000` through `3350`), preventing card misuse at hotels, retail, or unauthorized merchants.

---

## 4. Payment Certification Verdict

**PAYMENT ARCHITECTURE: AUDITED & COMPLIANT WITH FAIL-CLOSED BOUNDARIES**  
- Production profile safely blocks execution if `STRIPE_SECRET_KEY` is absent.
- The separation between supplier VCC issuing and passenger acquiring is established in the code and data model.
- Double-entry accounting ensures that every transaction corresponds to an authorized, captured, voided, or refunded state.
