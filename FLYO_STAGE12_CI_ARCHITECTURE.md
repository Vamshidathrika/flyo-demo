# FLYO RESCUE — CONTINUOUS ENGINEERING EVALUATION ARCHITECTURE (STAGE 12)

**Status**: ACTIVE / PRODUCTION QUALITY GATE  
**Version**: 1.0.0  
**Authors**: Flyo Reliability & Infrastructure Engineering  
**Baseline Anchor**: Commit [`297d819`](https://github.com/Vamshidathrika/flyo-demo/commit/297d819) via `FLYO_BENCHMARK_BASELINE.json`  

---

## 1. Architectural Mission & Philosophy

The objective of Flyo Stage 12 is to transform our Stage 11.1 benchmark evaluation framework from an offline experimental script into an **automated, permanent engineering quality gate** directly integrated into the GitHub Actions continuous integration pipeline.

### Core Design Principles:
1. **Eval-First CI Gating**: Pull requests cannot be merged solely on green unit tests; they must satisfy empirical benchmark invariants and demonstrate zero regression against the frozen Stage 11.1 baseline.
2. **Asymmetric Cost Management**: High-frequency pull requests run fast, targeted deterministic security suites and baseline invariant checks; full multi-agent cross-evaluations run on scheduled weekly schedules or manual trigger.
3. **Protected Judge Execution Boundary**: Evaluated agents and developer PR branches run inside isolated Git worktrees. Authoritative tests and task specifications are verified against SHA-256 baseline manifests; any attempt by code changes to weaken assertions triggers an immediate blocking security violation.
4. **Honest Metric Accounting**: Missing token or API cost metrics are recorded as `N/A: Not emitted by runner`, never fabricated as zero or simulated approximations.

---

## 2. CI Pipeline Topology & Workflow Architecture

The Stage 12 CI architecture consists of two interconnected GitHub Actions workflows:

```mermaid
flowchart TD
    subgraph PR_FLOW ["PR Quality Gate: .github/workflows/benchmark-gate.yml"]
        A["Pull Request Opened / Synchronized"] --> B["Path Filter & Domain Detection"]
        B --> C["PostgreSQL 16 Service Initialized"]
        C --> D["Set Up JDK 17, Python 3.11, Node 20"]
        D --> E["scripts/test_judge_protection.py<br/>(Adversarial Tampering Audit)"]
        E --> F["Execute Authoritative Security Suite<br/>(51 Deterministic Tests)"]
        F --> G["./bin/agent-eval compare --baseline FLYO_BENCHMARK_BASELINE.json --agent baseline --strict"]
        G -->|Invariant Violation / Regression| H["❌ Block PR Merge & Post Step Summary"]
        G -->|All Invariants Verified| I["✅ Approve PR Quality Gate"]
    end

    subgraph SCHEDULED_FLOW ["Scheduled Full Evaluation: .github/workflows/scheduled-benchmark.yml"]
        J["Cron: Weekly Monday 02:00 UTC / Manual Dispatch"] --> K["Concurrency Group: flyo-scheduled-benchmark-eval<br/>(Prevents Shared Storage Corruption)"]
        K --> L["Spin Up Isolated Git Worktrees"]
        L --> M["Run All 7 Tasks across Configured Agents<br/>(3 Trials per Task, N=21)"]
        M --> N["Generate Comparative Markdown & JSON Reports"]
        N --> O["Upload Build Artifacts & Alert on Drift"]
    end
```

---

## 3. Targeted PR Quality Gate (`benchmark-gate.yml`)

### Triggers & Path Filtering:
Runs on all pull requests targeting `main`, `develop`, or `release/*` that modify:
- `flyo-rescue-demo/backend/**`
- `ml/**`
- `tasks/flyo/**`
- `scripts/**`
- `FLYO_BENCHMARK_BASELINE.json`
- `.github/workflows/benchmark-gate.yml`

### Execution Sequence:
1. **Checkout Full Git History**: Ensures `git rev-parse` and worktree operations can reference the baseline commit `297d819`.
2. **Environment Matrix Setup**:
   - Temurin JDK 17 with Maven caching
   - Python 3.11 with pip dependencies (`fastapi`, `xgboost`, `shap`, `pyyaml`)
   - Node.js 20 with npm caching
   - Ephemeral PostgreSQL 16 Alpine container with health-check readiness probe
3. **Judge Protection & Tampering Verification**:
   - Executes `scripts/test_judge_protection.py` to ensure test suites and task YAML files match authoritative checksums.
4. **Authoritative 51-Test Security Suite**:
   Runs the 8 security-critical test classes:
   ```bash
   mvn test -f flyo-rescue-demo/backend/pom.xml \
     -Dtest="FlyoDataSeederProfileTest,Stage9BAdversarialProductionConfigTest,Stage6EnterpriseSecurityAdversarialTest,ConcurrencyAndLoadTest,IdempotencyTest,Stage6DistributedSagaReliabilityTest,ProviderIntegrationAndTenantTest,PnrIngestionTest"
   ```
5. **Deterministic Baseline Invariant Comparison**:
   Executes `./bin/agent-eval compare --baseline FLYO_BENCHMARK_BASELINE.json --agent baseline --strict --format markdown`.
   - Compares the candidate branch metrics against `FLYO_BENCHMARK_BASELINE.json`.
   - Appends the markdown comparison table directly to GitHub Actions Step Summary (`$GITHUB_STEP_SUMMARY`).
   - If any critical invariant is violated or pass rate drops, exits with code 1, automatically blocking the PR merge.

---

## 4. Scheduled Full Benchmark Suite (`scheduled-benchmark.yml`)

### Purpose & Schedule:
- Runs automatically every Monday at 02:00 UTC (or on demand via `workflow_dispatch`).
- Re-evaluates all 7 benchmark tasks across 3 independent trials ($N=21$) to detect environmental drift, JVM dependency updates, or latency shifts.

### Concurrency Lock:
```yaml
concurrency:
  group: flyo-scheduled-benchmark-eval
  cancel-in-progress: false
```
Guarantees that parallel triggers wait in queue rather than concurrently accessing `.eval_results/` or temporary worktree mounts.

### Artifact Retention:
Uploads:
- `latest_benchmark_report.md`
- `latest_benchmark_report.json`
- `.eval_results/results.jsonl`
- `.eval_results/runs/*.json`
Stored for 90 days for historical auditability and compliance reviews.

---

## 5. Security & Isolation Boundaries

1. **No Shared Git Worktrees**: Every evaluation trial runs inside a detached, freshly checked-out worktree (`.eval_worktrees/<run_id>`). Worktrees are created with `git worktree add --detach` and forcefully purged in a `finally:` block.
2. **Local Caching & Offline Test Execution**: Maven dependencies reside in `~/.m2` while Python dependencies reside in `ml/.venv`, symlinked into the isolated worktree. Outbound external HTTP network calls during tests are banned or strictly mocked with fail-closed assertions.
3. **Impenetrable Result Storage**: The authoritative result store (`.eval_results/results.jsonl`) resides outside the worktree. Agents executing within the worktree have zero file-system access to delete or modify historical benchmark logs.
