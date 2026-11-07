# Model Feature & Prediction Drift Monitoring Report

**Flyo Disruption Rescue Platform &middot; Phase 4 Engineering Verification**  
**Module:** `ml/service/drift_detector.py`  
**Baseline Dataset:** BTS July 2024 Held-Out Verification Split (569,090 flights)  
**Baseline Artifact:** `ml/artifacts/baseline_reference_distributions.json`  

---

## 1. Drift Detection Methodology

Model performance naturally deteriorates over time due to seasonal weather changes, airline schedule updates, air traffic control shifts, and fleet disruptions. Flyo implements automated two-tier statistical drift detection:

1. **Population Stability Index (PSI)**:
   Used for probability distributions (`cancellation_probability` and `delay_120m_probability`) bucketed into 10 decile bins ($[0.0, 0.1), [0.1, 0.2), \dots, [0.9, 1.0]$):
   $$\text{PSI} = \sum_{i=1}^{k} (A_i - E_i) \times \ln\left(\frac{A_i}{E_i}\right)$$
   where $A_i$ is actual production fraction and $E_i$ is baseline reference fraction (with $\epsilon = 10^{-5}$ smoothing).

   - **PSI Thresholds**:
     - $\text{PSI} < 0.10$: **STABLE** (No significant distribution shift)
     - $0.10 \le \text{PSI} < 0.25$: **MODERATE SHIFT** (`WARNING` alert)
     - $\text{PSI} \ge 0.25$: **SIGNIFICANT DRIFT** (`CRITICAL` alert & operator notification)

2. **Kolmogorov-Smirnov (KS) Two-Sample Test**:
   Applied to continuous predicted delay minutes (`scipy.stats.ks_2samp`) comparing live inferred delay samples against reference percentile distributions.
   - Significant drift flagged when $p\text{-value} < 0.01$ and $D\text{-statistic} > 0.15$.

3. **Sample Size Gating**:
   A minimum sample threshold of $N \ge 30$ evaluated records is strictly enforced. Sample windows with $N < 30$ return `INSUFFICIENT_DATA` rather than generating noisy false alerts.

---

## 2. Baseline Reference Distributions

From `ml/artifacts/baseline_reference_distributions.json`:
- **Cancellation Probability Baseline Deciles**:
  - `[0.0 - 0.1]`: 97.4%
  - `[0.1 - 0.2]`: 2.1%
  - `[0.2 - 0.3]`: 0.3%
  - `[0.3 - 1.0]`: 0.2%
- **Delay $\ge 120$m Probability Baseline Deciles**:
  - `[0.0 - 0.1]`: 93.8%
  - `[0.1 - 0.2]`: 4.6%
  - `[0.2 - 0.3]`: 1.1%
  - `[0.3 - 1.0]`: 0.5%
- **Continuous Predicted Delay Percentiles (minutes)**:
  - $p10 = 2.1$, $p25 = 4.8$, $p50 = 8.5$, $p75 = 14.2$, $p90 = 24.6$, $p95 = 35.1$, $p99 = 72.4$

---

## 3. Drift Evaluation API

Endpoint: `GET /monitoring/drift?limit=500` (Protected by `X-Flyo-Monitoring-Key`)

Sample Response:
```json
{
  "sample_size": 150,
  "cancellation_prob_psi": 0.0342,
  "delay_120m_prob_psi": 0.0481,
  "continuous_delay_ks_statistic": 0.0621,
  "continuous_delay_ks_p_value": 0.482,
  "drift_detected": false,
  "alert_severity": "NORMAL",
  "verdict": "Prediction output distributions align with July 2024 held-out baseline within normal operating tolerances."
}
```

---

## 4. Operator Action Protocol on Drift

When `SIGNIFICANT_DRIFT` is flagged:
1. Operator dashboard triggers red banner.
2. Production traffic is **never interrupted** autonomously.
3. Model remains operational in supplementary mode.
4. MLOps team initiates review of recent weather anomalies or carrier schedule shifts.
