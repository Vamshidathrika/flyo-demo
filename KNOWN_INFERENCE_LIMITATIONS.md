# Flyo Predictive Intelligence — Known Inference Limitations & Operational Boundaries

**Model Version**: `v2.0.0-multiseason`  
**Service Component**: `flyo-ml-inference`  
**Classification**: Experimental Supplementary Intelligence  

This document details the known operational boundaries, statistical constraints, and strict limitations of the current Flyo ML inference service. All operators and engineering personnel must adhere to these guidelines.

---

## 1. Geographic & Airspace Limitations

### 1.1 US Domestic Restriction
- **Training Corpus**: The current models are trained exclusively on Bureau of Transportation Statistics (BTS) data covering United States domestic commercial flights.
- **Airspace Incompatibility**: Flight dynamics, airport slot constraints, air traffic control protocols, monsoon weather patterns, and ground operations in India (or Europe/Asia) differ substantially from the US national airspace system.
- **Strict Guardrail**: Any flight originating, landing, or operated by an Indian entity (`DEL`, `BOM`, `BLR`, `HYD`, `6E`, `AI`, `UK`, etc.) is geofenced and returns:
  ```json
  "status": "UNSUPPORTED_AIRSPACE"
  ```
- **Operator Directive**: Never attempt to apply US BTS model weights to Indian routes. Rely exclusively on live flight radar telemetry, METAR observations, and human judgment.

---

## 2. Airline Carrier Coverage Boundaries

### 2.1 The 15 Reporting Carriers
The model features 15 one-hot encoded indicators and empirical training priors for BTS reporting carriers:
`WN`, `AA`, `DL`, `UA`, `OO`, `YX`, `MQ`, `NK`, `B6`, `AS`, `9E`, `OH`, `F9`, `G4`, `HA`.

### 2.2 Unrepresented Carriers
- Flights operated by airlines outside this list (e.g. international airlines `LH`, `BA`, `EK`, or US non-reporting charters) return:
  ```json
  "status": "INSUFFICIENT_COVERAGE"
  ```
- The service does not attempt to fabricate pseudo-probabilities for unrepresented carriers.

---

## 3. Disruption Target vs. Connection Feasibility

### 3.1 Extreme Delay Classification ($\ge 120\text{m}$)
- The binary target `is_delayed_120m` measures whether the flight's arrival delay was greater than or equal to 120 minutes.
- **Critical Distinction**: This is **strictly a single-flight delay classification target**, **NOT** a probability of a missed connection.
- **Why this matters**:
  - A 45-minute delay on a 50-minute connection will cause a missed flight.
  - A 130-minute delay on a 4-hour layover will **not** cause a missed flight.
- **System Authority**: Connection feasibility is governed exclusively by Flyo's deterministic Minimum Connection Time (MCT) rules engine (`MctRulesService` and `CaseStateMachineService`), which evaluates exact layover durations against terminal transfer thresholds (120 minutes).

---

## 4. Model Predictive Power & Uncertainty

### 4.1 Statistical Performance Summary
Evaluated on the held-out July 2024 multi-season test set:
- **Delay $\ge 120$m ROC-AUC**: `0.5950`
- **Delay $\ge 120$m PR-AUC**: `0.0693` (against a random baseline of `0.0523`)
- **Cancellation Brier Score**: `0.02931`

### 4.2 Practical Interpretation
- While the models demonstrate genuine statistical signal over baseline (+32.5% relative PR-AUC improvement), predicting extreme disruptions 2 hours prior to departure based solely on schedule geometry and historical priors is inherently noisy.
- Probabilities must be treated as indicative risk scores rather than certainty.

---

## 5. Weather Information Trade-off

### 5.1 Elimination of Observed Reanalysis Weather
- In Phase 2, the decision was made to completely remove historical observed weather reanalysis from training vectors to guarantee zero lookahead data leakage.
- **Current State**: The v2 model operates strictly on point-in-time features observable at $T - 2\text{h}$ (carrier disruption rate, airport traffic density, diurnal departure slots, route distance).
- **Future Roadmap**: Integrating real weather requires acquiring historical point-in-time **forecasts** (e.g. NOAA TAF / Terminal Aerodrome Forecasts issued before departure) rather than post-hoc observations.

---

## 6. Prohibition on Autonomous Action

1. **Mandatory Human Approval**: An ML prediction score can **never** independently transition a case to `REBOOKING`, approve flight alternatives, charge cards, or invoke Playwright browser workers.
2. **Deterministic Precedence**: Live radar alerts and airline status notifications take precedence over statistical model predictions.
3. **No Phantom Rebooking**: If ML indicates high risk but live radar reports the flight is on time and operating normally, no rebooking action is initiated.
