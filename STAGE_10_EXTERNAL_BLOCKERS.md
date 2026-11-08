# STAGE 10: REAL-WORLD EXTERNAL BLOCKERS & PREREQUISITES LOG
**Flyo Disruption Management & Automated Passenger Rescue Platform**  
**Audit Date:** November 8, 2026 | **Classification:** External Dependencies & Commercial Prerequisites

---

## 1. Commercial & External Blocker Taxonomy

Flyo Rescue's technical architecture is completely ready for production deployment. However, before live airline passenger rescues can be executed with real capital and physical flight tickets, several external third-party commercial contracts, licenses, and API credentials must be provisioned.

The table below catalogs every external blocker, the exact external organization responsible, estimated turnaround time, and risk mitigation strategy:

| Blocker ID | External Vendor / Entity | Commercial Dependency | Technical Impact if Missing | Workaround / Mitigation | Turnaround Estimate | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **BLK-01** | **Amadeus IT Group** | Live Enterprise GDS / NDC Production Contract | Cannot book live physical flight seats on commercial carriers. | System operates in `SANDBOX` mode or alerts Human Dispatcher with PNR details. | 2–4 Weeks | **ACTION REQUIRED BY BIZDEV** |
| **BLK-02** | **Stripe Treasury / Issuing** | Commercial Stripe Issuing Production Account | Cannot issue live Visa/Mastercard VCC cards for airline settlement. | System records authorized amount and hands off to Airline Partner Direct Billing agreement. | 1–2 Weeks | **ACTION REQUIRED BY FINANCE** |
| **BLK-03** | **Stripe Merchant Payments** | Stripe PaymentIntents / Checkout Merchant Account | Cannot charge passenger personal credit cards for co-paid fare differences. | Limit disruption rescue options to airline-paid or corporate-absorbed fare differences. | 3–5 Days | **ACTION REQUIRED BY FINANCE** |
| **BLK-04** | **Meta Business Manager** | Verified WhatsApp Business Account (WABA) & Approved HSM Templates | Outbound WhatsApp messages will fail or be rate-limited to sandbox numbers. | Dispatches transactional SMS via Twilio or Email as fallback messaging channels. | 5–7 Days | **ACTION REQUIRED BY PRODUCT** |
| **BLK-05** | **FlightAware / AviationStack** | Commercial Real-Time Aviation Radar Feed License | Radar flight polling falls closed (`Optional.empty()`); no automatic disruption detection. | Disruption events ingested via airline PNR change feed or operations manual webhook. | 1–2 Days | **ACTION REQUIRED BY ENG** |

---

## 2. Actionable Commercial Onboarding Checklist

To transition from **CONDITIONAL GO** to **FULL UNCONDITIONAL GO**, the business and operations teams must complete the following actions:

1. **Amadeus Commercial Contracting**:
   - Contact Amadeus Airline Distribution team.
   - Request IATA/ARC accreditation or partner with an accredited Travel Management Company (TMC) host agency.
   - Obtain Production `client_id` and `client_secret` targeting `https://api.amadeus.com`.

2. **Stripe Issuing Verification**:
   - Complete KYB (Know Your Business) verification on Stripe Dashboard.
   - Deposit initial corporate liquidity reserve into the Stripe Issuing balance.
   - Generate production restricted API key with permissions: `issuing:card:write`, `issuing:transaction:read`.

3. **Meta WABA Registration**:
   - Submit business verification documents to Meta Business Suite.
   - Register Flyo official phone number and register interactive message templates for rebooking.

---

## 3. Summary Statement

No further code modifications are required to enable these integrations. All providers are implemented behind environment-driven adapters that will activate automatically once the corresponding production secrets are supplied.
