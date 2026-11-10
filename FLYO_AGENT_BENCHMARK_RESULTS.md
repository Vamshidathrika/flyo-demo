# FLYO RESCUE — EMPIRICAL AGENT BENCHMARK RESULTS (STAGE 11)
**Date**: November 10, 2026  
**Harness**: `agent-eval` CLI Runner  
**Pinned Starting Commit**: `ca813189d5988e0bacff45724fa7b76791abf8c2`  
**Host Environment**: macOS Darwin 25.4.0 (arm64), Java 26, Python 3.14.7, Node v22.23.4  
**Data Storage**: `.eval_results/results.jsonl` and `.eval_results/runs/*.json`

---

## 1. Executive Benchmark Summary

Two representative Flyo engineering tasks were executed across independent Git worktrees with 3 repeated runs each ($N=3$) to measure pass rate, execution latency, and consistency (`pass@k` vs `pass^k`).

```
===================================================================================================
Task                   Agent              Runs   Pass Rate   Mean Time   pass@k   pass^k   Cost    
===================================================================================================
seed-isolation         baseline           3      100.0%      12.18s      100.0%   100.0%   N/A     
ml-integrity           baseline           3      100.0%      4.28s       100.0%   100.0%   N/A     
===================================================================================================
```

---

## 2. Benchmark Task 1: `seed-isolation`

- **Task**: `tasks/flyo/seed-isolation.yaml`
- **Objective**: Guarantee production/default profile safety and positive `@Profile({"dev", "demo", "test"})` isolation.
- **Judge 1 (Command)**: `mvn -f flyo-rescue-demo/backend/pom.xml test -Dtest=FlyoDataSeederProfileTest` (7 tests).
- **Judge 2 (Grep)**: Validates `@Profile({"dev", "demo", "test"})`.
- **Judge 3 (Grep)**: Validates `@ConditionalOnProperty(name = "flyo.seeding.enabled", havingValue = "true", matchIfMissing = false)`.
- **Judge 4 (Grep)**: Validates `flyo.seeding.enabled=false` in `application.properties`.

### Individual Run Results:
| Run ID | Agent | Isolated Worktree Path | Duration | Status | Deterministic Judges |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `seed-isolation-baseline-r1-429823` | baseline | `.eval_worktrees/seed-isolation-baseline-r1-429823` | 11.87s | **PASS** | 4/4 Judges Passed |
| `seed-isolation-baseline-r2-b22c5e` | baseline | `.eval_worktrees/seed-isolation-baseline-r2-b22c5e` | 11.91s | **PASS** | 4/4 Judges Passed |
| `seed-isolation-baseline-r3-65bd49` | baseline | `.eval_worktrees/seed-isolation-baseline-r3-65bd49` | 12.76s | **PASS** | 4/4 Judges Passed |

- **Mean Duration**: 12.18s
- **Median Duration**: 11.91s
- **`pass@3`**: 100.0% ($1 - (1 - 1.0)^3$)
- **`pass^3`**: 100.0% ($1.0^3$)
- **Cost**: `N/A: Not emitted by CLI`

---

## 3. Benchmark Task 2: `ml-integrity`

- **Task**: `tasks/flyo/ml-integrity.yaml`
- **Objective**: Verify Python ML predictive microservice verifies artifact checksums, handles unsupported airspace corridors fail-safe, and avoids data leakage.
- **Judge 1 (Command)**: `./ml/.venv/bin/python -m unittest discover -s ml/tests` (36 tests).
- **Judge 2 (Grep)**: Validates `UNSUPPORTED_AIRSPACE` status handling in `ml/service/predictor.py`.

### Individual Run Results:
| Run ID | Agent | Isolated Worktree Path | Duration | Status | Deterministic Judges |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `ml-integrity-baseline-r1-2e85d7` | baseline | `.eval_worktrees/ml-integrity-baseline-r1-2e85d7` | 4.44s | **PASS** | 2/2 Judges Passed (36/36 tests) |
| `ml-integrity-baseline-r2-30a622` | baseline | `.eval_worktrees/ml-integrity-baseline-r2-30a622` | 4.13s | **PASS** | 2/2 Judges Passed (36/36 tests) |
| `ml-integrity-baseline-r3-b5d01c` | baseline | `.eval_worktrees/ml-integrity-baseline-r3-b5d01c` | 4.27s | **PASS** | 2/2 Judges Passed (36/36 tests) |

- **Mean Duration**: 4.28s
- **Median Duration**: 4.27s
- **`pass@3`**: 100.0%
- **`pass^3`**: 100.0%
- **Cost**: `N/A: Not emitted by CLI`

---

## 4. Machine-Readable Result Records (JSON Excerpt)

```json
[
  {
    "task": "seed-isolation",
    "agent": "baseline",
    "runs": 3,
    "passed": 3,
    "pass_rate_pct": "100.0%",
    "mean_time_sec": "12.18s",
    "median_time_sec": "11.91s",
    "pass_at_k": "100.0%",
    "pass_pow_k": "100.0%",
    "cost": "N/A",
    "tokens": "N/A"
  },
  {
    "task": "ml-integrity",
    "agent": "baseline",
    "runs": 3,
    "passed": 3,
    "pass_rate_pct": "100.0%",
    "mean_time_sec": "4.28s",
    "median_time_sec": "4.27s",
    "pass_at_k": "100.0%",
    "pass_pow_k": "100.0%",
    "cost": "N/A",
    "tokens": "N/A"
  }
]
```

Full individual run execution records, including per-judge exit codes, stdout, stderr, and git diff snapshots, are persisted under `.eval_results/runs/`.
