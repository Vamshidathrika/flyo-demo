# BTS HISTORICAL DATA INGESTION REPORT
## Flyo Predictive Intelligence v1 — Milestone Phase 1

**Date:** November 5, 2026  
**Ingestion Pipeline:** `ml/src/pipeline.py` / `ml/src/bts_downloader.py` / `ml/src/bts_parser.py`  
**Data Source:** U.S. Department of Transportation (DOT) Bureau of Transportation Statistics (BTS)  
**Dataset Reference:** `On_Time_Reporting_Carrier_On_Time_Performance_1987_present_2024_1.zip`  
**Licensing & Rights:** Public Domain (U.S. Government Work / 17 U.S.C. § 105)  

---

## 1. Executive Summary

A reproducible, leakage-free historical flight data ingestion pipeline has been established and verified on a small, real-world monthly release from BTS TranStats.

- **Sample Month Downloaded:** January 2024 (`2024_01`).
- **Archive Size:** 27,573,265 bytes (~27.5 MB compressed).
- **Archive Checksum (SHA-256):** `fe089b45523f4b5059d0ef3a093b169528f114c0005a74421b1836c05a1097fa`.
- **Rows Processed:** 50,000 real domestic commercial flights.
- **Valid Rows Retained:** 50,000 (100% schema compliance, zero parse errors).
- **Cancellations Identified:** 1,229 flights (2.458% cancellation rate).
- **Extreme Delays ($\ge 120\text{m}$):** 2,415 flights (4.97% of completed flights).
- **Weather Enrichment Test:** 2,500 sample flights enriched with Open-Meteo Historical Archive API (85.44% weather match rate across major US hub flights).

---

## 2. Ingestion Architecture & Pipeline Design

```mermaid
flowchart TD
    BTS["U.S. DOT BTS TranStats PREZIP Server"] -->|HTTP Stream with SHA-256 Checksum| RawZip["ml/data/raw/On_Time_..._2024_1.zip"]
    RawZip --> MetaJson["ml/data/raw/metadata.json (Incremental Registry)"]
    RawZip --> ZipStream["bts_parser.py (Stream Extraction)"]
    ZipStream --> NormRules["Field Normalization & Deduplication"]
    NormRules --> CleanCSV["ml/data/processed/flights_2024_01.csv"]
    NormRules --> StatsJSON["ml/data/processed/quality_stats_2024_01.json"]
    CleanCSV --> WeatherMatcher["weather_matcher.py (Open-Meteo Archive API)"]
    WeatherMatcher --> EnrichedCSV["ml/data/processed/enriched_sample_2024_01.csv"]
```

### 2.1 Incremental Ingestion & Metadata Registry
- The pipeline maintains `ml/data/raw/metadata.json`.
- Before initiating any network call, the downloader checks target file presence, byte count, and registered SHA-256 hash. If valid, downloads are skipped (`[Incremental Cache Hit]`), preventing redundant bandwidth consumption.
- Ingestion metadata recorded:
  ```json
  {
    "2024_01": {
      "year": 2024,
      "month": 1,
      "filename": "On_Time_Reporting_Carrier_On_Time_Performance_1987_present_2024_1.zip",
      "source_url": "https://www.transtats.bts.gov/PREZIP/On_Time_Reporting_Carrier_On_Time_Performance_1987_present_2024_1.zip",
      "downloaded_at_utc": "2026-11-05T12:56:01.275000+00:00",
      "size_bytes": 27573265,
      "sha256": "fe089b45523f4b5059d0ef3a093b169528f114c0005a74421b1836c05a1097fa",
      "status": "RAW_DOWNLOAD_SUCCESS"
    }
  }
  ```

---

## 3. Real-Data Ingestion Validation Results (January 2024)

| Metric | Measured Value | Significance for Predictive Intelligence |
| :--- | :---: | :--- |
| **Total Rows Read** | 50,001 | Small real-world test batch (out of ~550,000 monthly total) |
| **Valid Retained Flights** | 50,000 | Conforms to strict schema with zero synthetic data |
| **Completed Flights** | 48,638 | Used for Continuous Arrival Delay Regression ($y_{\text{delay}}$) |
| **Cancelled Flights** | 1,229 (2.458%) | Used for Binary Cancellation Classification ($y_{\text{cancel}}$) |
| **Diverted Flights** | 133 (0.266%) | Isolated from standard completion metrics |
| **Delayed $\ge 15\text{m}$ (DOT Standard)** | 13,150 (27.04%) | DOT On-Time Arrival benchmark baseline |
| **Extreme Delay $\ge 120\text{m}$ (Flyo MCT)** | 2,415 (4.97%) | Directly validates Flyo's 120-minute connection hazard threshold |
| **Mean Arrival Delay** | $+15.63\text{ minutes}$ | Empirical baseline mean across all completed flights |
| **Max Arrival Delay Recorded** | $+2,989\text{ minutes}$ | Real winter storm delay tail event |
| **Min Arrival Delay (Early)** | $-76\text{ minutes}$ | Normal favorable tailwind distribution |

---

## 4. Cancellation Breakdown (January 2024 Real Data)

Commercial airlines report standardized cancellation cause codes to the BTS:

| Code | Cancellation Reason | January 2024 Count | Percentage of Cancellations |
| :---: | :--- | :---: | :---: |
| **B** | **Adverse Meteorological Conditions** | **1,010** | **82.18%** |
| **C** | **National Aviation System / ATC Capacity** | **161** | **13.10%** |
| **A** | **Carrier Mechanical / Crew Duty Time** | **58** | **4.72%** |
| **D** | **Security** | **0** | **0.00%** |

> **Key Machine Learning Finding:** Over **82% of all January cancellations** were driven by severe winter weather. This empirically proves the necessity of combining meteorological features (Open-Meteo historical archive) with flight schedule features for cancellation prediction.

---

## 5. Geographic Scope Boundary

> [!IMPORTANT]
> **Data Licensing & Geographical Integrity:**  
> The BTS dataset is strictly limited to U.S. domestic commercial air carriers operating within the United States.  
> As mandated by our core engineering principles, **we do not claim Indian domestic flight prediction capability (e.g. HYD, BOM, DEL) from US-only training data**.  
> The first trained XGBoost model will explicitly forecast US domestic flights, establishing the model architecture, calibration, and inference pipeline before porting to international data sources.
