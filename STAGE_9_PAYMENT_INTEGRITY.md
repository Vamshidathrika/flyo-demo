# FLYO RESCUE — STAGE 9: FINANCIAL INTEGRITY & PAYMENT GATEWAY ARCHITECTURE

## 1. Overview
The payment architecture provides PCI-DSS v4.0 compliance, zero plaintext cardholder storage, single-use Virtual Corporate Cards (VCCs), and multi-tenant ledger settlement.

---

## 2. Stripe Issuing Production Adapter (`StripeIssuingPaymentAdapter`)

- **Role**: Primary payment gateway adapter in production environments (`@Primary`).
- **Configuration**: Requires `flyo.payment.stripe.api-key` or `STRIPE_SECRET_KEY`.
- **Fail-Closed Policy**: If credentials are missing, throws `ProviderNotConfiguredException` with reason:
  `"BLOCKED BY EXTERNAL ACCESS: Stripe Issuing API credentials not provided."`
- **PCI Compliance**:
  - Flyo servers never persist card security codes (CVV/CVC) or raw Primary Account Numbers (PANs).
  - Only tokenized card IDs (`ic_xxx`) and last 4 digits are recorded in the `virtual_cards` table.

---

## 3. Financial Transaction Settlement (`PaymentSettlementService`)

### 3.1 Idempotency & Concurrency
Every payment transaction enforces unique idempotency keys via the database constraint:
```sql
CONSTRAINT uk_payment_tx_idem UNIQUE (idempotency_key)
```
Duplicate submission of the same transaction returns the existing authorized record without double billing.

### 3.2 Dual-Entry Audit Ledger
Each authorization and capture writes to `payment_transactions` and links to the parent `cases` and `tenants` records:
- `transaction_id`: UUID primary key.
- `case_id`: Disruption case identifier.
- `tenant_id`: Corporate tenant identifier.
- `amount_cents`: Authorized amount in lowest currency unit.
- `currency`: ISO-4217 (e.g. `INR`, `USD`).
- `status`: `PENDING`, `AUTHORIZED`, `CAPTURED`, `FAILED`, `REFUNDED`.
- `created_at`: ISO-8601 UTC timestamp.
