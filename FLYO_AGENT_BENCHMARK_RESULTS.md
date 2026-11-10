# FLYO RESCUE — EMPIRICAL AGENT BENCHMARK RESULTS (STAGE 11.1)

**Execution Date**: November 10, 2026  
**Runner Specification**: `./bin/agent-eval` (Python 3.14 CLI runner implementing `agent-eval` open spec)  
**Starting Commit SHA**: `76f1e78927f9d08bcc4ab3b1ec5cdaade0cef5c9`  
**Host Environment**: macOS Darwin 25.4.0 (arm64), Java 26.0.2, Node v22.23.4, Python 3.14.7  
**Benchmark Classification**: **Single-Agent Baseline Reproducibility Benchmark**  
*(Explicit Note: Only the engineered Flyo baseline agent was evaluated across isolated Git worktrees. This documents empirical baseline repeatability; it is not a comparative multi-agent leaderboard).*

---

## 1. Executive Summary: 7 Tasks, 21 Trials

All 7 declarative benchmark tasks under `tasks/flyo/` completed 3 independent trials each ($N=3$, total 21 trials) in isolated Git worktrees (`.eval_worktrees/<run_id>`). All 21 trials passed their deterministic command and AST/grep judges without any silent discards or omissions.

```
========================================================================================================================
Task                   Agent      Runs  Pass Rate  Mean Time  Median Time  pass@3  pass^3  Cost     Tokens
========================================================================================================================
booking-idempotency    baseline   3     100.0%     64.51s     62.83s       100.0%  100.0%  N/A      N/A
seed-isolation         baseline   3     100.0%     10.70s     11.90s       100.0%  100.0%  N/A      N/A
ml-integrity           baseline   3     100.0%     5.43s      5.50s        100.0%  100.0%  N/A      N/A
provider-boundaries    baseline   3     100.0%     36.04s     35.23s       100.0%  100.0%  N/A      N/A
saga-recovery          baseline   3     100.0%     52.00s     52.00s       100.0%  100.0%  N/A      N/A
tenant-isolation       baseline   3     100.0%     47.67s     44.16s       100.0%  100.0%  N/A      N/A
pnr-ingestion          baseline   3     100.0%     27.30s     27.72s       100.0%  100.0%  N/A      N/A
========================================================================================================================
TOTALS                 1 Agent    21    100.0%     34.81s     35.23s       100.0%  100.0%  $0.00*   0*
========================================================================================================================
* Token usage and API cost marked N/A: Not emitted by local runner execution.
```

---

## 2. Metric Definitions & Formulas

1. **`pass@k` ($k=3$)**: The probability of at least one successful run out of $k$ independent attempts:
   $$\text{pass@}k = 1 - (1 - p)^k = 1 - (1 - 1.0)^3 = 100.0\%$$
2. **`pass^k` ($k=3$)**: The probability of all $k$ independent runs succeeding consecutively without failure:
   $$\text{pass}^k = p^k = (1.0)^3 = 100.0\%$$
3. **`Mean Time`**: Arithmetic mean duration of all $k$ runs including workspace creation, compilation, test execution, judge validation, and cleanup.
4. **`Median Time`**: 50th-percentile execution time across the 3 runs.

---

## 3. Benchmark Task Catalog & YAML Checksums

Every task definition is authoritatively hashed with SHA-256 to guarantee immutable test requirements and prevent evaluation tampering:

| Task Name | Category | SHA-256 Checksum | Authoritative Judges |
| :--- | :--- | :--- | :--- |
| **`booking-idempotency`** | `distributed-safety` | `d62b0d47521bf63dbe72b5511f3703b34ed1c6c23fa1742ef0a72311a3fb7fb5` | `IdempotencyTest`, `ConcurrencyAndLoadTest`, `FinancialAndBookingSafetyTest` |
| **`seed-isolation`** | `core-isolation` | `ca52e2dd4c05fb9533662ec2d99c319d4aced05837327293986ec6f656898dfa` | `FlyoDataSeederProfileTest` (7 tests) + 3 Grep checks |
| **`ml-integrity`** | `predictive-safety` | `10758dc07c8b45d118bef4dfc0ba5310151a4ff597acd52550a5450814182722` | Pytest ML suite (36 tests) + Model SHA-256 Checksum Grep |
| **`provider-boundaries`** | `fail-closed-integrations` | `d1f17dd220ec44ec32409099b3b94466798e6728de47392b4f403734bf5c012d` | `Stage9BAdversarialProductionConfigTest` (11 tests), `ProviderAdaptersIntegrationTest` |
| **`saga-recovery`** | `distributed-coordination` | `38fd584d3a99d5fc23f2b099a275b9a6071e8ec1b71d637cac7bb7b5009f2914` | `Stage6DistributedSagaReliabilityTest` (5 tests), `OutboxAndSagaIntegrationTest` |
| **`tenant-isolation`** | `enterprise-multi-tenancy` | `aa66f5eb854883965db831f62ecef3256f5ff8cfa825f2a359fecf1e0d943800` | `Stage6EnterpriseSecurityAdversarialTest`, `Stage3EnterpriseSecurityTest` |
| **`pnr-ingestion`** | `domain-parsers` | `d0b48e6276eebcdc4ccc7660744801409c48a3d52cf1f793ca6725250dad4f7b` | `PnrIngestionTest` (6 tests) |

---

## 4. Full Audit Trail of All 21 Individual Trials

All trials were executed from baseline commit `76f1e78927f9d08bcc4ab3b1ec5cdaade0cef5c9` in detached Git worktrees.

| Run Index | Trial Identifier | Task | Duration | Judges | Result | Exit Code |
| :---: | :--- | :--- | :---: | :---: | :---: | :---: |
| 1 | `booking-idempotency-baseline-r1-96beac` | `booking-idempotency` | 59.76s | 3/3 passed | **PASS** | 0 |
| 2 | `booking-idempotency-baseline-r2-5ca4d9` | `booking-idempotency` | 62.83s | 3/3 passed | **PASS** | 0 |
| 3 | `booking-idempotency-baseline-r3-61418d` | `booking-idempotency` | 70.95s | 3/3 passed | **PASS** | 0 |
| 4 | `seed-isolation-baseline-r1-4a8cce` | `seed-isolation` | 7.39s | 4/4 passed | **PASS** | 0 |
| 5 | `seed-isolation-baseline-r2-cb9f75` | `seed-isolation` | 11.90s | 4/4 passed | **PASS** | 0 |
| 6 | `seed-isolation-baseline-r3-f985b1` | `seed-isolation` | 12.82s | 4/4 passed | **PASS** | 0 |
| 7 | `ml-integrity-baseline-r1-416013` | `ml-integrity` | 4.87s | 2/2 passed | **PASS** | 0 |
| 8 | `ml-integrity-baseline-r2-97a566` | `ml-integrity` | 5.91s | 2/2 passed | **PASS** | 0 |
| 9 | `ml-integrity-baseline-r3-170b77` | `ml-integrity` | 5.50s | 2/2 passed | **PASS** | 0 |
| 10 | `provider-boundaries-baseline-r1-b776e7` | `provider-boundaries` | 35.23s | 2/2 passed | **PASS** | 0 |
| 11 | `provider-boundaries-baseline-r2-b67e0c` | `provider-boundaries` | 45.06s | 2/2 passed | **PASS** | 0 |
| 12 | `provider-boundaries-baseline-r3-03727c` | `provider-boundaries` | 27.83s | 2/2 passed | **PASS** | 0 |
| 13 | `saga-recovery-baseline-r1-b4cfe2` | `saga-recovery` | 52.00s | 2/2 passed | **PASS** | 0 |
| 14 | `saga-recovery-baseline-r2-013bba` | `saga-recovery` | 52.23s | 2/2 passed | **PASS** | 0 |
| 15 | `saga-recovery-baseline-r3-bfd13c` | `saga-recovery` | 51.76s | 2/2 passed | **PASS** | 0 |
| 16 | `tenant-isolation-baseline-r1-546de3` | `tenant-isolation` | 55.22s | 2/2 passed | **PASS** | 0 |
| 17 | `tenant-isolation-baseline-r2-86a154` | `tenant-isolation` | 43.64s | 2/2 passed | **PASS** | 0 |
| 18 | `tenant-isolation-baseline-r3-2648bf` | `tenant-isolation` | 44.16s | 2/2 passed | **PASS** | 0 |
| 19 | `pnr-ingestion-baseline-r1-78c38e` | `pnr-ingestion` | 24.27s | 1/1 passed | **PASS** | 0 |
| 20 | `pnr-ingestion-baseline-r2-5d73e3` | `pnr-ingestion` | 29.91s | 1/1 passed | **PASS** | 0 |
| 21 | `pnr-ingestion-baseline-r3-99911d` | `pnr-ingestion` | 27.72s | 1/1 passed | **PASS** | 0 |

---

## 5. Worktree Isolation & Judge Integrity Guarantees

1. **Independent Worktrees**:
   - Every single trial run was executed inside a newly minted Git worktree created via `git worktree add --detach <path> <commit_sha>`.
   - No trial inherited file system modifications, uncommitted edits, or local caches from another trial.
   - Worktree cleanup was enforced via `git worktree remove --force <path>` after deterministic evaluation.
2. **Judge Immutability**:
   - The authoritative test suites reside in `flyo-rescue-demo/backend/src/test/java` and `ml/tests/`.
   - Because judges execute the compiled test classes defined in the immutable baseline commit `76f1e78`, evaluated agents cannot modify or weaken the assertions that grade their performance.
3. **Deterministic Evaluation**:
   - All judges rely on exit codes and concrete assertions rather than LLM-as-a-judge heuristics.
