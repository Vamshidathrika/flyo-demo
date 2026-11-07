# STAGE 6 — PAYMENT LIFECYCLE & FINANCIAL LEDGER RECONCILIATION

## 1. Executive Summary
In airline disruption rebooking, financial operations involve substantial risk: duplicate authorizations, double-charges, orphan virtual cards, and payment gateway mismatches. In Stage 6, Flyo's payment settlement subsystem was hardened with a 2-phase authorization/capture/void/refund model, immutable ledger-style transaction persistence, tenant-scoped idempotency keys, scheduled discrepancy reconciliation, and strict PCI DSS data protection controls.

## 2. Two-Phase Payment Lifecycle
Flyo separates fund reservation from actual settlement:
1. `AUTHORIZATION`: Reserves funds when an alternative option is approved by a supervisor. Returns an `auth_code`.
2. `CAPTURE`: Executes only after the airline provider returns an authoritative PNR and e-ticket number.
3. `VOID`: Released immediately if downstream airline booking fails or inventory is rejected during checkout.
4. `REFUND`: Initiated if cancellation occurs post-capture or manual intervention cancels the rescue mission.

## 3. Financial Ledger & Webhook Idempotency
- **Optimistic Concurrency on Ledger Entries**: The `PaymentTransaction` JPA entity is protected by `@Version`. Duplicate concurrent capture or settlement calls fail immediately with optimistic locking exceptions.
- **Tenant-Scoped Idempotency Keys**: Unique index `idx_payment_idem_tenant` on `(idempotency_key, tenant_id)` guarantees that webhook replays from payment processors (Stripe, Adyen, Banking APIs) return cached success without double-crediting or duplicate debits.
- **Scheduled Reconciliation Worker (`PaymentSettlementService.reconcileDiscrepancies()`)**: Runs periodically to detect pending authorizations older than 15 minutes without corresponding carrier bookings, voiding expired holds and reporting mismatches to the `flyo.payment.discrepancies` metric.

## 4. PCI DSS Compliance & Data Security Guardrails
- **Zero Raw Cardholder Data**: Flyo strictly avoids storing CVV, card verification codes, or unmasked PANs (Primary Account Numbers).
- **Tokenization Only**: All Virtual Credit Cards (VCC) are referenced via external token identifiers (`cardTokenId`, `last4`, and `expiryDate`).
- **Audit Logging Redaction**: All card numbers in API requests and MDC logs are masked (`XXXX-XXXX-XXXX-1234`).

## 5. Test Verification Evidence (`Stage6PaymentAndReconciliationTest.java`)
- `testTwoPhaseAuthorizationAndCaptureWorkflow()`: Verified separation of `AUTHORIZED` and `CAPTURED` states with auth and capture codes.
- `testAuthorizationVoidOnBookingFailure()`: Verified automatic transition to `VOIDED` with audit event on saga backward compensation.
- `testPaymentIdempotencyKeyPreventsDuplicateCapture()`: Verified duplicate capture requests with identical idempotency key return original transaction without additional charges.
- `testReconciliationJobDetectsDiscrepanciesAndVoidsStaleAuthorizations()`: Verified automated background sweeper identifies and voids orphan authorizations.
- `testOptimisticLockingPreventsConcurrentDoubleSettlement()`: Verified concurrent captures result in optimistic locking conflict.
- `testSensitiveCardDataNotLoggedOrPersistedInPlaintext()`: Verified CVV is never persisted and card identifiers are masked.

**Result: 6/6 Tests Passed Cleanly.**
