# STAGE 8 — MODEL LIMITATIONS, ASSUMPTIONS & OPERATIONAL SAFEGUARDS

**Document ID:** `FLYO-STG8-LIM-009`  
**Classification:** Risk Governance & Operational Boundary Specification  
**Target:** Flyo Machine Learning Prediction & Forecasting System  
**Date:** November 2026  

---

## 1. Executive Summary

A core requirement of enterprise AI safety is being completely honest about what an ML model can and cannot do. False confidence in statistical systems causes catastrophic operational decisions. This document details the empirical limitations, data boundaries, and mandatory safeguards governing Flyo's predictive intelligence.

---

## 2. Key Model Limitations

### A. Geographical & Data Provenance Boundaries
1. **US BTS Data vs Indian Aviation:**
   - The US BTS dataset represents 1.63M real observed historical flights.
   - The Indian corridor dataset (30,672 flights across 6 bilateral trunk corridors) is derived from DGCA published on-time statistics, airline schedules, and airport weather models. It provides realistic operational distributions but is not raw radar transponder data.
2. **Unsupported Airspaces:**
   - Flights outside the 6 priority Indian corridors (`HYD-DEL`, `HYD-BOM`, `HYD-BLR`, `DEL-BOM`, `BLR-MAA`, `BOM-BLR`) and the 12 top US hubs (`ATL`, `ORD`, `DFW`, `DEN`, `LAX`, `SFO`, etc.) fall back to regional climatological and carrier priors with reduced confidence scores.

### B. Unobserved Operational Variables
The model cannot observe real-time variables that occur within 60 minutes of pushback:
- Unscheduled mechanical or avionics faults discovered during pre-flight walkarounds.
- Late-arriving flight crew or cabin crew exceeding legal duty-time limits.
- Ground handling strikes or sudden baggage belt conveyor failures.
- VIP aircraft movements and temporary military airspace closures (NOTAMs).

### C. Extreme Tail Delay Uncertainty
While the median absolute error is ~16–20 minutes for typical delays, extreme tail delays (flights delayed >4 hours due to multi-hub cascading gridlock) have high variance. The model provides prediction intervals ($P_{10}$ to $P_{90}$), but extreme black-swan delays remain inherently non-deterministic.

---

## 3. Mandatory Operational Safety Boundaries

> [!CRITICAL]
> **Safety Rule 1: ML Predictions Must Never Autonomously Mutate Authoritative State**
> Machine learning predictions provide situational intelligence and prioritization alerts. An ML prediction must NEVER autonomously cancel a booking, reissue an e-ticket, or debit funds without authoritative carrier confirmation or human supervisor authorization.

> [!IMPORTANT]
> **Safety Rule 2: Deterministic Engine Remains Authoritative in Degradation**
> If the ML microservice experiences network timeouts, circuit breaker trips, or service degradation, Flyo automatically falls back to deterministic rule-based evaluation (`DeterministicRiskEngine`). Flight operations never halt due to an ML service outage.

> [!NOTE]
> **Safety Rule 3: Explainability Over Blind Acceptance**
> Every high-risk prediction must expose top SHAP feature attributions so human dispatchers can review the physical and seasonal drivers before taking operational action.
