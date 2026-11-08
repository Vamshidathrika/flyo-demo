# FLYO RESCUE — STAGE 9: EXTERNAL DEPENDENCIES & FAIL-CLOSED BEHAVIOR

## 1. External Dependency Registry

| Dependency | Purpose | Integration Protocol | Unconfigured / Outage Semantics |
| :--- | :--- | :--- | :--- |
| **Amadeus GDS / NDC** | Flight inventory, offer search, flight order booking, electronic ticketing. | REST / IATA NDC 21.3 JSON API | Throws `ProviderNotConfiguredException`. Case marked `FAILED_ESCALATED` or `MANUAL_INTERVENTION_REQUIRED`. **Never creates fake PNR.** |
| **Stripe Issuing** | Virtual Corporate Card (VCC) issuance and airline settlement. | Stripe REST API v1 | Throws `ProviderNotConfiguredException`. Transaction marked `BLOCKED BY EXTERNAL ACCESS`. **Never generates fake cards.** |
| **Aviationstack** | Live radar telemetry, cancellation detection, gate delays. | REST API | Returns `Optional.empty()`. Status set to `STATUS_UNAVAILABLE`. **Never synthesizes fake delay pings.** |
| **Open-Meteo** | Atmospheric aviation weather (METAR, wind gusts, fog, convective storms). | REST API | Returns `WEATHER_TELEMETRY_UNAVAILABLE`. **Never assumes arbitrary 24°C clear skies.** |
| **Meta WhatsApp** | Passenger disruption notification, conversational rescue approvals, OTP verification. | Meta Graph API v19.0 | Webhooks fail signature verification closed. Messages not sent without valid bearer token. **Never sends to hardcoded mock phone.** |
| **Aviation News RSS** | ATC ground stop notifications, runway closures, airspace NOTAMs. | XML / RSS Feed | Returns empty list `List.of()`. **Never fabricates static headline bulletins.** |

---

## 2. Fail-Closed Architectural Contract
In production, Flyo Rescue adheres to the fundamental principle:
> **An external provider failure or missing configuration produces an explicit degraded/unavailable state — never a fabricated successful transaction.**
