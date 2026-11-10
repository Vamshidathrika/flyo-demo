# FLYO RESCUE — CODING AGENT BENCHMARK STRATEGY (STAGE 11)
**Framework**: `agent-eval` + `eval-harness`  
**Target**: Autonomous Flight Disruption Recovery & Passenger Rescue Engine  
**Author**: Flyo System Architecture & Security Engineering

---

## 1. Executive Summary & Paradigm Shift

In AI-assisted software engineering, benchmarking has historically suffered from **self-reporting bias**: agents declare tasks "completed" and self-assert success based on superficial conversational impressions.

Stage 11 introduces a **reproducible, empirical coding-agent evaluation framework** for Flyo Rescue. The system transitions from trusting agent claims to measuring whether agent-authored modifications actually survive isolated, deterministic engineering quality gates.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        FLYO AGENT BENCHMARKING ARCHITECTURE                            │
├──────────────────────────┬─────────────────────────────┬───────────────────────────────┤
│    AGENT ADAPTERS        │     ISOLATION RUNNER        │     DETERMINISTIC JUDGES      │
│  (Codex, Claude, etc.)   │       (agent-eval)          │      (Code, AST, Tests)       │
├──────────────────────────┼─────────────────────────────┼───────────────────────────────┤
│ • Claude Code (2.1.252)  │ • Isolated Git Worktrees    │ • JUnit 5 / Surefire Tests    │
│ • Antigravity / Gemini   │ • Pinned Commit SHA Baseline│ • Python unittest Suites      │
│ • Deterministic Baseline │ • Process Sandboxing        │ • Multi-Tenant Security Audits│
│ • Custom Script Adapters │ • Clean Worktree Teardown   │ • AST & Regex Grep Guards     │
└──────────────────────────┴─────────────────────────────┴───────────────────────────────┘
                                       │
                                       ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        EMPIRICAL ENGINEERING SCORECARD                                 │
│  Pass Rate (%) │ Mean/Median Latency (s) │ pass@k │ pass^k │ API Cost │ Regressions   │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Separation of Concerns: Evaluation vs Quality Gates

To prevent conflation between comparative agent selection and continuous CI delivery, Flyo maintains strict separation between two complementary systems:

| Dimension | Agent Evaluation (`agent-eval`) | Engineering Quality Gates (`eval-harness`) |
| :--- | :--- | :--- |
| **Primary Question** | *"Which coding agent writes the most reliable Flyo code?"* | *"Does this specific code change satisfy Flyo's correctness criteria?"* |
| **Execution Context** | Ephemeral Git worktrees running competitive head-to-head trials. | CI/CD commit hooks and deployment pipelines before staging/prod merges. |
| **Key Output** | Comparative scorecard: pass rate, latency, cost, `pass@k`, `pass^k`. | Binary GO / NO-GO gating decision; blocks merge if any regression occurs. |
| **Failure Policy** | Recorded as empirical failure data point for model ranking. | Aborts pipeline immediately; halts release. |

---

## 3. Git Worktree Isolation Architecture

To ensure 100% fair and non-interfering agent trials:
1. **Pinned Starting Commit**: Every benchmark run checks out the exact same commit SHA (e.g. `ca813189d5`).
2. **Dedicated Git Worktree**: Each trial runs in its own directory (`.eval_worktrees/<task>-<agent>-r<idx>-<uuid>`) on an isolated temporary branch (`eval-...`).
3. **Zero Cross-Contamination**: An agent modifying files in run 1 cannot affect run 2 or corrupt the developer's working directory.
4. **Automated Teardown**: Upon completion, the worktree and temporary branch are pruned cleanly, while git diffs, judge logs, and performance metrics are preserved in `.eval_results/runs/`.

---

## 4. Deterministic Test Judges over Vibes

Grep checks alone are fragile; an agent can game a grep check by inserting inert comments. Flyo benchmarks employ **multi-layered executable judges**:
1. **Executable Unit & Integration Suites**: Compiles and executes real tests (`mvn test`, `python -m unittest`).
2. **Adversarial Profile Invocations**: Verifies that components fail closed when profiles are unset or misconfigured.
3. **Behavioral Acceptance Criteria**: Validates that idempotency locks, tenant query predicates, and refund compensations behave correctly under concurrent execution.

---

## 5. Mathematical Metrics Formulation

Flyo tracks both empirical and probabilistic metrics across $N$ trials:

### 5.1 Pass Rate ($p$)
$$\text{Pass Rate} = \frac{\text{Passed Runs}}{\text{Total Runs}}$$

### 5.2 `pass@k` (Reliability Metric)
`pass@k` measures the probability that an agent succeeds **at least once** within $k$ independent attempts:
$$\text{pass@k} = 1 - (1 - p)^k$$
*Application*: For exploratory or assisted tasks where an operator needs at least one viable solution out of $k$ generations.

### 5.3 `pass^k` (Consistency Metric)
`pass^k` measures the probability that an agent succeeds across **all $k$ consecutive runs**:
$$\text{pass}^k = p^k$$
*Application*: For mission-critical autonomous tasks (e.g., automated ticketing or financial settlement) where zero failure variance is tolerated.

### 5.4 Latency & Cost Accounting
- **Mean & Median Time**: Wall-clock seconds from prompt dispatch to judge completion.
- **API Cost & Tokens**: Extracted directly from CLI telemetry when emitted by the agent runner. When an agent CLI does not emit token accounting, cost is strictly reported as `N/A: Not emitted by CLI` rather than fabricated.

---

## 6. Safety & Non-Negotiable Boundaries

1. **Zero Production Risk**: Benchmarks execute in isolated worktrees using H2 in-memory databases and mock providers.
2. **No Fabricated Commercial Transactions**: Benchmarks never touch live airline reservation gateways or moving bank lines.
3. **Critical Security Failure Policy**: Any agent change that introduces a security regression (e.g. leaking seed data in `prod`, allowing cross-tenant reads, or hardcoding passwords) is classified as an immediate fatal score (0%), regardless of speed.
