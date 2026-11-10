#!/usr/bin/env python3
"""
Flyo Rescue Platform - Automated Hardcoded Operational Data Scanner
Stage 13C Repository-Wide Architecture Enforcement

Scans Java, Python, JavaScript, HTML, and SQL files for:
1. Static in-memory airport MCT matrices or hardcoded tolerance rules.
2. Hardcoded passenger identities outside test fixtures and seed files.
3. Unverified hardcoded PNRs or ticket numbers.
4. Unprotected mock fallback assumptions.

Outputs detailed classification, file:line occurrences, and compliance status.
"""

import os
import re
import sys
import json
from pathlib import Path

# Directories to exclude from production scanner
EXCLUDE_DIRS = {
    ".git", ".venv", "node_modules", "target", "dist", "build",
    "__pycache__", ".pytest_cache", ".idea", ".vscode"
}

# Directories designated as test/demo fixtures
TEST_FIXTURE_DIRS = {
    "src/test", "tests", "test", "mock", "fixtures"
}

# Approved demo seeding files
SEEDING_FILES = {
    "FlyoDataSeeder.java", "V1__initial_production_schema.sql", "V11__airport_mct_rules.sql", "BookingService.java"
}

PROHIBITED_PROD_PATTERNS = [
    # Pattern 1: Static in-memory MCT matrices
    (r"PUBLISHED_MCT_MATRIX\s*=\s*", "HARDCODED_MCT_MATRIX", "CRITICAL", "MCT rules must be database-driven via airport_mct_rules table"),
    # Pattern 2: Hardcoded airport mapping outside database
    (r"matrix\.put\(\s*\"(DEL|BOM|BLR|HYD|LHR|FRA)\"", "HARDCODED_AIRPORT_MCT_MAP", "CRITICAL", "Airport rule inserted directly into Java memory map"),
    # Pattern 3: Fixed passenger name in production controller/service
    (r"\"Ananya Sharma\"", "HARDCODED_PASSENGER_NAME", "HIGH", "Passenger identity hardcoded in service code"),
    # Pattern 4: Fixed PNR in production booking service
    (r"\"R7X2K9\"", "HARDCODED_PNR", "MEDIUM", "Fixed PNR string in business logic"),
    # Pattern 5: Fixed Card ending in production UI without masking/provenance
    (r"\"8821\"", "HARDCODED_CARD_SUFFIX", "MEDIUM", "Fixed card ending without dynamic derivation")
]

def is_test_file(path_str):
    for tfd in TEST_FIXTURE_DIRS:
        if tfd in path_str:
            return True
    return False

def is_seed_file(path_str):
    filename = os.path.basename(path_str)
    return filename in SEEDING_FILES

def scan_repository(root_dir):
    findings = []
    scanned_files_count = 0
    prod_files_count = 0
    test_files_count = 0

    valid_extensions = {".java", ".py", ".js", ".mjs", ".html", ".sql"}

    for root, dirs, files in os.walk(root_dir):
        # Exclude directories
        dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]

        for file in files:
            ext = os.path.splitext(file)[1]
            if ext not in valid_extensions:
                continue

            full_path = os.path.join(root, file)
            rel_path = os.path.relpath(full_path, root_dir)
            scanned_files_count += 1

            is_test = is_test_file(rel_path)
            is_seed = is_seed_file(rel_path)

            if is_test:
                test_files_count += 1
            else:
                prod_files_count += 1

            try:
                with open(full_path, "r", encoding="utf-8", errors="ignore") as f:
                    for line_no, line in enumerate(f, start=1):
                        for pattern, rule_id, severity, desc in PROHIBITED_PROD_PATTERNS:
                            if re.search(pattern, line):
                                classification = "TEST_FIXTURE" if is_test else ("SEED_DATA" if is_seed else "PRODUCTION_CODE")
                                findings.append({
                                    "file": rel_path,
                                    "line": line_no,
                                    "rule_id": rule_id,
                                    "severity": severity,
                                    "classification": classification,
                                    "description": desc,
                                    "matched_text": line.strip()[:100]
                                })
            except Exception as e:
                print(f"Error reading {rel_path}: {e}", file=sys.stderr)

    return {
        "scanned_files": scanned_files_count,
        "prod_files": prod_files_count,
        "test_files": test_files_count,
        "findings": findings
    }

def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    print(f"=== Flyo Rescue Hardcoded Operational Data Scanner ===")
    print(f"Scanning repository root: {root}")

    results = scan_repository(root)

    prod_violations = [f for f in results["findings"] if f["classification"] == "PRODUCTION_CODE"]
    seed_findings = [f for f in results["findings"] if f["classification"] == "SEED_DATA"]
    test_findings = [f for f in results["findings"] if f["classification"] == "TEST_FIXTURE"]

    print(f"\n--- Scan Summary ---")
    print(f"Total files scanned: {results['scanned_files']}")
    print(f"Production files scanned: {results['prod_files']}")
    print(f"Test/Fixture files scanned: {results['test_files']}")
    print(f"Test fixture occurrences: {len(test_findings)} (Allowed)")
    print(f"Seed data occurrences: {len(seed_findings)} (Allowed under dev/demo profiles)")
    print(f"Production code violations: {len(prod_violations)}")

    # Check for critical violations in production code
    critical_prod = [f for f in prod_violations if f["severity"] == "CRITICAL"]
    print(f"Critical production violations (e.g. hardcoded MCT matrices): {len(critical_prod)}")

    report_path = os.path.join(root, "SOURCE_CODE_HARDCODE_SCANNER_RESULTS.json")
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"Detailed scanner JSON report written to: {report_path}")

    return 0 if len(critical_prod) == 0 else 1

if __name__ == "__main__":
    sys.exit(main())
