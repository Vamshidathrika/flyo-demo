# KNOWN LIMITATIONS & OPERATIONAL BOUNDARIES — FLYO RESCUE AGENT

This document details known limitations, operational boundaries, and data source characteristics of the Flyo Rescue Agent system as of November 5, 2026.

---

## 1. Aviation Data Source Limitations

### 1.1 Open-Meteo Weather Telemetry vs Official METAR/TAF
- **Nature of Source:** Open-Meteo provides high-resolution numerical weather prediction (NWP) model outputs and observational reanalysis. It is **not** an FAA/ICAO certified aviation METAR (Meteorological Aerodrome Report) or TAF (Terminal Aerodrome Forecast) feed.
- **Operational Impact:** Open-Meteo visibility ($< 1000\text{m}$) and WMO weather codes (45/48 for dense fog) accurately flag adverse meteorological conditions (such as winter fog at Delhi DEL), but **cannot be used as legally binding flight dispatch weather**.
- **System Guardrail:** Under no circumstances will adverse Open-Meteo weather trigger an autonomous flight cancellation. Authoritative primary flight status must originate from airline operations webhooks or radar tracking feeds.

### 1.2 Aviation News Intelligence vs NOTAM / ATFM Feeds
- **Nature of Source:** The news intelligence service ingests public RSS feeds (SimpleFlying). It provides general situational awareness (e.g., strikes, terminal works, fleet groundings).
- **Operational Impact:** News intelligence is **not** an operational Notice to Airmen (NOTAM) or Air Traffic Flow Management (ATFM) ground stop feed.
- **System Guardrail:** Headlines are used strictly as explanatory context for human operators and in passenger reassurance messaging. They have zero autonomous veto authority over flight ranking or case execution.

### 1.3 AirAware & FlightAware AeroAPI Access Boundaries
- **Access Constraint:** Commercial aviation APIs such as FlightAware AeroAPI or Cirium require commercial subscription contracts with per-query fees.
- **Current Strategy:** To adhere to zero-paid-API constraints, Flyo implements the `AviationRadarClient` interface using free/sandbox endpoints (AviationStack / local mock radar). For commercial airline enterprise deployment, credentials for FlightAware AeroAPI or SITA FliteInfo can be injected via `AviationRadarConfig`.

---

## 2. Risk Engine & Connection Nuances

### 2.1 Minimum Connection Time (MCT) Context
- **120-Minute Policy:** Flyo applies a strict 120-minute conservative buffer for international connections.
- **Terminal Transfers:** While 120 minutes satisfies published MCT at Delhi T3 (Domestic to International published MCT is 90 minutes), 120–135 minutes is marginal during peak immigration congestion. The engine flags options between 120m and 135m as `MODERATE` risk with specific advisory notes.
- **Inter-Terminal Bus Transfers:** Transfers between Delhi T1/T2 and T3 require a minimum of 150 minutes under Flyo policy. Options involving inter-terminal transit must be manually vetted by operators.

### 2.2 Confidence Index Interpretation
- **Heuristic Index, Not Probability:** The 0–100 confidence value represents an algorithmic multi-source verification score. It is **not** an empirical Bayesian probability. Human operators should interpret $\ge 90$ as confirmed multi-source corroboration and $< 80$ as requiring operator verification.

---

## 3. Rebooking Automation & Carrier Integrations

### 3.1 Headless Browser Automation (Playwright)
- **Portal DOM Fragility:** Playwright automation is designed for partner airline B2B agent portals and the Flyo mock carrier. Live consumer airline portals frequently deploy anti-bot protections (Cloudflare Turnstile, Akamai Bot Manager, CAPTCHA).
- **Enterprise Recommendation:** For production airline distribution, Flyo's `AmadeusNdcBookingAdapter` or `SabreGdsBookingAdapter` should be activated with certified IATA NDC XML/JSON credentials instead of browser DOM automation.

### 3.2 External API Rate Limits
- **Open-Meteo Limits:** Open-Meteo requests are cached with a 5-minute TTL to stay well within public rate limits (10,000 requests/day). Under extreme enterprise burst traffic, deploy an internal Redis cache or direct NOAA AWC METAR mirror.
