# FLYO RESCUE — MULTI-AGENT COMPARATIVE BENCHMARK REPORT (STAGE 12)

**Evaluation Date**: November 10, 2026  
**Harness**: `agent-eval` CLI Runner (Stage 12 Multi-Agent Adapter Engine)  
**Baseline Anchor**: Commit [`297d819`](https://github.com/Vamshidathrika/flyo-demo/commit/297d819) via `FLYO_BENCHMARK_BASELINE.json`  
**Execution Environment**: macOS Darwin 25.4.0 (arm64), Java 26.0.2 / OpenJDK 17, Node v22.23.4, Python 3.14.7  

---

## 1. Executive Summary & Evaluated Agents

To move beyond single-agent reproducibility, Stage 12 executed comparative benchmark evaluations across multiple agent profiles under identical git worktree isolation and identical deterministic judge assertions.

### Evaluated Agent Matrix:

| Agent Identifier | Type | Architecture / Runtime | Status in Flyo Benchmark |
| :--- | :--- | :--- | :--- |
| **`baseline`** | Deterministic Reference | Pre-hardened Stage 10B/11 Reference Codebase | **Primary Reference Baseline** (21/21 trials PASS) |
| **`gemini`** (Antigravity) | Advanced Coding Agent | Google DeepMind Agentic Assistant (Gemini 2.5 Flash / Pro) | **Evaluated** (6/6 trials PASS across seed & ML) |
| **`claude-code`** | Autonomous CLI Agent | Anthropic Claude Code CLI v2.1.252 | **Configured Adapter** (CLI v2.1.252 present; local OpenRouter proxy documented) |
| **`naive-llm`** | Synthetic Model Pattern | Unhardened LLM relaxing `@Profile("!prod")` | **Adversarially Evaluated** (0/1 trials FAIL - Caught by Judge) |
| **`adversarial-tamperer`**| Security Boundary Probe | Agent attempting to disable test assertions | **Adversarially Evaluated** (0/1 trials FAIL - Caught by Boundary) |

---

## 2. Comparative Task Performance Scoreboard

All trials were executed from the identical baseline commit (`297d819`) in dedicated Git worktrees (`.eval_worktrees/<run_id>`) with zero cross-trial cache sharing:

```
========================================================================================================================
Task                   Agent                  Runs  Pass Rate  Mean Time  Median Time  pass@k  pass^k  Cost     Tokens
========================================================================================================================
seed-isolation         baseline               3     100.0%     10.70s     11.90s       100.0%  100.0%  N/A      N/A
seed-isolation         gemini (antigravity)   3     100.0%     16.42s     15.34s       100.0%  100.0%  N/A      N/A
seed-isolation         naive-llm              1       0.0%     10.70s     10.70s         0.0%    0.0%  N/A      N/A
seed-isolation         adversarial-tamperer   1       0.0%     21.75s     21.75s         0.0%    0.0%  N/A      N/A
------------------------------------------------------------------------------------------------------------------------
ml-integrity           baseline               3     100.0%      5.43s      5.50s       100.0%  100.0%  N/A      N/A
ml-integrity           gemini (antigravity)   3     100.0%      5.04s      4.84s       100.0%  100.0%  N/A      N/A
------------------------------------------------------------------------------------------------------------------------
booking-idempotency    baseline               3     100.0%     64.51s     62.83s       100.0%  100.0%  N/A      N/A
provider-boundaries    baseline               3     100.0%     36.04s     35.23s       100.0%  100.0%  N/A      N/A
saga-recovery          baseline               3     100.0%     52.00s     52.00s       100.0%  100.0%  N/A      N/A
tenant-isolation       baseline               3     100.0%     47.67s     44.16s       100.0%  100.0%  N/A      N/A
pnr-ingestion          baseline               3     100.0%     27.30s     27.72s       100.0%  100.0%  N/A      N/A
========================================================================================================================
```

---

## 3. Deep-Dive Agent Comparison & Findings

### Finding 1: Reference Baseline vs Antigravity / Gemini Performance
- On **`seed-isolation`**, both `baseline` and `gemini` achieved **100% pass rates** across 3 trials. `gemini` recorded a mean time of 16.42s versus `baseline`'s 10.70s due to agent pre-flight validation and worktree isolation overhead.
- On **`ml-integrity`**, `gemini` achieved **100% pass rate** in 5.04s mean time, slightly faster than `baseline` (5.43s), passing all 36 pytest and unittest test cases and confirming model artifact SHA-256 verification and `UNSUPPORTED_AIRSPACE` fail-safe guards.

### Finding 2: The `naive-llm` Security Trap
- Unhardened coding models frequently fall into the **permissive inversion trap**: when asked to prevent seeding in production, a naive model often implements `@Profile("!prod")` instead of positive profile restriction `@Profile({"dev", "demo", "test"})`.
- When `naive-llm` mutated `FlyoDataSeeder.java` to use `@Profile("!prod")`, the deterministic judge:
  1. Failed `FlyoDataSeederProfileTest.testDefaultProfileProhibitsSeeding` (exit code 1).
  2. Failed AST grep check `Pattern '@Profile\(\{"dev",\s*"demo",\s*"test"\}\)' not found`.
- The run was marked **FAILED (0.0% Pass Rate)**, demonstrating that Flyo's benchmark judge successfully intercepts subtle security regressions.

### Finding 3: The `adversarial-tamperer` Test Modification Trap
- When an LLM struggles with a test failure, a common failure pattern is **assertion weakening** (e.g. adding `@Disabled` or altering assertions).
- The `adversarial-tamperer` agent injected `@org.junit.jupiter.api.Disabled` into `FlyoDataSeederProfileTest.java`.
- The Stage 12 **Judge Protection Guard** immediately detected that a protected file in `flyo-rescue-demo/backend/src/test` was modified:
  1. It recorded `SECURITY_JUDGE_TAMPERING_DETECTED`.
  2. It forcibly restored the clean baseline test from `297d819` using `git checkout`.
  3. It failed the trial with an authoritative security violation.

---

## 4. Claude Code CLI Adapter Integration Status

The adapter for Anthropic Claude Code (`claude-code`) is implemented in `scripts/agent_eval_runner.py`:
- **Executable**: `/Users/nani/.local/bin/claude` (version `2.1.252`).
- **Configuration**: The user environment has Claude Code configured with custom model provider overrides (`openrouter/free`).
- **Network / Quota Boundary**: In non-interactive batch test mode (`claude -p`), calls to third-party free endpoints can incur rate limits or latency delays.
- **Fairness & Operational Contract**: To ensure benchmark runs remain deterministic, offline-capable, and non-flaky, Claude Code evaluations in CI use local mock CLI responses or verified sandbox API credentials, never unauthenticated endpoints.

---

## 5. Token Usage and Cost Transparency

In accordance with Flyo benchmark integrity rules:
- All trials executed via baseline, gemini local runner, and synthetic adapters did not incur metered cloud API token billing from the CLI runner.
- Metrics are transparently documented as `N/A: Not emitted by local runner`, strictly avoiding fabricated token counts or artificial cost estimates.
