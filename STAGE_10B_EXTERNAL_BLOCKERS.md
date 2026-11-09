# STAGE 10B: EXTERNAL BLOCKERS, THIRD-PARTY DEPENDENCIES & COMMERCIAL PREREQUISITES
**Flyo Disruption Management & Automated Passenger Rescue Platform**  
**Audit Date:** November 9, 2026 | **Classification:** Commercial Readiness & External Integration Gates

---

## 1. Executive Summary

While Flyo Rescue's software architecture, distributed saga engine, dual-tier payment reconciliation, and provider adapters are verified and operational against live sandboxes, **live physical ticket issuance and real-world passenger rebooking require external commercial licenses, accredited ticketing authority, and payment acquiring setups**.

This register catalogs every external commercial dependency, the specific third-party provider, estimated SLA/turnaround time, and technical safeguards.

---

## 2. External Blocker Register

| ID | Third-Party Provider | Requirement / Dependency | Technical Impact if Missing | Safe Operational Fallback | Estimated Turnaround | Business Owner |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **BLK-01** | **Amadeus IT Group** | Live Enterprise GDS / NDC Production Contract & Production API Keys | Order execution works in sandbox; live seat inventory cannot be held or purchased in production. | System halts rebook at `ORDER_CREATED` or routes to human dispatcher with Altéa terminal access. | 3–6 Weeks | BizDev / Partnerships |
| **BLK-02** | **IATA / ARC** | IATA Numeric Code or ARC Agency Accreditation | Cannot issue electronic flight tickets (ETKT, Form of Payment BSP/ARC). System sets ticket to `UNVERIFIED-IN-SANDBOX`. | Operates under Host Agency / TMC partner ticketing authority (e.g., BCD Travel, Amex GBT). | 4–8 Weeks | Legal / Airline Relations |
| **BLK-03** | **Stripe Treasury & Issuing** | Commercial Stripe Issuing Production Account & KYB Verification | Cannot issue live Visa/Mastercard virtual cards loaded with corporate funds for airline settlement. | Direct airline agency billing or GDS monthly BSP settlement. | 1–2 Weeks | Finance & Operations |
| **BLK-04** | **Stripe Payments** | Production Stripe PaymentIntents / Elements Merchant Account | Cannot collect customer copayments for cabin upgrades or fare difference surplus. | Disruption rescues restricted to airline-funded or corporate-guaranteed fare tiers. | 3–5 Days | Finance |
| **BLK-05** | **Meta / Twilio** | Verified WhatsApp Business Account (WABA) & Registered Interactive HSM Templates | Outbound WhatsApp messages will fail or be restricted to pre-registered sandbox recipients. | Outbound SMS via Twilio or transactional email via SendGrid/SES. | 5–10 Days | Product / Marketing |
| **BLK-06** | **Aviation Radar Provider** (FlightAware / AviationStack) | Enterprise Aviation ADS-B Polling License | Aviation tracking queries return `Optional.empty()`. | Flight disruptions ingested via Airline Operational Control Center (AOCC) webhooks or PNR change queue. | 2–3 Days | Engineering |

---

## 3. Commercial Onboarding Roadmap & Workarounds

```
[ PHASE A: CONTRACTING ]              [ PHASE B: ONBOARDING ]              [ PHASE C: GO-LIVE ]
Amadeus Enterprise Agreement ------> Production Credentials In Vault ------> Automated NDC Orders
IATA / Host Agency Partner   ------> Ticketing Authority Assigned    ------> Real ETKT Issuance
Stripe KYB & Balance Reserve ------> Live Issuing API Active         ------> Live VCC Settle
Meta Business Verification   ------> Approved WhatsApp Templates     ------> Live Passenger Rebook
```

### 3.1 Host Agency Interim Model (Recommended Mitigation)
To circumvent the 6–8 week lead time for an independent IATA numeric code:
1. Flyo enters into a Host Agency Agreement with an existing IATA-accredited Travel Management Company (TMC).
2. The TMC provisions an Amadeus Office ID (`HYD1A2xxx`) mapped to Flyo's Amadeus Enterprise API credentials.
3. Flyo creates the PNR and issues orders under the TMC's ticketing authority; ticketing fees settle via the TMC's existing BSP/ARC account.
4. This reduces time-to-market from 8 weeks to under 10 business days.

---

## 4. Codebase Boundary Guarantee

- **Zero Hardcoded Bypasses**: No provider credentials are baked into git commits or configuration defaults.
- **Fail-Closed Adapters**: When live production credentials are absent, adapters explicitly report unconfigured status or throw structured exceptions rather than fabricating mock airline confirmations.
- **No Refactoring Needed**: When production credentials become available, they are injected via Kubernetes secrets without altering application code or rebuilding binaries.
