# STAGE 8 — SHAP MODEL EXPLAINABILITY & TRANSPARENCY SPECIFICATION

**Document ID:** `FLYO-STG8-XAI-007`  
**Classification:** Explainable Artificial Intelligence (XAI) & Algorithmic Governance  
**Framework:** TreeSHAP (Tree-based Shapley Additive Explanations)  
**Application:** Flyo Rescue System (`ml/service/explainability.py`)  
**Date:** November 2026  

---

## 1. Executive Summary

In high-stakes airline operations, "black-box" machine learning predictions are rarely trusted by dispatchers. If an AI system flags a flight as "80% Disruption Risk" without explaining why, operators cannot determine whether to hold connecting passengers, prepare an aircraft swap, or ignore the alert.

Stage 8 integrates **TreeSHAP** directly into the Flyo inference pipeline. For every flight prediction, the system computes the exact Shapley attribution for each feature, identifying:
1. The model's baseline expected value.
2. The top features increasing disruption probability ($\text{SHAP} > 0$).
3. The top features decreasing disruption probability ($\text{SHAP} < 0$).
4. The exact magnitude and direction of each factor in human-readable terms.

---

## 2. TreeSHAP Mathematical Foundation

For a flight instance $x$ and model prediction $f(x)$, TreeSHAP computes the additive feature contribution $\phi_i$ such that:
$$f(x) = \phi_0 + \sum_{i=1}^{M} \phi_i(x)$$
where:
- $\phi_0 = \mathbb{E}[f(X)]$ is the expected baseline probability across the training corpus.
- $\phi_i(x)$ is the Shapley value of feature $i$, representing the marginal contribution of feature $i$ across all possible feature subsets $S \subseteq F \setminus \{i\}$:
  $$\phi_i(x) = \sum_{S \subseteq F \setminus \{i\}} \frac{|S|!(|F| - |S| - 1)!}{|F|!} \left[ f_x(S \cup \{i\}) - f_x(S) \right]$$

---

## 3. Real-Time Explainability Response Example

When querying an evening flight from Delhi to Mumbai (`6E-501`) operating during winter fog conditions, the service returns:

```json
{
  "explainability_available": true,
  "base_value": 0.1802,
  "top_contributing_factors": [
    {
      "feature_name": "delhi_winter_fog_risk",
      "display_name": "Delhi Morning Winter Fog Window",
      "feature_value": 1.0,
      "shap_value": 0.2241,
      "impact_direction": "INCREASES_RISK",
      "absolute_magnitude": 0.2241
    },
    {
      "feature_name": "is_dep_evening_peak",
      "display_name": "Evening Airspace Peak (17:00-20:59)",
      "feature_value": 1.0,
      "shap_value": 0.0812,
      "impact_direction": "INCREASES_RISK",
      "absolute_magnitude": 0.0812
    },
    {
      "feature_name": "carrier_prior_cancel_rate",
      "display_name": "Carrier Historical Cancellation Prior",
      "feature_value": 0.015,
      "shap_value": -0.0615,
      "impact_direction": "DECREASES_RISK",
      "absolute_magnitude": 0.0615
    }
  ],
  "causal_disclaimer": "STATISTICAL ATTRIBUTION NOTICE: Factor contributions represent statistical feature weights derived via TreeSHAP within the XGBoost model. They indicate correlation patterns in training data, not independent physical causation."
}
```

---

## 4. Algorithmic Governance: Statistical Correlation vs Physical Causality

A critical governance requirement for Flyo is that **statistical feature importance must never be conflated with physical or legal causality**.
- If a flight is delayed and TreeSHAP attributes $+0.15$ to `precip_mm`, that indicates that rainfall historically correlates with delays on that corridor.
- It does **not** prove that rainfall legally caused the delay (which might actually be crew scheduling or air traffic control hold).
- Every explainability panel in the UI displays an explicit disclaimer ensuring dispatchers make decisions based on total situational awareness.
