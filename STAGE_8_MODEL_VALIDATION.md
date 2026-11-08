# STAGE 8 — MODEL VALIDATION, BACKTESTING & DRIFT MONITORING

**Document ID:** `FLYO-STG8-VAL-008`  
**Classification:** Quality Assurance, Chronological Backtesting & Observability  
**Application:** Flyo Rescue System (`ml/service/drift_detector.py`, `ml/src/train_v3.py`)  
**Date:** November 2026  

---

## 1. Executive Summary

A critical standard of Flyo's engineering philosophy is that machine learning models must be validated using realistic out-of-time evaluation protocols rather than random train-test splits. Random K-fold cross-validation on aviation time-series data leaks future weather and seasonal patterns into the past, resulting in overly optimistic accuracy metrics that fail in production.

In Stage 8, all models were evaluated via **Chronological Out-of-Time Backtesting**:
- **Training Period:** January 1, 2024 to June 15, 2024 (73,970 flights).
- **Validation Period:** June 15, 2024 to July 15, 2024 (15,851 flights) used strictly for Platt probability calibration and hyperparameter tuning.
- **Held-Out Test Period:** July 15, 2024 to July 31, 2024 (15,851 flights) representing unseen summer operations and active monsoon conditions.

---

## 2. Segmented Evaluation by Airline & Corridor

### A. Carrier-Level Performance (Held-Out Test Split)
| Carrier | Operational Profile | Test Flights | Disruption Rate | Calibrated Brier Score | ROC-AUC |
|---|---|---|---|---|---|
| **6E (IndiGo)** | Indian Domestic Low-Cost | 1,425 | 17.5% | 0.1342 | 0.7410 |
| **AI (Air India)** | Indian Full-Service Hub | 475 | 21.2% | 0.1495 | 0.7180 |
| **UK (Vistara)** | Indian Premium Full-Service | 238 | 16.8% | 0.1280 | 0.7350 |
| **WN (Southwest)** | US Point-to-Point | 3,180 | 28.5% | 0.2015 | 0.5420 |
| **AA (American)** | US Hub-and-Spoke | 2,150 | 32.1% | 0.2240 | 0.5310 |
| **DL (Delta)** | US Hub-and-Spoke | 2,280 | 26.4% | 0.1980 | 0.5560 |
| **UA (United)** | US Hub-and-Spoke | 1,620 | 30.5% | 0.2150 | 0.5390 |

---

## 3. Probability Calibration Assessment

Calibration measures whether a predicted probability of 30% actually results in disruptions 30% of the time:
- **Uncalibrated XGBoost Brier Score:** `0.2516`
- **Platt Calibrated XGBoost Brier Score:** `0.2208` (12.2% calibration improvement)
- **Indian Corridor Brier Score:** `0.1379` (highly calibrated probabilistic risk)

---

## 4. Production Drift Detection Protocol

The inference engine continuously monitors live operational traffic against the baseline training distribution using two statistical metrics:

1. **Kolmogorov-Smirnov (KS) Two-Sample Test:**
   Evaluates continuous numerical features (delay minutes, temperature, wind speed, distance).
   $$D = \sup_x |F_{\text{live}}(x) - F_{\text{baseline}}(x)|$$
   A KS-statistic $D > 0.15$ with $p < 0.01$ raises a feature drift warning.
2. **Population Stability Index (PSI):**
   Evaluates categorical and binned distributions (carrier shares, origin hubs, disruption risk tiers).
   $$\text{PSI} = \sum_{b=1}^{B} (P_b - Q_b) \ln\left(\frac{P_b}{Q_b}\right)$$
   - $\text{PSI} < 0.10$: Stable distribution (No action).
   - $0.10 \le \text{PSI} < 0.25$: Moderate drift (Review feature distributions).
   - $\text{PSI} \ge 0.25$: Severe drift (Trigger model retraining alert).
