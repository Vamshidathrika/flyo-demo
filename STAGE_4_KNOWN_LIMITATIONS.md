# FLYO — STAGE 4 KNOWN LIMITATIONS & OPERATIONAL BOUNDARIES
## Honest Engineering Assessment of Unresolved Gaps & External Dependencies

**Classification:** Flyo Engineering Transparency Document  
**Version:** 4.0.0-PROD-STAGE4  
**Date:** November 2026  
**Audited Location:** Entire Flyo Codebase

---

## 1. External Provider Connectivity Limitations

### 1.1. Direct GDS / NDC Production Credentials Missing
- **Current State:** Flyo does not possess direct production credentials for live airline GDS systems (Sabre Sonic, Amadeus Altéa Production, or airline NDC aggregators).
- **Impact:** Rebooking execution currently operates against verified sandbox adapters (`PlaywrightMockAirlineAdapter` and Amadeus Altea test sandboxes). Production deployment requires an accredited TMC partnership, IATA number, or airline API aggregator agreement.
- **Classification:** `NOT_CONNECTED_BLOCKED` for direct production GDS.

### 1.2. US BTS Machine Learning Model Airspace Boundary
- **Current State:** The predictive ML service is trained exclusively on US Department of Transportation Bureau of Transportation Statistics historical on-time data (334 US domestic airports).
- **Impact:** The ML service cannot predict delays or cancellations for Indian, European, or Asian domestic carriers. Flyo correctly guards against this by returning `UNSUPPORTED_AIRSPACE` and falling back cleanly to deterministic safety rules and MCT calculation.
- **Remediation Required:** Collecting historical DGCA (India) or Eurocontrol on-time performance data to train region-specific models.

---

## 2. Infrastructure & Automation Boundaries

### 2.1. Headless Browser Automation Overhead (Playwright)
- **Current State:** Rebooking execution through the mock airline portal relies on Playwright headless Chromium.
- **Limitation:** While highly representative of human operator workflows, headless browser automation consumes ~120 MB RAM per browser process and has higher latency ($1.8\text{s}$–$4.2\text{s}$) compared to direct JSON/REST API calls ($150\text{ms}$).
- **Recommendation:** Migrate to direct NDC/EDIFACT APIs as carrier bilateral agreements are finalized.

### 2.2. Human Operator Approval Dependency
- **Current State:** Flyo strictly enforces human approval for every consequential action (`ApprovalRequiredException` if bypassed).
- **Limitation:** In massive irregular operations (e.g. airport-wide grounding with 500+ cancelled flights), human operators become the throughput bottleneck.
- **Future Roadmap:** Implement pre-authorized automated policies (e.g. "Auto-approve if fare delta = ₹0 and arrival delay < 2 hours for Gold tier passengers").

### 2.3. WhatsApp Business API Costs & Rate Limits
- **Current State:** WhatsApp messaging relies on Meta Cloud API conversation-based pricing.
- **Limitation:** High-volume broadcast alerts during major storms incur per-message template fees and are subject to Meta tier rate limits (Tier 1: 1,000 unique recipients per 24 hours).
- **Remediation:** Implement fallback channels (SMS via Twilio, Push notification via mobile app).
