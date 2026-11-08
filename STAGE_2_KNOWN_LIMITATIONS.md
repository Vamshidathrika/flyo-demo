# Stage 2: Known Operational Limitations & Boundary Disclosures

**Author:** Principal Forward Deployment Engineer  
**Date:** November 6, 2026  
**System:** Flyo Autonomous Disruption Recovery Engine  

---

## 1. External System Boundary Disclosures

In accordance with Flyo's engineering contract ("Never claim an integration is connected when it is not"), the following boundaries are formally disclosed:

| Subsystem / Capability | Current Real-World Limitation | Production Resolution Path |
| :--- | :--- | :--- |
| **Amadeus Altéa PSS** | Contract & DTOs implemented (`AmadeusAlteaAdapter`); returns static test PNR segments. Does not hit live Amadeus 1A Web Services. | Requires client-provided Amadeus BTA (Business Travel Agency) corporate credentials and signed IATA e-ticketing agreement. |
| **Sabre GDS AirCreate** | Contract implemented (`SabreGdsAdapter`); returns `CREDENTIALS_REQUIRED`. | Requires client PCC (Pseudo City Code) and Sabre SWS certificates during client onboarding. |
| **Live Airline Web Portal Rebooking** | Currently automated via Playwright headless Chromium against local mock portal (`/mock-airline`). | Playwright automation is appropriate for local demos and sandbox validation, but live production rebooking should prioritize NDC/GDS APIs over brittle browser scraping. |
| **Stripe Issuing Live Cards** | Adapter implemented (`StripeIssuingPaymentAdapter`); raises `ProviderNotConfiguredException` when live key absent. Uses tokenized `tok_sandbox_*` virtual cards locally. | Requires client Stripe account with Issuing enabled and live `STRIPE_SECRET_KEY`. |
| **Official Meta WhatsApp Cloud API**| Adapter implemented (`MetaWhatsAppAdapter`); messages logged to internal simulated chat ledger. | Requires client Meta Business Manager verification and approved WhatsApp HSM message templates. |
| **Distributed Cache & Locking** | `DistributedRateLimiter` uses concurrent in-memory sliding windows with clean Redis interface. | In multi-node production Kubernetes clusters, backing store should be swapped to Redis Sentinel / AWS ElastiCache. |

---

## 2. Airspace & ML Geographic Boundaries

- **Indian Airspace vs US Airspace**: As established in Phase 3 audits, the BTS ML model was trained on US domestic flights and is strictly disabled for Indian domestic flights (`AI-*`, `6E-*`, `UK-*`). Indian airspace disruptions use deterministic multi-source radar and Open-Meteo weather evidence reconciliation (`FlightEvidenceReconciliationService`), which achieves 93%+ confidence.
