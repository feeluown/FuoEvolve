#!/usr/bin/env python3
"""Write a compact GitHub Actions test/coverage summary.

The script consumes Kover's JaCoCo-compatible XML, JUnit XML files produced by
Gradle, and the Git diff against the requested base revision. Coverage is
informational: this script never enforces a threshold.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def run_git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.stdout


def resolve_base(requested: str | None) -> str | None:
    candidates = [requested, "origin/master", "HEAD^"]
    for candidate in candidates:
        if not candidate or candidate == "0" * 40:
            continue
        result = subprocess.run(
            ["git", "rev-parse", "--verify", f"{candidate}^{{commit}}"],
            cwd=ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if result.returncode == 0:
            return candidate
    return None


def find_kover_xml(explicit: str | None) -> Path | None:
    if explicit:
        path = (ROOT / explicit).resolve()
        return path if path.is_file() else None

    preferred = [
        ROOT / "build/reports/kover/report.xml",
        ROOT / "build/reports/kover/xml/report.xml",
    ]
    for path in preferred:
        if path.is_file():
            return path

    matches = sorted((ROOT / "build/reports/kover").glob("**/*.xml"))
    return matches[0] if matches else None


def counter(report: ET.Element, kind: str) -> tuple[int, int]:
    for item in report.findall("counter"):
        if item.attrib.get("type") == kind:
            return int(item.attrib.get("covered", 0)), int(item.attrib.get("missed", 0))
    return 0, 0


def percent(covered: int, missed: int) -> str:
    total = covered + missed
    if total == 0:
        return "n/a"
    return f"{covered * 100.0 / total:.1f}%"


def coverage_index(report: ET.Element) -> dict[str, dict[int, bool]]:
    index: dict[str, dict[int, bool]] = {}
    for package in report.findall("package"):
        package_name = package.attrib.get("name", "").strip("/")
        for source in package.findall("sourcefile"):
            name = source.attrib.get("name", "")
            if not name:
                continue
            key = f"{package_name}/{name}" if package_name else name
            lines = index.setdefault(key, {})
            for line in source.findall("line"):
                number = int(line.attrib["nr"])
                missed = int(line.attrib.get("mi", 0))
                covered = int(line.attrib.get("ci", 0))
                if missed + covered > 0:
                    lines[number] = lines.get(number, False) or covered > 0
    return index


def source_key(path: str) -> str | None:
    normalized = path.replace("\\", "/")
    for marker in ("/kotlin/", "/java/"):
        if marker in normalized:
            return normalized.split(marker, 1)[1]
    return None


def changed_kotlin_lines(base: str) -> tuple[dict[str, set[int]], int, int, int]:
    diff = run_git(
        "diff",
        "--unified=0",
        "--no-color",
        f"{base}...HEAD",
        "--",
        "*.kt",
        "*.kts",
    )
    changed: dict[str, set[int]] = {}
    current_file: str | None = None
    for raw in diff.splitlines():
        if raw.startswith("+++ b/"):
            current_file = raw[6:]
            changed.setdefault(current_file, set())
            continue
        match = HUNK_RE.match(raw)
        if match and current_file:
            start = int(match.group(1))
            count = int(match.group(2) or "1")
            if count > 0:
                changed[current_file].update(range(start, start + count))

    files = additions = deletions = 0
    numstat = run_git("diff", "--numstat", f"{base}...HEAD")
    for raw in numstat.splitlines():
        parts = raw.split("\t", 2)
        if len(parts) != 3:
            continue
        files += 1
        if parts[0].isdigit():
            additions += int(parts[0])
        if parts[1].isdigit():
            deletions += int(parts[1])
    return changed, files, additions, deletions


def patch_coverage(
    changed: dict[str, set[int]],
    index: dict[str, dict[int, bool]],
) -> tuple[int, int, list[tuple[str, int]]]:
    executable = covered = 0
    uncovered: list[tuple[str, int]] = []
    for path, line_numbers in sorted(changed.items()):
        key = source_key(path)
        if key is None:
            continue
        coverage_lines = index.get(key)
        if not coverage_lines:
            continue
        for number in sorted(line_numbers):
            if number not in coverage_lines:
                continue
            executable += 1
            if coverage_lines[number]:
                covered += 1
            else:
                uncovered.append((path, number))
    return covered, executable, uncovered


def junit_totals() -> tuple[int, int, int, int, int]:
    tests = failures = errors = skipped = files = 0
    for path in ROOT.glob("**/build/test-results/**/TEST-*.xml"):
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            continue
        if root.tag != "testsuite":
            continue
        files += 1
        tests += int(float(root.attrib.get("tests", 0)))
        failures += int(float(root.attrib.get("failures", 0)))
        errors += int(float(root.attrib.get("errors", 0)))
        skipped += int(float(root.attrib.get("skipped", 0)))
    return tests, failures, errors, skipped, files


def write_summary(text: str) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as handle:
            handle.write(text)
            if not text.endswith("\n"):
                handle.write("\n")
    else:
        print(text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", help="Git revision used as the diff base")
    parser.add_argument("--kover-xml", help="Explicit Kover XML path relative to the repository root")
    args = parser.parse_args()

    base = resolve_base(args.base)
    report_path = find_kover_xml(args.kover_xml)

    lines = ["## Tests & coverage", "", "| Metric | Result |", "| --- | ---: |"]

    tests, failures, errors, skipped, result_files = junit_totals()
    if result_files:
        passed = max(0, tests - failures - errors - skipped)
        status = "✅" if failures + errors == 0 else "❌"
        detail = f"{status} {passed} passed"
        if skipped:
            detail += f", {skipped} skipped"
        if failures + errors:
            detail += f", {failures + errors} failed"
        lines.append(f"| JVM/Android test executions | {detail} |")
    else:
        lines.append("| JVM/Android test executions | no JUnit XML found |")

    report_root: ET.Element | None = None
    index: dict[str, dict[int, bool]] = {}
    if report_path:
        try:
            report_root = ET.parse(report_path).getroot()
            index = coverage_index(report_root)
        except (ET.ParseError, OSError) as exc:
            lines.append(f"| Kover report | unreadable: `{exc}` |")

    if report_root is not None:
        line_covered, line_missed = counter(report_root, "LINE")
        branch_covered, branch_missed = counter(report_root, "BRANCH")
        lines.append(
            f"| Overall line coverage | **{percent(line_covered, line_missed)}** "
            f"({line_covered}/{line_covered + line_missed}) |",
        )
        lines.append(
            f"| Overall branch coverage | **{percent(branch_covered, branch_missed)}** "
            f"({branch_covered}/{branch_covered + branch_missed}) |",
        )
    else:
        lines.append("| Overall coverage | Kover XML not found |")

    uncovered: list[tuple[str, int]] = []
    if base:
        try:
            changed, changed_files, additions, deletions = changed_kotlin_lines(base)
            lines.append(f"| Git diff vs `{base[:12]}` | {changed_files} files, +{additions} / -{deletions} |")
            if index:
                changed_covered, changed_executable, uncovered = patch_coverage(changed, index)
                changed_missed = changed_executable - changed_covered
                lines.append(
                    f"| Changed-line coverage | **{percent(changed_covered, changed_missed)}** "
                    f"({changed_covered}/{changed_executable} executable lines) |",
                )
        except subprocess.CalledProcessError as exc:
            lines.append(f"| Git diff | unavailable (git exited {exc.returncode}) |")
    else:
        lines.append("| Git diff | base revision unavailable |")

    lines.extend(
        [
            "",
            "> Coverage is informational only. This workflow does not enforce a coverage threshold.",
        ],
    )

    if uncovered:
        lines.extend(["", "### Uncovered changed lines", ""])
        for path, number in uncovered[:50]:
            lines.append(f"- `{path}:{number}`")
        if len(uncovered) > 50:
            lines.append(f"- … and {len(uncovered) - 50} more")

    write_summary("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
