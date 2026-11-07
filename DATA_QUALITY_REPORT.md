# DATA QUALITY AUDIT REPORT
## Flyo Predictive Intelligence v1 — BTS Historical Ingestion Verification

**Dataset Ingested:** BTS On-Time Reporting Carrier Performance (`2024_01`)  
**Pipeline:** `ml/src/bts_parser.py`  
**Execution Timestamp:** November 5, 2026  

---

## 1. High-Level Quality Metrics

| Audit Dimension | Measured Value | Benchmark Expectation | Compliance Status |
| :--- | :---: | :---: | :---: |
| **Total Rows Evaluated** | 50,001 | 50,000+ Sample | **PASS** |
| **Valid Schema Rows Retained** | 50,000 | > 99.0% | **PASS (99.99%)** |
| **Missing Mandatory Keys** | 0 | 0 | **PASS (100% Complete)** |
| **Duplicate Flight Signatures Filtered** | 0 | < 0.1% | **PASS** |
| **Completed Flights Analyzed** | 48,638 | ~97% | **PASS (97.28%)** |
| **Cancelled Flights Recorded** | 1,229 | ~1.5 - 3.5% | **PASS (2.458%)** |
| **Diverted Flights Isolated** | 133 | < 0.5% | **PASS (0.266%)** |

---

## 2. Missing Value Analysis

| Field | Missing Count | Missing % | Reason & Handling |
| :--- | :---: | :---: | :--- |
| `flight_date`, `carrier_code`, `origin_airport`, `dest_airport` | 0 | 0.0% | Mandatory keys. Any row missing these is discarded. |
| `scheduled_dep_time`, `scheduled_arr_time` | 0 | 0.0% | Mandatory schedule keys. Cleaned and formatted to `HH:MM`. |
| `tail_number` | 24 | 0.048% | Unassigned aircraft on cancelled/ferry flights. Imputed as `"UNKNOWN"`. |
| `actual_dep_time`, `actual_arr_time` | 1,229 | 2.458% | **Expected structural missingness:** Exactly matches cancelled flights ($1,229$). Cancelled flights do not depart. |
| `arr_delay_minutes` | 1,362 | 2.724% | **Expected structural missingness:** Corresponds to $1,229$ cancelled flights $+ 133$ diverted flights. Handled cleanly: continuous delay model is trained strictly on completed flights ($48,638$). |
| `cancellation_code` | 48,771 | 97.542% | **Expected:** Only populated when `is_cancelled == 1`. Populated in 100% of cancelled flights. |

---

## 3. Consistency & Outlier Validation

### 3.1 Logical Integrity Checks
- **Cancelled Flights Consistency:**
  - $100\%$ of cancelled flights (`is_cancelled == 1`) had `actual_dep_time == None` and `actual_arr_time == None`. Zero phantom departure times on cancelled records.
  - $100\%$ of cancelled flights had a valid DOT cancellation code (`A`, `B`, or `C`).
- **Completed Flights Consistency:**
  - All completed flights have valid positive scheduled elapsed time (`scheduled_elapsed_minutes` $> 0$).
  - Mean scheduled duration: $152.4\text{ minutes}$ ($2\text{h } 32\text{m}$).
  - Flight distance ranges between $67\text{ miles}$ (short hop) and $4,983\text{ miles}$ (continental/transpacific).

### 3.2 Arrival Delay Distribution (`arr_delay_minutes`)
- **Sample Size:** 48,638 completed flights.
- **Mean Arrival Delay:** $+15.63\text{ minutes}$.
- **Median Arrival Delay:** $-5.0\text{ minutes}$ (indicates scheduled airline block time padding; over $50\%$ of flights arrive at gate slightly early under nominal conditions).
- **Minimum Delay (Early Arrival):** $-76.0\text{ minutes}$ (strong tailwinds / aggressive padding).
- **Maximum Delay:** $+2,989.0\text{ minutes}$ ($\sim 49.8\text{ hours}$ — aircraft delayed overnight during major January blizzard).
- **Standard Deviation:** $\pm 48.7\text{ minutes}$.
- **Extreme Delays ($\ge 120\text{m}$):** 2,415 flights ($4.97\%$).
- **On-Time Rate ($< 15\text{m}$ delay):** $72.96\%$.

---

## 4. Class Imbalance Assessment for XGBoost

1. **Cancellation Classification Target (`is_cancelled`):**
   - Class 0 (Completed/Diverted): 48,771 ($97.54\%$)
   - Class 1 (Cancelled): 1,229 ($2.458\%$)
   - **Imbalance Ratio:** $39.7 : 1$.
   - **Modeling Recommendation:** Set `scale_pos_weight = 39.7` in `XGBClassifier`, evaluate using **Precision-Recall AUC (PR-AUC)** and F1 score, and calibrate output probabilities with **Isotonic Regression**.
2. **Extreme Delay Target (`is_delayed_120m`):**
   - Class 0 ($< 120\text{m}$): 46,223 ($95.03\%$)
   - Class 1 ($\ge 120\text{m}$): 2,415 ($4.97\%$)
   - **Imbalance Ratio:** $19.1 : 1$.
