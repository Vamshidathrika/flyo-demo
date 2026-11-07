# Flyo ML Phase 2.5 — Carrier and Airport Coverage Report

> **Dataset Scope:** 1,701,105 Valid Flight Records (January, February & July 2024)  
> **Source:** US DOT Bureau of Transportation Statistics (BTS)  
> **Status:** Full Geographic & Airline Coverage Audited

---

## 1. Complete Airline Distribution (All 15 BTS Reporting Carriers)

Unlike the initial 50,000-row sample that represented only 2 carriers, the expanded dataset includes **100% of all 15 reporting domestic US airlines**:

| Rank | Carrier Name | IATA Code | Flight Count | Share of Total | Jan 2024 | Feb 2024 | Jul 2024 |
| :---: | :--- | :---: | :--- | :--- | :--- | :--- | :--- |
| **1** | Southwest Airlines | `WN` | **348,238** | 20.47% | 115,389 | 105,207 | 127,642 |
| **2** | American Airlines | `AA` | **240,507** | 14.14% | 77,346 | 74,870 | 88,291 |
| **3** | Delta Air Lines | `DL` | **237,898** | 13.98% | 74,384 | 71,218 | 92,296 |
| **4** | United Airlines | `UA` | **181,152** | 10.65% | 58,855 | 56,834 | 65,463 |
| **5** | SkyWest Airlines | `OO` | **174,053** | 10.23% | 56,814 | 53,325 | 63,914 |
| **6** | Republic Airways | `YX` | **70,129** | 4.12% | 22,914 | 21,602 | 25,613 |
| **7** | Envoy Air | `MQ` | **66,978** | 3.94% | 20,750 | 20,117 | 26,111 |
| **8** | Spirit Airlines | `NK` | **65,113** | 3.83% | 20,415 | 19,929 | 24,769 |
| **9** | JetBlue Airways | `B6` | **58,997** | 3.47% | 19,580 | 19,235 | 20,182 |
| **10** | Alaska Airlines | `AS` | **58,522** | 3.44% | 17,775 | 16,863 | 23,884 |
| **11** | PSA Airlines | `OH` | **52,259** | 3.07% | 16,526 | 15,655 | 20,078 |
| **12** | Endeavor Air | `9E` | **49,885** | 2.93% | 16,972 | 15,656 | 17,257 |
| **13** | Frontier Airlines | `F9` | **47,968** | 2.82% | 14,379 | 14,157 | 19,432 |
| **14** | Allegiant Air | `G4` | **29,943** | 1.76% | 8,596 | 8,486 | 12,861 |
| **15** | Hawaiian Airlines | `HA` | **19,463** | 1.14% | 6,576 | 6,067 | 6,820 |
| **Total** | **All Reporting US Airlines** | | **1,701,105** | **100.0%** | **547,271** | **519,221** | **634,613** |

---

## 2. Airport & Hub Distribution

The dataset encompasses **364 unique US airports** across all 50 states, Puerto Rico, and the US Virgin Islands.

### Top 25 US Departure Hubs:

| Rank | Airport Name | IATA Code | Flight Count | Primary Hub Carrier |
| :---: | :--- | :---: | :--- | :--- |
| **1** | Hartsfield-Jackson Atlanta | `ATL` | **81,208** | Delta Air Lines (`DL`) |
| **2** | Dallas/Fort Worth | `DFW` | **75,333** | American Airlines (`AA`) |
| **3** | Denver International | `DEN` | **73,521** | United (`UA`) / Southwest (`WN`) |
| **4** | Chicago O'Hare | `ORD` | **64,405** | United (`UA`) / American (`AA`) |
| **5** | Charlotte Douglas | `CLT` | **51,189** | American Airlines (`AA`) |
| **6** | Los Angeles International | `LAX` | **46,900** | Delta / American / United |
| **7** | Phoenix Sky Harbor | `PHX` | **45,934** | American / Southwest |
| **8** | Harry Reid Las Vegas | `LAS` | **45,297** | Southwest (`WN`) |
| **9** | Orlando International | `MCO` | **41,210** | Southwest / Delta / Spirit |
| **10** | New York LaGuardia | `LGA` | **39,687** | Delta / American |
| **11** | Seattle-Tacoma | `SEA` | **39,051** | Alaska Airlines (`AS`) |
| **12** | Ronald Reagan Washington | `DCA` | **34,732** | American Airlines (`AA`) |
| **13** | Boston Logan | `BOS` | **33,205** | Delta / JetBlue |
| **14** | San Francisco International | `SFO` | **31,593** | United Airlines (`UA`) |
| **15** | Newark Liberty | `EWR` | **31,171** | United Airlines (`UA`) |
| **16** | Detroit Metropolitan | `DTW` | **30,948** | Delta Air Lines (`DL`) |
| **17** | Minneapolis-St. Paul | `MSP` | **30,211** | Delta Air Lines (`DL`) |
| **18** | Miami International | `MIA` | **29,540** | American Airlines (`AA`) |
| **19** | Houston Bush | `IAH` | **29,180** | United Airlines (`UA`) |
| **20** | New York JFK | `JFK` | **27,842** | Delta / JetBlue / American |
| **21** | Fort Lauderdale | `FLL` | **27,112** | Spirit / JetBlue / Southwest |
| **22** | Salt Lake City | `SLC` | **26,984** | Delta Air Lines (`DL`) |
| **23** | Chicago Midway | `MDW` | **23,892** | Southwest Airlines (`WN`) |
| **24** | Baltimore/Washington | `BWI` | **23,115** | Southwest Airlines (`WN`) |
| **25** | Tampa International | `TPA` | **21,809** | Southwest / Delta |

---

## 3. Geographic Boundaries: Non-US / Indian Route Audit

A programmatic query across all 1,701,105 rows evaluated presence of major non-US airports:

```python
# Audited Indian Domestic Airport Codes
indian_codes = ["DEL", "BOM", "BLR", "HYD", "MAA", "CCU", "AMD", "GOI"]
# Result: Exactly 0 rows found (0.00%)
```

- **Origin Representation:** 0 records for any Indian airport.
- **Destination Representation:** 0 records for any Indian airport.
- **Carrier Representation:** 0 records for Indian domestic airlines (`6E`, `AI`, `SG`, `UK`, `I5`).
- **Audit Conclusion:** The dataset is strictly US domestic air transportation.

---

## 4. Route Coverage Gating Policy

Based on empirical frequency in the 1.7M flight dataset:

| Route Classification | Criteria | Platform Action |
| :--- | :--- | :--- |
| **Fully Supported Core Route** | Operates between top 25 US hubs by any of the 15 BTS airlines with $\ge 500$ historical flights. | `PREDICTION_AVAILABLE`: Full calibrated probability scores provided. |
| **Secondary Regional Route** | US domestic flight between non-hub regional outstations with $< 50$ historical flights. | `INSUFFICIENT_COVERAGE`: Model abstains; reports station empirical priors only. |
| **Indian Domestic Route** | Origin or destination in India (`DEL`, `BOM`, `HYD`, etc.). | `UNSUPPORTED_AIRSPACE`: Explicitly rejected; reports zero model coverage. |
| **International Airspace** | Cross-border or non-US domestic routing. | `UNSUPPORTED_AIRSPACE`: Explicitly rejected. |
