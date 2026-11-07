# Flyo Integration Security, Privacy, and Regulatory Compliance

**Document Status:** Enterprise Security & Legal Architecture Reference  
**Author:** Principal Security Architect & Aviation Compliance Specialist  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Regulatory Landscape Overview

Deploying Flyo into an airline or travel management enterprise requires simultaneous adherence to global data privacy laws, regional aviation safety and passenger welfare regulations, and financial payment security standards:

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                             REGULATORY COMPLIANCE PILLARS                              │
 │                                                                                        │
 │  ┌─────────────────────────┐  ┌─────────────────────────┐  ┌─────────────────────────┐ │
 │  │    DATA PRIVACY LAWS    │  │   AVIATION PASSENGER    │  │    PAYMENT CARD DATA    │ │
 │  │                         │  │       PROTECTION        │  │        SECURITY         │ │
 │  │ ─ India DPDP Act 2023   │  │ ─ DGCA CAR Sec 3 Ser M │  │ ─ PCI-DSS v4.0 Level 1  │ │
 │  │   & 2025 Notified Rules │  │   Part IV (India)       │  │ ─ Tokenized VCC Proxy   │ │
 │  │ ─ EU GDPR (EC 2016/679) │  │ ─ EU Regulation 261/2004│  │ ─ Zero Plaintext Card / │ │
 │  │ ─ Data Fiduciary vs     │  │ ─ US DOT 14 CFR Part 259│  │   CVV in Flyo Database  │ │
 │  │   Data Processor Bounds │  │   Passenger Care Rules  │  │                         │ │
 │  └─────────────────────────┘  └─────────────────────────┘  └─────────────────────────┘ │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

> [!IMPORTANT]
> **Legal Notice:** This document establishes engineering architecture and operational controls for technical compliance. It does not constitute formal legal counsel. Enterprise deployments require execution of a bilateral Data Processing Agreement (DPA) reviewed by the client's legal department.

---

## 2. India DPDP Act 2023 & Notified DPDP Rules 2025 Compliance

Under India's **Digital Personal Data Protection Act, 2023** and the **DPDP Rules 2025** notified by the Ministry of Electronics and Information Technology (MeitY):

### 2.1. Fiduciary vs. Processor Role Segregation
* **The Client (Airline / TMC / OTA) is the Data Fiduciary:** The client determines the purpose and means of collecting passenger personal data during ticket booking.
* **Flyo is the Data Processor:** Flyo processes passenger data strictly on behalf of the Data Fiduciary under a formal, written Data Processing Agreement (DPA). Flyo never uses passenger data for its own commercial purposes, model training across tenants, or external marketing.

### 2.2. Standalone Consent Notice for Disruption Outreach
Under the DPDP Rules 2025, consent notices must be standalone, clear, and itemized:
* When a passenger books a flight, the airline's booking flow must present an opt-in notice:
  > *"I consent to receiving real-time flight disruption alerts and automated rebooking options via WhatsApp / SMS powered by the airline's disruption recovery service."*
* Flyo's WhatsApp bot enforces this by presenting a verified service greeting with a one-click opt-out option (`Reply STOP to cancel automated recovery alerts`).

### 2.3. Data Subject Rights & Automated Erasure
* **Right to Erasure:** Upon completion of a disruption case (flight landed and ticket closed), Flyo initiates a 30-day retention countdown. After 30 days, all passenger contact numbers and names are permanently purged, leaving only anonymized operational metrics (delays, airport codes, rebooking timestamps) for SLA reporting.
* **Grievance Redressal:** Flyo provides automated data export and erasure APIs accessible by the client's designated Data Protection Officer (DPO).

---

## 3. DGCA CAR Section 3, Series M, Part IV Compliance

In India, passenger welfare during disruptions is strictly regulated by the **Directorate General of Civil Aviation (DGCA)** under **Civil Aviation Requirements (CAR) Section 3, Series M, Part IV**:

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                     DGCA CAR MANDATED PASSENGER WELFARE MATRIX                          │
├─────────────────────┬───────────────────────────┬───────────────────────────────────────┤
│ Disruption Type     │ Operational Threshold     │ Mandated Airline Facility / Relief    │
├─────────────────────┼───────────────────────────┼───────────────────────────────────────┤
│ Flight Delay        │ Block time up to 2.5 hrs, │ Free meals and refreshments matching  │
│                     │ Delay >= 2 hours          │ the waiting time                      │
├─────────────────────┼───────────────────────────┼───────────────────────────────────────┤
│ Flight Delay        │ Block time 2.5 to 5 hrs,  │ Free meals and refreshments           │
│                     │ Delay >= 3 hours          │                                       │
├─────────────────────┼───────────────────────────┼───────────────────────────────────────┤
│ Flight Delay        │ Block time > 5 hrs,       │ Free meals and refreshments           │
│                     │ Delay >= 4 hours          │                                       │
├─────────────────────┼───────────────────────────┼───────────────────────────────────────┤
│ Overnight Delay     │ Delay spans overnight or  │ Free hotel accommodation, including   │
│                     │ > 24 hours from schedule  │ airport ground transfers              │
├─────────────────────┼───────────────────────────┼───────────────────────────────────────┤
│ Extended Delay      │ Domestic flight delayed   │ Mandatory option of: Alternate flight │
│                     │ by more than 6 hours      │ OR full ticket refund                 │
├─────────────────────┼───────────────────────────┼───────────────────────────────────────┤
│ Flight Cancellation │ Cancelled with notice     │ Alternate flight within 2 hours OR    │
│                     │ between 24h and 2 weeks   │ compensation (₹5,000 to ₹10,000)      │
├─────────────────────┼───────────────────────────┼───────────────────────────────────────┤
│ Flight Cancellation │ Cancelled with notice     │ Alternate flight within 1 hour OR     │
│                     │ less than 24 hours        │ compensation (₹5,000 to ₹10,000)      │
└─────────────────────┴───────────────────────────┴───────────────────────────────────────┘
```

### 3.1. Flyo's Automated DGCA Compliance Engine
Flyo integrates these regulatory rules directly into the case management state machine:
1. **Automated Refreshment Voucher Trigger:** When a flight delay crosses the 2-hour threshold (or 3-hour for long sectors), Flyo automatically notifies the airport ground team and transmits digital meal vouchers to the passenger's WhatsApp.
2. **Automated Hotel Care Trigger:** When a disruption requires an overnight stay, Flyo marks `hotel_risk = 'CONFIRMED'` and automatically books partner airport hotels through corporate hospitality APIs.
3. **Extraordinary Circumstances Audit Log:** If the disruption is caused by weather (e.g., Delhi fog CAT III), Flyo records the meteorological telemetry in the immutable audit log, legally protecting the airline from unjustified compensation claims while ensuring mandatory passenger on-ground care is fulfilled.

---

## 4. Payment Security & Elimination of PCI Risks

> [!CAUTION]
> **Existing Codebase Vulnerability:** In Flyo's initial prototype database (`flyo-rescue-demo/backend/src/main/resources/db/migration/V1__initial_production_schema.sql`), the `virtual_cards` table defines:
> `card_number VARCHAR(32)`, `expiry VARCHAR(16)`, `cvv VARCHAR(32)`!
> **Storing plaintext Primary Account Numbers (PAN) and Card Verification Values (CVV) violates PCI-DSS Requirement 3.2 and exposes the company to severe regulatory penalties and immediate merchant account revocation.**

### 4.1. PCI Remediation Architecture
In the enterprise production architecture:
1. **CVVs Are Never Persisted:** CVVs are strictly held in volatile memory only during the single HTTP transaction dispatching the payment, then immediately zeroed out.
2. **Tokenized Corporate Issuing:** Flyo integrates exclusively with PCI-DSS Level 1 certified virtual card issuers (Stripe Issuing, Lithic, or Direct Commercial Bank APIs like HDFC/ICICI Corporate Banking). Flyo stores only the non-sensitive card token (`card_token_id: "tok_issuing_1234"`), the last 4 digits (`"1234"`), and the cardholder name.
3. **Single-Use Merchant Category Locking:** Every virtual card issued by Flyo is locked to Merchant Category Code (MCC) `3000–3299` (Airlines) or `7011` (Hotels) with an exact spend ceiling and an automatic 2-hour expiration timer.

---

## 5. Defense-in-Depth Cryptographic Architecture

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        CRYPTOGRAPHIC DATA ENCRYPTION TIERS                             │
├────────────────────┬─────────────────────────────┬─────────────────────────────────────┤
│ State              │ Algorithm                   │ Key Management                      │
├────────────────────┼─────────────────────────────┼─────────────────────────────────────┤
│ Data in Transit    │ TLS 1.3 / mTLS (X.509)      │ AWS ACM / Let's Encrypt (90-day rot)│
│ Core Database      │ AES-256-GCM (TDE)           │ AWS KMS / Azure Key Vault Envelope  │
│ PII Masking Column │ AES-256-SIV (Deterministic) │ Tenant-Dedicated Cryptographic Key  │
│ Passenger OTP      │ HMAC-SHA256 (6-digit salt)  │ Volatile Redis (10-minute expiry)   │
│ Audit Trail Sign   │ Ed25519 Cryptographic Sign  │ Flyo Master Audit Signing HSM Key   │
└────────────────────┴─────────────────────────────┴─────────────────────────────────────┘
```

* **Immutable Append-Only Audit Logging:** Every operator action, state machine transition, and passenger OTP confirmation is hashed and linked to the previous log entry using a cryptographic hash chain. This ensures that audit records cannot be altered retroactively during regulatory inquiries or legal disputes.
