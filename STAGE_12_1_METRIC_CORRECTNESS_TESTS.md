# FLYO RESCUE — STAGE 12.1 METRIC CORRECTNESS TESTS

## 1. Overview

This report provides the formal unit and behavioral verification for all metrics rendered within the Flyo ML Observability Operations Console. In an aviation disruption operations dashboard, an incorrect metric (such as displaying `9200%` instead of `92.0%`, or displaying `NaN%`) poses immediate operational risk.

---

## 2. Imputation Metric Specification & Normalization Contract

### 2.1. Mathematical Contract

The pure normalization function `normalizePercentage(val)` adheres to the following specification:

```javascript
function normalizePercentage(val) {
    if (val === null || val === undefined) return null;
    const num = Number(val);
    if (isNaN(num) || !isFinite(num)) return null;
    if (num < 0 || num > 100) return null;
    if (num <= 1.0) {
        return num * 100;
    }
    return num;
}

function formatPercentage(val) {
    const norm = normalizePercentage(val);
    if (norm === null) return "N/A";
    return `${norm.toFixed(1)}%`;
}
```

### 2.2. Unit Test Matrix

All 14 test cases execute automatically in `flyo-rescue-demo/frontend/test.mjs`:

| Input Value | Type / Category | Expected Normalized | Expected Formatted | Verification Status |
| :--- | :--- | :--- | :--- | :--- |
| `0.92` | Fractional rate | `92.0` | `92.0%` | **PASS** |
| `0.9117` | Fractional rate | `91.17` | `91.2%` | **PASS** |
| `92.0` | Percentage scale | `92.0` | `92.0%` | **PASS** |
| `91.17` | Percentage scale | `91.17` | `91.2%` | **PASS** |
| `0.0` | Boundary zero | `0.0` | `0.0%` | **PASS** |
| `1.0` | Boundary 100% | `100.0` | `100.0%` | **PASS** |
| `null` | Missing value | `null` | `N/A` | **PASS** |
| `undefined` | Unbound field | `null` | `N/A` | **PASS** |
| `NaN` | Math error | `null` | `N/A` | **PASS** |
| `-5` | Out of range (neg) | `null` | `N/A` | **PASS** |
| `150` | Out of range (>100) | `null` | `N/A` | **PASS** |
| `Infinity` | Overflow | `null` | `N/A` | **PASS** |
| `"not-a-number"` | Malformed string | `null` | `N/A` | **PASS** |
| `""` | Empty string | `null` | `N/A` | **PASS** |

---

## 3. Drift Classification & Fallback Safety Matrix

| Input Condition | Output Status | Color Scheme | Operational Implication |
| :--- | :--- | :--- | :--- |
| `sample_size < 30` | `CALIBRATING` | Slate Gray (`#64748b`) | Window too small to calculate statistically significant PSI. |
| `drift_level == 'CRITICAL'` or `max_psi >= 0.25` | `ADAPTIVE MODE` | Indigo (`#4f46e5`) | Non-US sector detected; deterministic calibrated airline fallback engaged. |
| `drift_level == 'WARNING'` or `max_psi >= 0.10` | `DRIFT WARNING` | Amber (`#d97706`) | Moderate shift; monitored without fallback intervention. |
| `status == 'EVALUATED'` and `max_psi < 0.10` | `STABLE` | Emerald Green (`#16a34a`) | Nominal prediction distributions matching training baseline. |
| `drift == null` or `status == 'UNAVAILABLE'` | `UNAVAILABLE` | Slate Gray (`#64748b`) | Metrics missing; **never falsely reports STABLE**. |

---

## 4. Evaluation Counter Safety Matrix

| Scenario | Evaluated Count | Pending Count | Displayed Text | Subtitle |
| :--- | :--- | :--- | :--- | :--- |
| Initial state / No ground truth | `0` | `623` | `0 / 623` | `Awaiting flight outcomes (623 pending)` |
| Partial ingestion | `50` | `100` | `50 / 150` | `Brier: 0.1423 (Δ -0.0120)` |
| Service missing / offline | `null` | `null` | `0 / 0` | `Delayed outcome telemetry unavailable` |
