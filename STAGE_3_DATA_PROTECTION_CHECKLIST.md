# FLYO — STAGE 3 DATA PROTECTION & PRIVACY CHECKLIST
## PCI DSS Scope Reduction, GDPR/DPDP Compliance & Sensitive Data Governance

**Compliance Scope:** PCI DSS v4.0, EU GDPR, India DPDP Act 2023  
**Auditor:** Application Security Architect & Compliance Auditor

---

## 1. Payment Cardholder Data (PCI DSS Scope Reduction)

- [x] **Zero Raw PAN Storage**: Flyo does NOT store primary account numbers (PAN) anywhere in the database schema or filesystem.
- [x] **Zero CVV/CVC Retention**: CVV/CVC storage is permanently banned and prevented by schema constraints (completed in Stage 0/1).
- [x] **Surrogate Tokenization Only**: Virtual cards are represented solely by opaque surrogate tokens (`card_token_id`), masked display numbers (`**** 4242`), and month/year expiry.
- [x] **Outbox & Event Redaction**: Outbox event payloads and audit event logs are strictly filtered to ensure no full card data is staged or transmitted.
- [x] **PCI SAQ Scope Determination**: By delegating card generation to certified issuing processors and storing only tokens, Flyo qualifies for reduced compliance scope (subject to client payment architecture validation).

---

## 2. Passenger Personally Identifiable Information (PII)

- [x] **Data Minimization**: Flyo stores only essential recovery attributes: Passenger Name, Contact Number (E.164), Booking ID, and PNR.
- [x] **Log Redaction**: Passenger phone numbers and email addresses are masked in application standard output (`+91 98765 *****`).
- [x] **Tenant Scoping of PII**: Tenant A operators cannot view or query passenger names, phone numbers, or itineraries belonging to Tenant B.
- [x] **Right to Erasure (GDPR Art. 17 / DPDP Sec. 12)**: System supports cryptographic deletion and anonymization of passenger records upon case completion and audit retention expiration.
- [x] **Purpose Limitation**: Passenger contact data is utilized exclusively for active disruption care and recovery notices, never secondary marketing.
