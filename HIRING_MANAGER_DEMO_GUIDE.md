# FLYO RESCUE — EXECUTIVE HIRING MANAGER DEMO CHEATSHEET
**Autonomous Airline Disruption Recovery & Passenger Rescue Engine**  
**Demo Target**: Executive / Engineering Hiring Manager Presentation

---

## 1. Quick Launch (One Command)

From the project root directory, run:
```bash
./start-demo.sh
```
This automatically:
- Starts the Python FastAPI ML Predictive Service on port `8000`.
- Starts the Spring Boot 3.3.4 Backend Engine on port `8080`.
- Waits for actuator health probes to report `UP`.
- Automatically opens your browser at **`http://localhost:8080`**.

---

## 2. The 5-Minute "Hero Story" Demo Script

Follow these 5 steps in order for maximum impact:

### STEP 1: Operations Console & Pre-Made Adoptive Presets
1. Open the console at **`http://localhost:8080`** (Trip Monitor tab).
2. **Talking Point**:  
   > *"Flyo Rescue is an autonomous disruption recovery platform designed for airline operations. It monitors active passenger itineraries in real-time. Under the hood, it pairs deterministic connection safety engines with ML predictive models and headless browser automation."*
3. Point out the top **Pre-Made Adoptive Scenario Templates**:
   - Scenario 1: **Hero (International Misconnect — Ananya Sharma)**
   - Scenario 2: **Domestic Urgent Reschedule (Rohit Verma)**
   - Scenario 3: **Carrier Gateway Failover (Airline 500 Outage & Retries)**
4. Click **`Run Hero Automation →`** on Scenario 1.

---

### STEP 2: Deterministic Impact Calculation & Rule-Based Ranking
1. The console instantly routes to the **Case Recovery** tab.
2. **Talking Point**:  
   > *"Notice the automated impact assessment: Flight `AI-839` (HYD → DEL) was cancelled. The passenger, Ananya Sharma, has a critical onward connection `DEL → Toronto (YYZ)` tomorrow morning, plus a non-refundable Delhi hotel night. If she misses that connection, it's a massive compensation liability."*
3. Point to the **Ranked Rebooking Options**:
   - **Option O2 (FL301 via Mumbai)** is ranked **Rank 1**: It saves the Toronto connection with a safe **7h 50m connection buffer** (+₹4,200 fare difference).
   - **Option O3 (AI204 direct)** is ranked **Rank 3** and disqualified from Rank 1 because a **45-minute buffer** at Delhi T3 is below the mandatory 120-minute safety threshold.
4. **Key Engineering Answer**:  
   > *"Why code instead of an LLM deciding ranking? Because LLMs hallucinate timezone math, transit time, and money. In flight operations, financial liability and connection safety must be mathematically deterministic."*

---

### STEP 3: 2FA Human-in-the-Loop Gate & Corporate Virtual Card (VCC)
1. Point to the **2FA Security OTP Gate**:
   - Rebooking attempts before approval are strictly blocked with **HTTP 403 Forbidden**.
2. Click **`Request WhatsApp OTP`** (or look at the simulated WhatsApp phone mockup on the right).
   - The single-use OTP automatically pre-fills into the OTP field.
3. Click **`Verify & Unlock Gate`** (or tap Enter).
4. Point to the **Corporate Virtual Credit Card (VCC) Tray**:
   - Single-use virtual card generated via Stripe Issuing.
   - Merchant Category Code (MCC) locked to `3000 (Airlines)` for ₹4,200.

---

### STEP 4: Headless Playwright Browser Rebooking & Real Airline Confirmation
1. Click **`Execute Rebooking (Playwright)`**.
2. Watch the live execution banner:
   - Playwright launches headless browser, navigates to the carrier portal, selects the approved flight, submits the corporate VCC payment, and extracts the confirmed airline PNR: **`R7X2K9`**.
3. Point to the **Phone Mockup on the right column**:
   - Ananya Sharma instantly receives an official WhatsApp confirmation with the updated itinerary and new boarding pass.
4. Point to the **Idempotency guarantee**:
   - Clicking rebook again returns the identical confirmed PNR without duplicate airline reservations or billing.

---

### STEP 5: ML Observability & Chronological Audit Ledger
1. Click the **Audit Logs** tab:
   - Walk through the append-only chronological ledger: Disruption Detected → Multi-Source Reconciliation → Options Ranked → 2FA OTP Verified → VCC Issued → Rebooking Completed.
2. Click the **ML Observability** tab:
   - Showcase the dual-model ML pipeline: delay prediction, connection risk classifier, airport disruption heatmap, and feature importance SHAP values.
3. Click the **Operations Control** tab:
   - Show live operational KPIs, provider health status (Amadeus GDS, Stripe, Twilio), distributed rate limits, and circuit breaker metrics.

---

## 3. High-Value Answers to Anticipated Hiring Manager Questions

| Anticipated Question | Strong Answer |
| :--- | :--- |
| **"Why not just use an LLM Agent to rebook directly?"** | LLMs are non-deterministic. An LLM can hallucinate terminal walking times, miscalculate timezones, or book invalid flights. In Flyo, deterministic business rules calculate risk and money, while LLMs and ML are used where they excel: unstructured PNR parsing, delay forecasting, and customer communication. |
| **"How do you handle airline portal timeouts or errors?"** | Resilient exponential retry (up to 3 attempts). If the carrier 500s or crashes (which you can demo live with Scenario 3), Flyo transitions to `FAILED_ESCALATED`, sets `newPnr = null`, captures an automated error screenshot, and provides immediate 1-800 manual airline desk guidance. It never fakes success. |
| **"How does the system scale across multiple airlines?"** | Flyo uses a provider adapter pattern (`FlightBookingProvider`). It supports headless Playwright automation for legacy airline extranets, Amadeus Altéa GDS/NDC APIs for direct integrations, and Stripe Issuing for treasury virtual card settlements. |
| **"Is the architecture production-certified?"** | Yes. Stage 10 completed independent certification: 317 automated tests pass across backend (275), Python ML (36), frontend (3), and forensic audit scripts (3), with zero seed records on clean PostgreSQL startup and verified non-empty backup/restore. |
