# FLYO RESCUE — STAGE 9B: EXTERNAL DEPENDENCIES REGISTER
## Third-Party Provider Credentials, API Contracts & Onboarding Requirements

**Audit Date**: November 8, 2026  
**Auditor**: Antigravity Autonomous Systems Engineering (Stage 9B Integrator)  
**Target Repository**: Flyo Airline Disruption Recovery Platform (`flyo-rescue`)  

---

### External Integration Requirements Matrix

Flyo Rescue integrates with airline GDS, corporate treasury payment issuing, radar flight tracking, aviation weather, and passenger messaging platforms. In production, missing credentials result in an explicit `BLOCKED BY EXTERNAL ACCESS` or `UNAVAILABLE` status.

| Integration | Provider | Purpose | Environment Variables Required | Fail-Closed Status if Absent | Commercial Account Status |
|---|---|---|---|---|---|
| **Airline GDS & NDC** | Amadeus Self-Service / Enterprise | Flight search, flight pricing, order creation, PNR lookup, ticket verification | `AMADEUS_CLIENT_ID`<br>`AMADEUS_CLIENT_SECRET`<br>`AIRLINE_BOOKING_PROVIDER=AMADEUS` | **BLOCKED BY EXTERNAL ACCESS** (`ProviderNotConfiguredException`) | Developer Sandbox active; Commercial Partner Agreement pending |
| **Corporate Virtual Cards** | Stripe Issuing | Single-use virtual card generation, authorization, financial capture | `STRIPE_SECRET_KEY`<br>`VCC_API_KEY`<br>`VCC_WEBHOOK_SECRET`<br>`PAYMENT_VCC_PROVIDER=STRIPE` | **BLOCKED BY EXTERNAL ACCESS** (`ProviderNotConfiguredException`) | Test mode active; Live Commercial Issuing KYC required |
| **Aviation Radar** | Aviationstack / FlightAware | Live ADS-B radar flight tracking, departure/arrival timestamps | `AVIATION_RADAR_API_KEY`<br>`AVIATION_RADAR_PROVIDER=aviationstack` | Returns `Optional.empty()` (Fail-closed) | Free tier active; Commercial real-time feed required |
| **Aviation Weather** | Open-Meteo | Hourly surface temperature, visibility, convective precipitation, wind gusts | None (Open public API) | Returns `WEATHER_TELEMETRY_UNAVAILABLE` on HTTP timeout | Active and operational |
| **Passenger Messaging** | Meta WhatsApp Cloud API | Disruption alerts, interactive recovery options, in-chat OTP authentication | `WHATSAPP_PHONE_NUMBER_ID`<br>`WHATSAPP_ACCESS_TOKEN`<br>`WHATSAPP_VERIFY_TOKEN`<br>`WHATSAPP_APP_SECRET`<br>`WHATSAPP_OPERATOR_PHONE` | Fails closed with delivery error log; never claims fake success | Sandbox verified; Meta Business Verification required for production tier |
| **ML Inference Service** | Flyo FastAPI Service | Disruption probability, P10/P50/P90 delay, TreeSHAP explainability, MCT risk | `FLYO_ML_SERVICE_URL=http://localhost:8000`<br>`FLYO_MONITORING_API_KEY` | Falls back to deterministic schedule buffer; marks ML as `MODEL_UNAVAILABLE` | Self-hosted internal microservice (Fully operational) |

---

### Production Credential Onboarding Runbook

Before transitioning an airline partner into live commercial operations:
1. **Amadeus Enterprise**:
   - Provision Production Client ID & Secret at `https://developers.amadeus.com`.
   - Complete IATA / ARC ticketing agreement option onboarding.
2. **Stripe Issuing**:
   - Complete Stripe Treasury business verification and fund the issuing balance.
   - Configure Stripe Webhook endpoint pointing to `/api/webhooks/payment/stripe`.
3. **Meta WhatsApp Cloud**:
   - Register the official airline sender phone number in Meta Business Manager.
   - Configure webhook endpoint `/api/webhooks/whatsapp` with verified HMAC secret.

### Verdict: DOCUMENTED
All external dependencies, required environment variables, fail-closed boundaries, and commercial prerequisites are cataloged and enforced.
