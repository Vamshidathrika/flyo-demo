# FLYO RESCUE — AGENT EVALUATION OPERATOR RUNBOOK (STAGE 11)
**Tool**: `agent-eval` CLI Runner (`./bin/agent-eval`)  
**Scope**: Operational Runbook for Running Multi-Agent Benchmarks & Authoring Tasks

---

## 1. Quick Start

### 1.1 List Available Tasks
```bash
./bin/agent-eval list
```
Displays all declarative task contracts currently defined under `tasks/flyo/`.

### 1.2 Run a Benchmark on a Single Agent
```bash
./bin/agent-eval run --task tasks/flyo/seed-isolation.yaml --agent baseline --runs 3
```

### 1.3 Run a Comparative Multi-Agent Benchmark
```bash
./bin/agent-eval run --task tasks/flyo/seed-isolation.yaml --agent baseline --agent claude --runs 3
```

### 1.4 Generate Summary Reports
```bash
# Print formatted ASCII table
./bin/agent-eval report

# Generate Markdown table for PR comments or documentation
./bin/agent-eval report --format markdown

# Generate Machine-Readable JSON for CI pipelines
./bin/agent-eval report --format json
```

---

## 2. Authoring a New Flyo Benchmark Task

To add a new benchmark task to `tasks/flyo/`:

1. Create `tasks/flyo/<task-name>.yaml`.
2. Follow the standard schema:
```yaml
name: my-new-task
version: "1.0.0"
category: correctness
tier: domain-feature
description: Concise description of what this task measures.
repo: .
commit: ca813189d5988e0bacff45724fa7b76791abf8c2
timeout: 180
max_changes: 15

files:
  - flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/MyService.java

prompt: |
  Implement or fix the required functionality in MyService:
  1. Detailed requirement 1.
  2. Detailed requirement 2.
  3. Ensure all tests in MyServiceTest pass with 0 errors.

setup:
  - command: "mvn -f flyo-rescue-demo/backend/pom.xml compile -DskipTests"
    description: "Verify sources compile cleanly before task execution"

judge:
  - type: command
    name: "My Service Test Suite"
    command: "mvn -f flyo-rescue-demo/backend/pom.xml test -Dtest=MyServiceTest"
    description: "Executes unit and integration tests"
  - type: grep
    name: "Pattern Verification"
    pattern: "expected_method_call"
    files: "flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/service/MyService.java"
    description: "Validates code structure"

acceptance_criteria:
  - "Criterion 1"
  - "Criterion 2"

cleanup:
  - "git checkout -- ."
```

---

## 3. Interpreting Output Metrics

| Metric | Interpretation | Target | Action on Failure |
| :--- | :--- | :--- | :--- |
| **Pass Rate** | Fraction of runs passing all deterministic judges. | **100%** | Investigate failure logs in `.eval_results/runs/<run_id>.json`. |
| **`pass@k`** | Probability of at least 1 success in $k$ attempts. | **100%** | If $<100\%$, agent is fundamentally unable to solve the task. |
| **`pass^k`** | Consistency rate across all $k$ consecutive runs. | **$\ge 90\%$** | If low, agent output is non-deterministic or flaky; prompt requires constraints. |
| **Mean Time** | Average execution latency in seconds. | Task dependent | Flag any agent run that exceeds 50% above baseline. |
| **Cost / Tokens**| API expenditure per task. | Track trends | If N/A, CLI did not emit token metrics; record as unavailable. |

---

## 4. Troubleshooting & Worktree Maintenance

### What to do if a worktree fails to unmount:
The runner automatically runs `git worktree remove --force <path>` and `git branch -D <branch>`.  
If an unclean shutdown occurs, manually prune stale worktrees:
```bash
git worktree prune
rm -rf .eval_worktrees/*
```

### Inspecting run diffs:
Every run records its exact diff, stdout, and exit codes:
```bash
cat .eval_results/runs/<run_id>.json | jq '.diff_stat, .judge_results'
```
