#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

METRICS = ("LINE", "BRANCH", "INSTRUCTION")


def read_metrics(path: Path) -> dict[str, dict[str, float | int]]:
    root = ET.parse(path).getroot()
    counters = {counter.attrib["type"]: counter for counter in root.findall("counter")}
    result: dict[str, dict[str, float | int]] = {}
    for metric in METRICS:
        counter = counters.get(metric)
        if counter is None:
            raise SystemExit(f"Missing {metric} counter in {path}")
        covered = int(counter.attrib["covered"])
        missed = int(counter.attrib["missed"])
        total = covered + missed
        percent = (covered * 100.0 / total) if total else 100.0
        result[metric] = {
            "covered": covered,
            "missed": missed,
            "total": total,
            "percent": percent,
        }
    return result


def format_percent(value: float) -> str:
    return f"{value:.2f}%"


def format_delta(current: float, baseline: float) -> str:
    delta = current - baseline
    return f"{delta:+.2f} pp"


def main() -> None:
    parser = argparse.ArgumentParser(description="Summarize a Kover JaCoCo-compatible XML report.")
    parser.add_argument("current", type=Path)
    parser.add_argument("baseline", type=Path, nargs="?")
    parser.add_argument("--json-output", type=Path)
    args = parser.parse_args()

    current = read_metrics(args.current)
    baseline = read_metrics(args.baseline) if args.baseline and args.baseline.is_file() else None

    print("## Kotlin/JVM coverage")
    print()
    print("> Scope: Kotlin `commonMain` + desktop/JVM production code. Android-specific source sets, Kotlin/Native and Rust are not included in this project-level metric.")
    print()
    print("| Metric | Current | master baseline | Δ | Covered / total |")
    print("| --- | ---: | ---: | ---: | ---: |")
    for metric in METRICS:
        current_metric = current[metric]
        current_percent = float(current_metric["percent"])
        if baseline:
            baseline_percent = float(baseline[metric]["percent"])
            baseline_text = format_percent(baseline_percent)
            delta_text = format_delta(current_percent, baseline_percent)
        else:
            baseline_text = "—"
            delta_text = "—"
        label = metric.title()
        print(
            f"| {label} | {format_percent(current_percent)} | {baseline_text} | {delta_text} | "
            f"{current_metric['covered']} / {current_metric['total']} |"
        )

    if not baseline:
        print()
        print("_No successful master coverage artifact is available yet; the baseline will appear automatically after master publishes one._")

    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(
            json.dumps({"current": current, "baseline": baseline}, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
