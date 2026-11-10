# STAGE 13B — OPERATIONS CONTROL CENTER DATA PROVENANCE MATRIX

**Document Version:** 1.0.0  
**Audit Date:** 2026-11-10  
**Target Milestone:** Stage 13B — End-to-End Operational Certification, Data Provenance & Recovery Integrity  
**Component Audited:** Flyo Operations Control Center (OCC) Dashboard & ML Telemetry Engine  
**Contract Baseline:** Stage 12.1 Normalization & Stage 13 OCC Specification  

---

## 1. Executive Overview & Data Integrity Contract

The Stage 13 Operations Control Center (OCC) serves as the mission-critical interface for airline dispatchers, flight operations officers, and rescue coordinators. To prevent operational misjudgments, every piece of telemetry rendered on the dashboard must have a mathematically rigorous, verifiable data provenance chain tracing back to its originating database table, physical sensor, ML inference record, or external partner API.

### The Canonical Percentage Contract (Stage 12.1 / 13B)
Prior stages suffered from unit ambiguity where fractional rates ($0.0 \dots 1.0$) were conflated with whole percentage metrics ($0.0\% \dots 100.0\%$), leading to anomalous UI displays such as `9200%` or `NaN%`. Stage 13B codifies the **Flyo Normalization Contract**:

$$\text{Displayed Percentage}(v) = \begin{cases}
\text{"N/A"} & \text{if } v \text{ is null, undefined, or NaN} \\
\min(100.0, v \times 100) \% & \text{if } 0.0 \le v \le 1.05 \text{ (Unit Fraction Domain)} \\
\min(100.0, v) \% & \text{if } 1.05 < v \le 100.0 \text{ (Whole Percentage Domain)} \\
\text{"N/A"} & \text{if } v < 0.0 \text{ or } v > 100.0
\end{cases}$$

> [!IMPORTANT]
> **Resolution of Ambiguous `1.0`**: Under the Stage 13B contract, an exact raw numeric value of `1.0` emitted by an API metric field representing a "rate", "ratio", or "index" (such as `imputation_rate` or `accuracy`) is mathematically classified as a **unit fraction** and displays as **`100.0%`**. When an API field represents an explicit count or integer score, it is not passed through `normalizePercentage()`.

---

## 2. Comprehensive Data Provenance & Pipeline Mapping

| UI Element / Widget | Display Field | Authoritative Upstream Source | Source Data Type | Backend / ML Transformation Pipeline | Frontend Rendering & Formatting Logic | Error / Stale Fallback |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Trip Monitor: Active Flights** | Total Monitored Flights | PostgreSQL `flights` table | `INTEGER` (COUNT) | Spring Boot `FlightController.getLiveFlights()` queries repository with active status filter | `data.flights.length` formatted as locale string | Displays `0` if empty array; error toast on HTTP 500 |
| **Trip Monitor: High Risk Count** | High Risk Flights Badge | ML Disruption Inference / Spring Boot `FlightRiskService` | `DOUBLE` ($p \in [0.0, 1.0]$) | Filter flights where $p_{\text{disruption}} \ge 0.65$ or predicted delay $\ge 45\text{ min}$ | Filtered count rendered in red badge (`badge-danger`) | Displays `0` if no high-risk flights detected |
| **OCC: Data Quality Card** | Missing Duration % | SQLite `monitoring.db` $\rightarrow$ `data_quality_metrics` | `REAL` / `FLOAT` (`missing_duration_percentage`) | FastAPI `/monitoring/data-quality` reads latest table row; emits JSON | `normalizePercentage(data.missing_duration_percentage \|\| data.imputation_rate)` $\rightarrow$ `"92.0%"` | Displays `"N/A"` if field null or missing |
| **OCC: Data Quality Card** | Completeness Score | SQLite `monitoring.db` $\rightarrow$ `data_quality_metrics` | `REAL` (`completeness_score`) | Evaluated as $1.0 - \text{imputation\_rate}$; emitted as fraction | `normalizePercentage(data.completeness_score)` $\rightarrow$ `"95.4%"` | Displays `"N/A"` |
| **OCC: Model Performance** | Calibrated Brier Score | SQLite `monitoring.db` $\rightarrow$ `model_performance` | `REAL` (`brier_score`) | Mean squared difference between predicted probability and actual binary outcome | `parseFloat(data.brier_score).toFixed(3)` $\rightarrow$ `"0.082"` | Displays `"N/A"` |
| **OCC: Model Performance** | Evaluated Outcomes | SQLite `monitoring.db` $\rightarrow$ `flight_outcomes` | `INTEGER` (`COUNT(*)`) | FastAPI `/monitoring/model-performance` counts matched ground-truth records | Integer render `data.evaluated_flights_count` $\rightarrow$ `"1,420"` | Displays `0` if no flights evaluated |
| **OCC: Drift Detection** | Feature PSI Score | SQLite `monitoring.db` $\rightarrow$ `drift_metrics` | `REAL` (`max_psi`) | Population Stability Index computed over sliding 7-day inference window | `parseFloat(data.max_psi).toFixed(3)` $\rightarrow$ `"0.041"` | Displays `"N/A"` |
| **OCC: Drift Detection** | Drift Classification | SQLite `monitoring.db` $\rightarrow$ `drift_metrics` | `VARCHAR` (`drift_level`) | Rule: $\text{PSI} < 0.1 \rightarrow \text{STABLE}$; $0.1 \le \text{PSI} < 0.25 \rightarrow \text{MODERATE}$; $\ge 0.25 \rightarrow \text{HIGH}$ | CSS badge: `badge-success` (STABLE), `badge-warning`, or `badge-danger` | Fallback: `"UNKNOWN"` if metrics missing |
| **OCC: Regional Heatmap** | Airport Congestion Factor | Flight delay aggregation + Open-Meteo Weather API | `DOUBLE` (Mean Delay / Weather Severity) | FastAPI `/monitoring/heatmap` aggregates delay and convective weather index per IATA code | Airport card color scale: Green ($<15\text{m}$), Yellow ($15-45\text{m}$), Red ($>45\text{m}$) | Grey badge with `"OFFLINE"` |
| **Passenger Cockpit (Ananya)**| Case Recovery State | PostgreSQL `rescue_cases` table $\rightarrow$ `state` | `VARCHAR(32)` (`CaseState` Enum) | Spring Boot `CaseStateMachineService` enforces legal state transition | Step indicator progress bar (1 to 5) highlighting active state | Displays `"UNKNOWN_CASE"` |
| **Passenger Cockpit (Ananya)**| VCC Token & Budget | PostgreSQL `virtual_cards` table | `VARCHAR`, `DECIMAL` | `CorporateVccClient` issues single-use token with INR authorization limit | Masked card number `•••• •••• •••• 4242` and `₹15,000` limit | Blocked if card unissued |
| **Audit Log View** | Event Chronology | PostgreSQL `audit_events` table | `BIGINT`, `TIMESTAMP`, `VARCHAR` | Spring Boot `AuditService.getAuditTrail(caseId)` orders by `id ASC` | Rendered table rows with timestamp, actor badge, and details | Displays `"No audit entries for case"` |

---

## 3. Concurrency, Stale Response Protection & Request Sequencing

In a live flight operations center, operators frequently switch between flight views or trigger telemetry refreshes in rapid succession. Without deterministic request sequencing, network latency jitter can cause an earlier, slower request to resolve *after* a newer request, clobbering fresh telemetry with stale state.

### The Monotonic Request Sequence Algorithm
Stage 13B implements an epoch-based monotonic sequence guard inside `app.js`:

```javascript
let currentRefreshRequestId = 0;

async function refreshTelemetryData() {
  const thisRequestId = ++currentRefreshRequestId;
  setRefreshButtonState('loading');

  try {
    const [quality, drift, perf, alerts] = await Promise.all([
      fetchQualityMetrics(),
      fetchDriftMetrics(),
      fetchModelPerformance(),
      fetchActiveAlerts()
    ]);

    // Concurrency Check: If a newer request was dispatched while we were awaiting, abort commit
    if (thisRequestId !== currentRefreshRequestId) {
      console.warn(`[OCC Telemetry] Discarding stale telemetry response #${thisRequestId} (active: #${currentRefreshRequestId})`);
      return;
    }

    renderQualityMetrics(quality);
    renderDriftMetrics(drift);
    renderModelPerformance(perf);
    renderAlerts(alerts);
    setRefreshButtonState('success');
  } catch (err) {
    if (thisRequestId === currentRefreshRequestId) {
      handlePartialOrTotalSyncFailure(err);
    }
  }
}
```

---

## 4. Partial Sync & Failure State Handling

When one or more telemetry microservices experience transient network degradation or database lockouts, the Operations Control Center strictly adheres to **Graceful Degradation with Visible Alerting**:

1. **Partial Sync Indicator:** If 3 out of 4 endpoints succeed but the Drift service times out, the dashboard renders the successful metrics and displays an inline yellow warning badge: `⚠️ Partial Sync: Drift metrics currently unavailable`.
2. **No False Positives:** A failed drift service will NEVER display `STABLE`. It renders `DRIFT SERVICE OFFLINE` with an amber caution outline.
3. **Primary Sync Failure:** If all endpoints fail or the ML microservice is unreachable, the refresh button transitions to a red `Sync Failed - Retry` state, and an error banner appears across the OCC header with the HTTP status code and timestamp.
4. **Independent Panel Retry:** Each panel contains an independent retry button enabling dispatchers to refresh specific data streams without re-querying the entire backend.
