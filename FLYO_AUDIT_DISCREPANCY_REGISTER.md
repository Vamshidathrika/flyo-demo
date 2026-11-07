# Flyo Master Audit Discrepancy Register & Evidence Reconciliation

**Document Status:** Technical Authority Record  
**Role:** Principal Forward Deployment Engineer & Engineering Director  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## Executive Summary

A comprehensive forensic audit of the Flyo codebase and existing audit documents was performed to reconcile conflicting claims, verify actual code implementations, and establish an unassailable baseline of truth. Multiple prior reports contained conflicting metrics, misunderstood architectural abstractions, or conflated demo mocks with production readiness.

This register formally resolves every discrepancy across the test suites, machine learning metrics, feature vector dimensions, persistence architectures, security postures, and external integration capabilities.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                             AUDIT RECONCILIATION SUMMARY MATRIX                                  │
├────────────────────┬─────────────────────────────┬─────────────────────────────┬─────────────────┤
│ Domain             │ Prior Conflicting Claims    │ Verified Code Truth         │ Operational Fact│
├────────────────────┼─────────────────────────────┼─────────────────────────────┼─────────────────┤
│ Java Test Count    │ 116 tests vs 27 test suites │ 28 test classes, 117 @Test  │ 117 tests pass  │
│                    │ (Phase 3 vs Phase 4 reports)│ methods across backend      │ against H2/mocks│
├────────────────────┼─────────────────────────────┼─────────────────────────────┼─────────────────┤
│ Python Test Count  │ 19 vs 26 test methods       │ 4 test files, 26 unit tests │ 26 tests pass   │
│                    │ across ML test suites       │ in `ml/tests/`              │ in virtualenv   │
├────────────────────┼─────────────────────────────┼─────────────────────────────┼─────────────────┤
│ Feature Contract   │ 16 features vs 79 columns   │ 16 raw source features      │ Both true: raw  │
│                    │ (Feature report vs schema)  │ expand to 79 one-hot columns│ vs vectorized   │
├────────────────────┼─────────────────────────────┼─────────────────────────────┼─────────────────┤
│ Disruption ML Model│ "Production Ready v2" vs    │ ROC-AUC 0.5279, Brier 0.0293│ ZERO recall at  │
│ Recall & Accuracy  │ "Zero Recall Model"         │ Worse than empirical prior  │ P>=0.20; UNFIT  │
├────────────────────┼─────────────────────────────┼─────────────────────────────┼─────────────────┤
│ Database Engine    │ PostgreSQL Production vs    │ Spring uses Postgres/H2;    │ Fragmented; dual│
│ Persistence Split  │ SQLite Monitoring DB        │ FastAPI uses local SQLite   │ storage engines │
├────────────────────┼─────────────────────────────┼─────────────────────────────┼─────────────────┤
│ Consequential API  │ "RBAC Protected via Spring" │ `SecurityConfig.java`: ALL  │ CRITICAL P0:    │
│ Security Filter    │ vs "Unauthenticated APIs"   │ consequential APIs permitAll│ Anonymous access│
├────────────────────┼─────────────────────────────┼─────────────────────────────┼─────────────────┤
│ External Airline   │ "Amadeus & Sabre Connected" │ Playwright mock portal only;│ 100% simulated  │
│ Rebooking Channel  │ vs "Simulated Execution"    │ GDS adapters throw exception│ rebooking       │
├────────────────────┼─────────────────────────────┼─────────────────────────────┼─────────────────┤
│ Outbox Pattern     │ "Durable Outbox Implemented"│ Events saved to DB, but no  │ Outbox events   │
│ Event Dispatcher   │ vs "Missing Dispatcher"     │ consumer/worker exists!     │ sit PENDING     │
└────────────────────┴─────────────────────────────┴─────────────────────────────┴─────────────────┘
```

---

## 1. Discrepancy Breakdown & Forensic Findings

### Finding 1: Java & Python Test Count Discrepancies
* **Audit References:** `PHASE_3_TEST_RESULTS.md` vs `PHASE_4_TEST_RESULTS.md` vs `FLYO_COMPLETE_PRODUCTION_READINESS_AUDIT.md`.
* **Discrepancy:** Phase 3 reported 27 Java test classes and 19 Python tests. Phase 4 reported 28 Java test classes / 116 tests and 26 Python tests. The complete readiness audit referenced 116 tests.
* **Verified Code Truth:**
  - In `flyo-rescue-demo/backend/src/test/java/com/flyo/rescue/`: Exactly **28 test Java files** exist.
  - Grep search on `@Test` annotations: Exactly **117 `@Test` methods** are defined across those 28 classes.
  - In `ml/tests/`: Exactly **4 test files** exist (`test_fastapi.py`, `test_bts_parser.py`, `test_evaluate.py`, `test_monitoring.py`).
  - Grep search on `def test_`: Exactly **26 test functions** exist across these 4 files.
* **Resolution:** The true verified test baseline is **117 Spring Boot tests** and **26 Python ML tests**. All pass cleanly in isolation against local H2 and mocks, but 0 tests validate live external airline systems.

---

### Finding 2: The 79-Column vs. 16-Source-Feature Confusion
* **Audit References:** `FEATURE_AVAILABILITY_REPORT.md` vs `MODEL_FEATURE_CONTRACT.md`.
* **Discrepancy:** Earlier data reports stated Flyo ML trains on "16 flight features", whereas the serving contract specifies "Exactly 79 numerical/binary features".
* **Verified Code Truth:**
  - Inspection of [`ml/src/bts_parser.py`](file:///Users/nani/Downloads/flyo/ml/src/bts_parser.py) and [`ml/src/leakage_free_preprocessor.py`](file:///Users/nani/Downloads/flyo/ml/src/leakage_free_preprocessor.py):
  - The raw Bureau of Transportation Statistics (BTS) dataset extracts **16 raw attributes** (`FL_DATE`, `OP_UNIQUE_CARRIER`, `ORIGIN`, `DEST`, `CRS_DEP_TIME`, `CRS_ARR_TIME`, `DISTANCE`, etc.).
  - The preprocessor extracts temporal peaks, target-encoded historical Bayesian priors (carrier, origin, dest, route cancellation and delay rates), and applies one-hot encoding across 16 airline columns (`carrier_*`), 21 origin hub columns (`origin_*`), and 21 destination hub columns (`dest_*`).
  - Together with the 21 numerical/derived features ($21 + 16 + 21 + 21 = 79$), the vectorized matrix passed to XGBoost has **exactly 79 columns**.
* **Resolution:** Both statements are correct at different abstraction layers. 16 raw features are ingested; 79 vectorized columns are fed to inference.

---

### Finding 3: Model Evaluation Metrics & Operational Inutility
* **Audit References:** `MODEL_EVALUATION_REPORT.md` vs `FLYO_ML_PRODUCTION_READINESS_AUDIT.md`.
* **Discrepancy:** Training logs claimed "Calibrated XGBoost v2 outperforming baselines", while the production audit claimed the model is functionally useless.
* **Verified Code Truth:**
  - In [`MODEL_EVALUATION_REPORT.md`](file:///Users/nani/Downloads/flyo/MODEL_EVALUATION_REPORT.md):
  - Cancellation model ($y_{\text{cancel}}$): Test ROC-AUC is **0.5279** (random chance is 0.5000). PR-AUC is **0.0318** against a 0.0296 base rate. Brier score is **0.02931** (worse than the naive prior Brier score of **0.02880**).
  - At operating threshold $P \ge 0.10$: Precision is 8.82%, but Recall is a catastrophic **0.41%** (capturing only 6 out of 1,481 cancellations, with 1,475 false negatives).
  - At threshold $P \ge 0.20$: Recall is **0.00%**.
  - Extreme delay model ($y_{\text{delay\_120}}$): ROC-AUC is **0.5950**, PR-AUC is **0.0693**, Brier score is **0.05075** (worse than empirical baseline of 0.04985).
* **Resolution:** The production audit is 100% correct. The cancellation model has zero operational discriminative power. It must be decoupled from all production decision gates.

---

### Finding 4: Dual Persistence Architecture (PostgreSQL vs. SQLite)
* **Audit References:** `FLYO_DATABASE_AND_BACKEND_AUDIT.md` vs `PHASE_4_MONITORING_IMPLEMENTATION_REPORT.md`.
* **Discrepancy:** System architecture diagrams depict a single unified PostgreSQL database, but monitoring reports reference SQLite ACID compliance.
* **Verified Code Truth:**
  - Spring Boot backend uses **PostgreSQL 14+** in production via [`application-prod.properties`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/resources/application-prod.properties) and Flyway migrations.
  - FastAPI inference service ([`ml/service/monitoring_db.py`](file:///Users/nani/Downloads/flyo/ml/service/monitoring_db.py)) independently initializes a local file-based SQLite database: `sqlite3.connect(str(self.db_path))`.
* **Resolution:** Persistence is fragmented. In a multi-replica Kubernetes deployment, FastAPI pods cannot share SQLite state, causing data partitioning of drift metrics and prediction logs.

---

### Finding 5: Anonymous Consequential API Exposure
* **Audit References:** `SECURITY_FINDINGS.md` vs `SecurityConfig.java`.
* **Discrepancy:** Documentation describes Role-Based Access Control (RBAC) and operator accounts, but security audits reported anonymous API execution.
* **Verified Code Truth:**
  - Inspection of [`SecurityConfig.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/SecurityConfig.java#L39-L62):
  ```java
  .requestMatchers(
      "/api/whatsapp/**", "/api/alerts/**", "/api/ingest/**",
      "/api/bookings/**", "/api/cases/**", "/api/payments/**",
      "/api/settlement/**", "/api/weather/**", "/api/intelligence/**"
  ).permitAll()
  ```
  - Consequential mutations (`POST /api/cases/{id}/approve`, `POST /api/payments/issue-card`, `POST /api/bookings/rebook`) permit anonymous access without HTTP headers, API keys, or JWT tokens.
  - The `operator_accounts` table created in Flyway V1 is completely unused in the security filter chain.
* **Resolution:** Critical P0 confirmed. The application is completely open to unauthenticated external tampering.

---

### Finding 6: The Dormant Outbox Engine
* **Audit References:** `FLYO_DATABASE_AND_BACKEND_AUDIT.md` vs `OutboxService.java`.
* **Discrepancy:** Flyo claims resilient asynchronous event publishing via the Transactional Outbox Pattern.
* **Verified Code Truth:**
  - In [`OutboxService.java`](file:///Users/nani/Downloads/flyo/flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/OutboxService.java), `publishEvent()` persists rows to `outbox_events` with status `PENDING`.
  - The retrieval method `getPendingEvents()` is called **nowhere** in the codebase.
  - There is no `@Scheduled` worker, no background poller, no Kafka bridge, and no message dispatcher.
* **Resolution:** Outbox events are written to the database and abandoned permanently. External side effects are executed synchronously inline.
