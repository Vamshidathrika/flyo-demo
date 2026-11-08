# Delayed Outcome Ingestion & Lifecycle Tracking Report

**Flyo Disruption Rescue Platform &middot; Phase 4 Engineering Verification**  
**Module:** `ml/service/monitoring_db.py` & `ml/service/main.py`  
**Deduplication Strategy:** Composite 5-Tuple Unique Constraint  

---

## 1. Lifecycle State Machine

Flight disruption risk predictions are generated pre-departure (hours or days before flight execution). The outcome cannot be known immediately. Flyo tracks prediction records through an explicit four-state lifecycle:

```
[ Prediction Inferred ]
           │
           ▼
     PENDING_OUTCOME
           │
           ├── (Ground truth outcome arrives & matched)
           ▼
   OUTCOME_AVAILABLE
           │
           ├── (Batch calibration & performance metrics computed)
           ▼
       EVALUATED
           │
   (or LABEL_UNAVAILABLE if flight diverted / data corrupt)
```

1. **`PENDING_OUTCOME`**: Flight has not yet operated; outcome is pending historical flight report arrival.
2. **`OUTCOME_AVAILABLE`**: Real-world flight outcome received and linked by flight identifier.
3. **`EVALUATED`**: Residual error, Brier score, and calibration bins calculated.
4. **`LABEL_UNAVAILABLE`**: Flight diverted, cancelled before reporting, or missing definitive records.

---

## 2. Ingestion & Deduplication Architecture

Delayed flight outcomes are ingested via `POST /monitoring/outcomes/ingest`. To prevent double-counting or corrupted calibration metrics, the database enforces a unique composite constraint across 5 key dimensions:

$$\text{Unique Key} = (\text{carrier\_code}, \text{flight\_number}, \text{origin\_airport}, \text{dest\_airport}, \text{flight\_date})$$

```sql
CREATE TABLE ground_truth_outcomes (
    outcome_id TEXT PRIMARY KEY,
    carrier_code TEXT NOT NULL,
    flight_number TEXT NOT NULL,
    origin_airport TEXT NOT NULL,
    dest_airport TEXT NOT NULL,
    flight_date TEXT NOT NULL,
    actual_dep_delay_minutes REAL NOT NULL,
    was_cancelled INTEGER NOT NULL,
    cancellation_code TEXT,
    ingested_at TEXT NOT NULL,
    source_feed TEXT,
    matched_predictions_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE (carrier_code, flight_number, origin_airport, dest_airport, flight_date)
);
```

---

## 3. Outcome Ingestion API Contract

### Request:
```http
POST /monitoring/outcomes/ingest
Content-Type: application/json
X-Flyo-Monitoring-Key: flyo-monitoring-secret-key-2026

{
  "carrier_code": "UA",
  "flight_number": "UA240",
  "origin_airport": "ORD",
  "dest_airport": "SFO",
  "flight_date": "2024-07-15",
  "actual_dep_delay_minutes": 145.0,
  "was_cancelled": false,
  "source_feed": "BTS Post-Flight Archive / Ground Radar"
}
```

### Response (New Outcome):
```json
{
  "outcome_id": "370525fa-88dc-493e-afb8-60bb91cf0a48",
  "status": "INGESTED",
  "matched_prediction_records_count": 1,
  "message": "Ground truth outcome recorded; matched 1 pending prediction records."
}
```

### Response (Duplicate Outcome):
```json
{
  "outcome_id": null,
  "status": "DUPLICATE_IGNORED",
  "matched_prediction_records_count": 0,
  "message": "Outcome already recorded for UA UA240 on 2024-07-15."
}
```

---

## 4. Verification Highlights

In `test_monitoring.py`:
- `test_delayed_outcome_matching_and_deduplication`: Confirms pending predictions transition `PENDING_OUTCOME` &rarr; `OUTCOME_AVAILABLE` upon ingestion.
- Submitting the same outcome twice is safely caught by `sqlite3.IntegrityError` and returns `DUPLICATE_IGNORED` with 0 double-counted records.
