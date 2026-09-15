#!/usr/bin/env python3
"""Dependency-free structural validator for TTS benchmark result JSON."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

TOP_LEVEL = {"schema_version", "run", "device", "engine", "capabilities", "measurements", "summary", "gate_evaluation"}
MEASUREMENT = {"passage_id", "category", "repetition", "characters", "words", "audio_duration_ms", "wall_synthesis_ms", "rtf", "first_audio_ms", "peak_rss_mb", "word_timing_count"}
SUMMARY = {"cold_initialization_ms", "rtf_p50", "rtf_p95", "warm_first_audio_p95_ms", "peak_rss_mb", "underruns", "word_start_error_median_ms", "word_start_error_p95_ms"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result", type=Path)
    parser.add_argument("--require-decision-ready", action="store_true")
    args = parser.parse_args()
    data = json.loads(args.result.read_text(encoding="utf-8"))
    errors: list[str] = []
    missing = TOP_LEVEL - data.keys()
    if missing:
        errors.append(f"missing top-level fields: {sorted(missing)}")
    if data.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    measurements = data.get("measurements", [])
    if not measurements:
        errors.append("measurements must not be empty")
    for index, measurement in enumerate(measurements):
        absent = MEASUREMENT - measurement.keys()
        if absent:
            errors.append(f"measurement {index} missing {sorted(absent)}")
        if measurement.get("audio_duration_ms", 0) <= 0 or measurement.get("rtf", -1) < 0:
            errors.append(f"measurement {index} has invalid duration or RTF")
    absent_summary = SUMMARY - data.get("summary", {}).keys()
    if absent_summary:
        errors.append(f"summary missing {sorted(absent_summary)}")
    status = data.get("gate_evaluation", {}).get("status")
    if status not in {"pass", "fail", "incomplete", "not-applicable"}:
        errors.append(f"invalid gate status: {status}")
    if args.require_decision_ready and status != "pass":
        errors.append(f"decision-ready result must pass every gate; status is {status}")
    if errors:
        print("Result validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"Result valid: {len(measurements)} measurements; gate status={status}")
    print("Use a JSON Schema 2020-12 validator with schemas/result.schema.json in CI for strict validation.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
