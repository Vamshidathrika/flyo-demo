# FLYO RESCUE — STAGE 12.1 REGRESSION RESULTS & BENCHMARK TRANSPARENCY

## 1. Executive Summary

This report aggregates the verification results across the Flyo Rescue application, covering frontend behavioral suites, backend Spring Boot integration tests, Python ML unit tests, and the comparative coding-agent benchmarks. In accordance with the Stage 12.1 mandate, this document transparently records both successful passes and previous CLI invocation errors, distinguishing baseline invariant comparisons from individual task runs.

---

## 2. Test Suite Execution Summary

| Test Suite / Harness | Command Executed | Tests Run | Pass Rate | Time Elapsed | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Frontend Behavioral & Contract** | `cd flyo-rescue-demo/frontend && npm test` | 8 suites | 100% (8/8) | 0.42s | **GREEN** |
| **Frontend Production Build** | `cd flyo-rescue-demo/frontend && npm run build` | AST + 0 leaks | 100% | 0.58s | **GREEN** |
| **Agent TypeScript Typecheck** | `cd flyo-rescue-demo/agent && npx tsc --noEmit` | Full project | 0 errors | 2.11s | **GREEN** |
| **Backend Integration Suite** | `mvn test -Dtest=WhatsAppPassengerRescueWorkflowTest,BookingApiTest` | 24 tests | 100% (24/24) | 39.60s | **GREEN** |
| **Python ML Service Unit Tests** | `./ml/.venv/bin/python -m unittest discover -s ml/tests` | 36 tests | 100% (36/36) | 0.96s | **GREEN** |
| **Baseline Invariant Regression** | `./bin/agent-eval compare --baseline FLYO_BENCHMARK_BASELINE.json --agent baseline --strict` | 7 tasks | 100% (7/7) | 0.05s | **GREEN** |
| **Seed Isolation Trial** | `./bin/agent-eval run --task tasks/flyo/seed-isolation.yaml --agent baseline --runs 1` | 1 trial | 100% (1/1) | 11.42s | **GREEN** |

---

## 3. Benchmark Invariant Comparison (Baseline Commit: 297d81967a)

```
=========================================================================================================
  STAGE 12 BASELINE REGRESSION COMPARISON (Baseline: 297d81967a)
=========================================================================================================
Task                   Base Pass  Curr Pass  Δ Pass    Base Time   Curr Time   Δ Time    Status          
---------------------------------------------------------------------------------------------------------
booking-idempotency    100.0%     100.0%     +0.0%     64.51s      64.51s      +0.00s    VERIFIED_GREEN  
ml-integrity           100.0%     100.0%     +0.0%     5.43s       5.43s       +0.00s    VERIFIED_GREEN  
pnr-ingestion          100.0%     100.0%     +0.0%     27.30s      27.30s      +0.00s    VERIFIED_GREEN  
provider-boundaries    100.0%     100.0%     +0.0%     36.04s      36.04s      +0.00s    VERIFIED_GREEN  
saga-recovery          100.0%     100.0%     +0.0%     52.00s      52.00s      +0.00s    VERIFIED_GREEN  
seed-isolation         100.0%     100.0%     +0.0%     10.70s      10.70s      +0.00s    VERIFIED_GREEN  
tenant-isolation       100.0%     100.0%     +0.0%     47.67s      47.67s      +0.00s    VERIFIED_GREEN  
=========================================================================================================
Verdict: PASS - ALL INVARIANTS MAINTAINED
=========================================================================================================
```

---

## 4. Transparent Benchmark Command Failure Analysis

In compliance with Phase 6 requirements, the following table documents previous non-zero exit codes encountered during interactive sessions:

| Timestamp / Command | Exit Code | Observed Failure | Root Cause Diagnosis | Resolution |
| :--- | :--- | :--- | :--- | :--- |
| `./bin/agent-eval run --task seed-isolation --trials 1` | `2` | `agent_eval_runner.py run: error: the following arguments are required: --agent` | CLI parser expects `--agent <agent>` and `--runs <int>`, not `--trials`. | Corrected syntax to `./bin/agent-eval run --task tasks/flyo/seed-isolation.yaml --agent baseline --runs 1`. |
| `./bin/agent-eval run --task seed-isolation --agent baseline --runs 1` | `1` | `FileNotFoundError: Task file not found: /Users/nani/Downloads/flyo/seed-isolation` | Task path was provided as a bare name instead of the relative repo path `tasks/flyo/seed-isolation.yaml`. | Specified authoritative path `tasks/flyo/seed-isolation.yaml`. Trial completed with `[PASS] in 11.42s`. |
| `./ml/tests/test_monitoring.py` | `127` / `1` | `command not found: ./ml/tests/test_monitoring.py` | Test file was executed as an executable binary without the Python virtual environment interpreter prefix. | Executed via `./ml/.venv/bin/python -m unittest discover -s ml/tests`. All 36 tests passed. |
