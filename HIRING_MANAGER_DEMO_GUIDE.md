# FLYO RESCUE — EXECUTIVE HIRING MANAGER DEMO GUIDE (STAGE 10B.1)
**Autonomous Airline Disruption Recovery & Passenger Rescue Engine**  
**Audience**: Executive / Engineering Hiring Manager Presentation

---

## 1. Executive Summary & Honest System Positioning

> **Core Positioning**:  
> *"Flyo identifies disrupted passengers, evaluates alternative itineraries using deterministic connection-safety rules and predictive intelligence, obtains operator approval via a 2FA-secured gate, and executes the configured provider workflow. The local demonstration uses a mock airline portal to illustrate the complete browser checkout workflow; the production architecture supports separately configured provider integrations (Amadeus GDS / NDC Direct)."*

### Architectural Execution Modes

Flyo Rescue supports three strictly isolated provider execution modes:

| Execution Mode | Environment | Airline Provider Workflow | Treasury / Payment Workflow | PNR & E-Ticket Origin |
| :--- | :--- | :--- | :--- | :--- |
| **`MOCK_DEMO`** | Local / Demo (`demo` profile) | Headless Playwright browser automation against Flyo's mock carrier extranet | Simulated Corporate VCC (Stripe Issuing Sandbox test instrument) | Simulated Demo PNR (`R7X2K9`) extracted from mock portal DOM |
| **`SANDBOX_PROVIDER`** | Staging / Pre-Prod | Amadeus Altéa GDS / NDC Sandbox API orders | Stripe Issuing Sandbox API with test card tokens | Certified Sandbox GDS Order & PNR |
| **`LIVE_PROVIDER`** | Production (`prod` profile) | Direct NDC Direct-Connect / Certified Carrier GDS API | Live Corporate Card Line & Settlement Reconciliation | Real Commercial Airline PNR & Issued E-Ticket |

---

## 2. Quick Launch (One Command)

From the project root directory, run:
```bash
./start-demo.sh
```

### What this command executes:
1. Safely checks ports `8080` and `8000`: Gracefully terminates any existing Flyo processes (refusing to touch non-Flyo PIDs).
2. Starts the **Python ML Microservice** on port `8000` (FastAPI + Uvicorn) with dual Random Forest and Gradient Boosting models.
3. Launches the **Spring Boot 3.3.4 Backend** with explicit **`--spring-boot.run.profiles=demo`**:
   - Enables safe in-memory H2 database with all Flyway V1-V10 migrations.
   - Activates demo-scoped seeding and isolated `/api/auth/demo-session` authentication.
   - Verifies Actuator `/actuator/health` reporting `UP`.
4. Opens the browser console automatically at **`http://localhost:8080`**.

---

## 3. The 5-Minute "Hero Story" Walkthrough Script

Follow these 5 steps in order for maximum technical impact:

### STEP 1: Trip Monitor & Disruption Ingestion
1. Open the console at **`http://localhost:8080`** (Trip Monitor tab).
2. **Talking Point**:
   > *"Flyo Rescue is an autonomous disruption recovery platform designed for airline operations. It continuously monitors passenger itineraries in real time. Under the hood, it pairs deterministic connection safety engines with ML predictive models and headless browser automation."*
3. Point out the **Header Status Bar**:
   - Highlight the **`[DEMO MODE: Simulated Operations]`** badge: Shows active profile isolation.
   - Operator badge shows **`System Admin (ROLE_ADMIN)`** authenticated via demo session.
4. Point out the top **Pre-Made Adoptive Scenario Templates**:
   - Scenario 1: **Hero (International Misconnect — Ananya Sharma)**
   - Scenario 2: **Domestic Urgent Reschedule (Rohit Verma)**
   - Scenario 3: **Carrier Gateway Failover (Airline 500 Outage & Exponential Retries)**
5. Click **`Run Hero Automation →`** on Scenario 1.

---

### STEP 2: Deterministic Impact Calculation & Rule-Based Ranking
1. The console automatically routes to the **Case Recovery** tab (showing an Active badge).
2. **Talking Point**:
   > *"Notice the automated impact assessment: Flight `AI-839` (HYD → DEL) was cancelled. The passenger, Ananya Sharma, has a critical onward connection `DEL → Toronto (YYZ)` tomorrow morning, plus a non-refundable Delhi hotel night. If she misses that connection, the carrier faces severe statutory compensation liabilities and hotel claims."*
3. Point to the **Ranked Rebooking Options**:
   - **Option O2 (FL301 via Mumbai)** is ranked **Rank 1**: It saves the Toronto connection with a safe **7h 50m connection buffer** (+₹4,200 fare adjustment).
   - **Option O3 (AI204 direct)** is ranked **Rank 3** and disqualified from Rank 1 because a **45-minute buffer** at Delhi T3 violates the mandatory 120-minute international safety rule.
4. **Key Engineering Answer**:
   > *"Why code instead of an LLM deciding ranking? Because LLMs hallucinate timezone math, transit time, and money. In flight operations, financial liability and connection safety must be mathematically deterministic."*

---

### STEP 3: 2FA Operator Approval Gate & Stripe Issuing Sandbox VCC
1. Point to the **2FA Security Gate**:
   - Rebooking attempts before approval are strictly blocked by Spring Security.
2. Click **`Request WhatsApp OTP`** (or view the simulated WhatsApp phone mockup on the right).
   - Point out the **`[SIMULATED DEMO CONVENIENCE]`** disclaimer: Explain that in production, OTPs are delivered out-of-band and manually submitted; auto-prefill is purely for walkthrough fluidity.
3. Click **`Verify & Unlock Gate`** (or tap Enter):
   - The gate verifies the OTP and unlocks Option O2 for authorized checkout.
4. Point to the **Corporate Virtual Credit Card (VCC) Tray**:
   - Visibly marked as **`[SIMULATED VCC — STRIPE ISSUING SANDBOX]`**.
   - Highlights strict **PCI Masking** (`•••• •••• •••• 8821`), single-use spend cap (+₹4,200), and **Merchant Category Code (MCC) lock to `3000 (Airlines)`**.

---

### STEP 4: Headless Playwright Browser Rebooking & Demo PNR Issuance
1. Click **`Execute Rebooking (Playwright)`**.
2. Watch the live execution banner:
   - In ~2 seconds, a Playwright headless browser session navigates Flyo's mock airline portal, fills passenger details, submits the virtual card payment form, and extracts confirmed demo PNR: **`R7X2K9`**.
3. Point to the **Rebooking Success Card**:
   - Prominently labeled with **`EXECUTION MODE: MOCK_DEMO`** and **`Simulated Demo PNR: R7X2K9 [MOCK PORTAL AUTOMATION]`**.
   - Shows the 4 audit screenshots captured during browser execution.
4. Point to the **Phone Mockup on the right column**:
   - Ananya Sharma receives an official WhatsApp confirmation with the updated itinerary and boarding pass link.
5. Point to the **Idempotency Guarantee**:
   - Clicking rebook again returns the identical confirmed PNR in < 10ms without duplicate carrier reservations or payment authorizations.

---

### STEP 5: ML Observability & Chronological Audit Ledger
1. Click the **Audit Logs** tab:
   - Walk through the append-only chronological ledger: Disruption Detected → Multi-Source Reconciliation → Options Ranked → 2FA OTP Verified → VCC Issued → Rebooking Completed.
2. Click the **ML Observability** tab:
   - Showcase the dual-model ML pipeline: delay prediction, connection risk classifier, airport disruption heatmap, and feature importance SHAP values.
3. Click the **Operations Control** tab:
   - Show live operational KPIs, provider health status, distributed rate limits, and circuit breaker metrics.

---

## 4. High-Value Answers to Anticipated Hiring Manager Questions

| Anticipated Question | Engineering Response |
| :--- | :--- |
| **"Why not just use an LLM Agent to rebook directly?"** | LLMs are probabilistic. An LLM can hallucinate terminal walking times, miscalculate timezones, or book invalid flights. In Flyo, deterministic business rules calculate risk and money, while LLMs and ML are used where they excel: unstructured PNR parsing, delay forecasting, and customer communication. |
| **"Is the PNR R7X2K9 an actual airline ticket?"** | In this local demonstration, `R7X2K9` is a simulated demo PNR generated by Playwright browser automation against Flyo's mock airline extranet. The demo proves end-to-end browser checkout, form automation, DOM extraction, and screenshot auditing. For production, Flyo connects to certified Amadeus Altéa NDC/GDS APIs. |
| **"Why is the OTP pre-filled in the UI?"** | For demo fluidity only. We have clearly labeled it as `[SIMULATED DEMO CONVENIENCE]`. In production environments, 2FA OTP codes are delivered out-of-band to operator or passenger devices and must be manually input. Moreover, OTP verification alone never triggers execution without authenticated operator sign-off. |
| **"How is database seeding isolated from production?"** | Seeding is protected by two non-negotiable gates: (1) strictly positive profile activation `@Profile({"dev", "demo", "test"})` (never negative `@Profile("!prod")`), and (2) an explicit configuration property `@ConditionalOnProperty("flyo.seeding.enabled")`. Under `prod`, `staging`, or `default`, seeding beans are never created, leaving the database completely clean. |
| **"How do you handle carrier portal timeouts or errors?"** | Resilient exponential retry (up to 3 attempts). If the carrier 500s or crashes (which you can demo live with Scenario 3), Flyo transitions to `FAILED_ESCALATED`, sets `newPnr = null`, captures an automated error screenshot, and triggers immediate manual airline desk escalation. It never fakes success. |
| **"How does the system scale across multiple airlines?"** | Flyo uses a provider adapter pattern (`FlightBookingProvider`). It supports headless Playwright automation for legacy airline extranets, Amadeus Altéa GDS/NDC APIs for direct integrations, and Stripe Issuing for treasury virtual card settlements. |
| **"Is the architecture certified?"** | Yes. Stage 10 completed independent certification: 317+ automated tests pass across backend (275+ Java tests including profile security boundary tests), Python ML (36 tests), frontend (3 tests), and forensic audit scripts. |
