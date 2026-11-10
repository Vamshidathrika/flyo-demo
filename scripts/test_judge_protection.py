#!/usr/bin/env python3
"""
Flyo Rescue — Judge Protection & Adversarial Tampering Verification Suite
Verifies that:
1. Unauthorized agent modifications to test suites are caught by the Judge Protection Boundary.
2. Protected test files are authoritatively restored from the baseline git commit before judging.
3. Tampering attempts fail with SECURITY_JUDGE_TAMPERING_DETECTED.
4. Naive/adversarial agent regressions (e.g. !prod seeding) are caught by deterministic judges.
"""

import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent


def test_judge_tampering_detection():
    print("[TEST 1/3] Testing Judge Protection Boundary against adversarial test tampering...")
    cmd = [
        str(REPO_ROOT / "bin/agent-eval"),
        "run",
        "--task", "tasks/flyo/seed-isolation.yaml",
        "--agent", "adversarial-tamperer",
        "--runs", "1"
    ]
    proc = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    
    # Read the latest run result from .eval_results/runs/
    runs_dir = REPO_ROOT / ".eval_results/runs"
    latest_run_file = sorted(runs_dir.glob("seed-isolation-adversarial-tamperer-*.json"))[-1]
    with open(latest_run_file, "r") as f:
        run_data = json.load(f)

    assert not run_data["passed"], "Adversarial run attempting test tampering MUST fail!"
    guards = [j for j in run_data["judge_results"] if j["judge_type"] == "security_boundary"]
    assert len(guards) > 0, "Security boundary judge result MUST be present!"
    assert "SECURITY_JUDGE_TAMPERING_DETECTED" in guards[0]["details"], \
        f"Details must report tampering, got: {guards[0]['details']}"
    print("  ✔ Adversarial tampering detected and blocked by Judge Protection Guard.")


def test_naive_agent_security_regression():
    print("[TEST 2/3] Testing Deterministic Judge against naive agent security regression...")
    cmd = [
        str(REPO_ROOT / "bin/agent-eval"),
        "run",
        "--task", "tasks/flyo/seed-isolation.yaml",
        "--agent", "naive-llm",
        "--runs", "1"
    ]
    proc = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    
    runs_dir = REPO_ROOT / ".eval_results/runs"
    latest_run_file = sorted(runs_dir.glob("seed-isolation-naive-llm-*.json"))[-1]
    with open(latest_run_file, "r") as f:
        run_data = json.load(f)

    assert not run_data["passed"], "Naive LLM using !prod MUST fail positive profile judge!"
    grep_checks = [j for j in run_data["judge_results"] if j["judge_type"] == "grep" and not j["passed"]]
    assert len(grep_checks) > 0, "Positive profile grep judge must fail on !prod mutation!"
    print("  ✔ Naive security relaxation (!prod) caught by deterministic judge.")


def test_baseline_compare_regression_flagging():
    print("[TEST 3/3] Testing Stage 12 Baseline Regression Intelligence...")
    cmd = [
        str(REPO_ROOT / "bin/agent-eval"),
        "compare",
        "--baseline", "FLYO_BENCHMARK_BASELINE.json",
        "--format", "json"
    ]
    proc = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    assert proc.returncode == 0, f"Compare exited with {proc.returncode}"
    comp_data = json.loads(proc.stdout)
    assert comp_data["baseline_commit"] is not None
    assert comp_data["status"] in ["PASS", "FAIL"]
    print("  ✔ Regression intelligence comparison verified against frozen baseline manifest.")


def main():
    print("================================================================================")
    print("  FLYO RESCUE — STAGE 12 JUDGE PROTECTION & REGRESSION ADVERSARIAL SUITE")
    print("================================================================================")
    test_judge_tampering_detection()
    test_naive_agent_security_regression()
    test_baseline_compare_regression_flagging()
    print("================================================================================")
    print("  ALL 3 ADVERSARIAL JUDGE PROTECTION TESTS PASSED CLEANLY (3/3)")
    print("================================================================================")


if __name__ == "__main__":
    main()
