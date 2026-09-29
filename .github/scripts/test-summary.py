"""Sums up the Surefire reports and fails if tests were skipped or none ran.

The Testcontainers tests are skipped (not failed) when Docker isn't available, so a
green build could otherwise hide the fact that the integration tests never ran.
Usage: python3 .github/scripts/test-summary.py <label>
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

label = sys.argv[1] if len(sys.argv) > 1 else "Tests"
totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
per_module = {}

for report in sorted(glob.glob("**/target/surefire-reports/TEST-*.xml", recursive=True)):
    suite = ET.parse(report).getroot()
    module = report.split(os.sep)[0].split("/")[0]
    counts = per_module.setdefault(module, {"tests": 0, "skipped": 0})
    for key in totals:
        value = int(suite.get(key, 0))
        totals[key] += value
        if key in counts:
            counts[key] += value

modules = ", ".join(f"{m} {c['tests']}" + (f" ({c['skipped']} skipped)" if c["skipped"] else "")
                    for m, c in per_module.items())
line = (f"{totals['tests']} tests, {totals['failures']} failures, {totals['errors']} errors, "
        f"{totals['skipped']} skipped - {modules}")

# Shown on the run page and readable through the public check-runs API.
print(f"::notice title={label}::{line}")

summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as out:
        out.write(f"### {label}\n\n{line}\n")

if totals["tests"] == 0:
    print(f"::error title={label}::No test reports found; the tests did not run.")
    sys.exit(1)
if totals["skipped"]:
    print(f"::error title={label}::{totals['skipped']} test(s) were skipped. "
          "Testcontainers tests skip when Docker is unavailable, so this build did not test everything.")
    sys.exit(1)
