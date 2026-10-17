#!/usr/bin/env python3
"""Coverage ratchet for the JVM unit tests (D72, docs/04-ci-y-release.md). Standard library only.

Reads each module's Kover report (`<module>/build/reports/kover/reportUnit.xml`, made by `koverXmlReportUnit`) and
compares it with the committed baseline (`config/coverage-baseline.json`).

  coverage.py summary  Markdown table per module (line %, branch %, baseline) on stdout, for $GITHUB_STEP_SUMMARY.
  coverage.py check    Fails (exit 1) if any module drops more than TOLERANCE points below its baseline, a module has
                       no baseline, or a module of the baseline has no report.
  coverage.py update   Rewrites the baseline with the current coverage. Values only go up unless --allow-decrease is
                       given (then the drop is visible in the reviewed diff of the baseline).

Run from the repository root (the Gradle tasks checkCoverageBaseline / updateCoverageBaseline do).
"""

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

TOLERANCE = 0.5
REPORT = Path("build/reports/kover/reportUnit.xml")
BASELINE = Path("config/coverage-baseline.json")
METRICS = ("line", "branch")


def percent(covered, missed):
    """Coverage in % rounded to 2 decimals, or None when there is nothing to cover."""
    total = covered + missed
    return None if total == 0 else round(100.0 * covered / total, 2)


def parse_report(path):
    """{"line": %, "branch": %} from the report-level counters of a JaCoCo-style XML report."""
    root = ET.parse(path).getroot()
    counters = {c.get("type"): c for c in root.findall("counter")}
    result = {}
    for metric in METRICS:
        counter = counters.get(metric.upper())
        result[metric] = None if counter is None else percent(int(counter.get("covered")), int(counter.get("missed")))
    return result


def collect(root):
    """{module: coverage} for every module directory that has a unit-test coverage report."""
    modules = (build.parent for build in sorted(Path(root).glob("*/build.gradle.kts")))
    return {module.name: parse_report(module / REPORT) for module in modules if (module / REPORT).is_file()}


def load_baseline(path):
    path = Path(path)
    return json.loads(path.read_text())["modules"] if path.is_file() else {}


def problems(current, baseline, tolerance=TOLERANCE):
    """Human-readable reasons the check fails; empty when coverage held."""
    found = []
    for module in sorted(set(current) | set(baseline)):
        if module not in baseline:
            found.append(f"{module}: no baseline; run ./gradlew updateCoverageBaseline and commit {BASELINE}")
            continue
        if module not in current:
            found.append(f"{module}: in the baseline but no coverage report (koverXmlReportUnit did not run?)")
            continue
        for metric in METRICS:
            base, now = baseline[module].get(metric), current[module].get(metric)
            if base is None:
                continue
            if now is None or now < base - tolerance:
                shown = "n/a" if now is None else f"{now:.2f} %"
                found.append(f"{module}: {metric} coverage {shown} < baseline {base:.2f} % - {tolerance} pt")
    return found


def updated(current, baseline, allow_decrease=False):
    """The new baseline: current values, never below the old ones unless allow_decrease."""
    result = {}
    for module, now in sorted(current.items()):
        old = baseline.get(module, {})
        entry = {}
        for metric in METRICS:
            value, before = now.get(metric), old.get(metric)
            if not allow_decrease and before is not None and (value is None or value < before):
                value = before
            entry[metric] = value
        result[module] = entry
    return result


def fmt(value):
    return "n/a" if value is None else f"{value:.1f} %"


def summary(current, baseline, tolerance=TOLERANCE):
    lines = [
        "### Unit-test coverage (Kover)",
        "",
        "| Module | Line | Branch | Baseline line | Baseline branch | |",
        "|---|---:|---:|---:|---:|---|",
    ]
    failing = {p.split(":", 1)[0] for p in problems(current, baseline, tolerance)}
    for module in sorted(set(current) | set(baseline)):
        now, base = current.get(module, {}), baseline.get(module, {})
        mark = "no baseline" if module not in baseline else "below baseline" if module in failing else "ok"
        lines.append(
            f"| {module} | {fmt(now.get('line'))} | {fmt(now.get('branch'))} | {fmt(base.get('line'))} "
            f"| {fmt(base.get('branch'))} | {mark} |"
        )
    lines += ["", f"A module fails when it drops more than {tolerance} pt below its baseline (`{BASELINE}`)."]
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("summary", "check", "update"))
    parser.add_argument("--root", default=".", help="repository root")
    parser.add_argument("--baseline", default=None, help=f"baseline file (default: <root>/{BASELINE})")
    parser.add_argument("--allow-decrease", action="store_true", help="update: also lower values")
    args = parser.parse_args(argv)

    baseline_path = Path(args.baseline) if args.baseline else Path(args.root) / BASELINE
    current = collect(args.root)
    baseline = load_baseline(baseline_path)
    if not current:
        print("No coverage reports found; run ./gradlew koverXmlReportUnit first.", file=sys.stderr)
        return 1

    if args.command == "summary":
        sys.stdout.write(summary(current, baseline))
        return 0
    if args.command == "check":
        found = problems(current, baseline)
        for problem in found:
            print(f"Coverage ratchet: {problem}", file=sys.stderr)
        if not found:
            print(f"Coverage ratchet: {len(current)} modules at or above their baseline.")
        return 1 if found else 0

    new = updated(current, baseline, args.allow_decrease)
    baseline_path.parent.mkdir(parents=True, exist_ok=True)
    baseline_path.write_text(json.dumps({"tolerancePoints": TOLERANCE, "modules": new}, indent=2) + "\n")
    print(f"Wrote {baseline_path} ({len(new)} modules).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
