#!/usr/bin/env python3
"""Validate the checked-in alignment fixtures.

The Kotlin gate reads these files and reports a number. If a file is malformed the number is
still a number, and it looks like an alignment regression rather than a broken fixture, so the
shape is checked separately and first.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from make_fixture import words

ROOT = Path(__file__).resolve().parents[2]
CASES = ROOT / "test-fixtures" / "alignment" / "cases"
GRANULARITIES = {"WORD", "SENTENCE", "CHAPTER", "NONE"}


def fold(value: str) -> str:
    return "".join(character.lower() for character in value if character.isalnum())


def check_hypothesis(hypothesis: list) -> list[str]:
    errors: list[str] = []
    previous_start = -1
    for index, token in enumerate(hypothesis):
        if not isinstance(token.get("text"), str) or not token["text"].strip():
            errors.append(f"token {index}: text must be a non-blank string")
            continue
        start, end = token.get("startMs"), token.get("endMs")
        if not isinstance(start, int) or not isinstance(end, int) or start < 0 or end < start:
            errors.append(f"token {index}: startMs and endMs must be ordered non-negative integers")
            continue
        if start < previous_start:
            errors.append(f"token {index}: startMs moves backwards")
        previous_start = start
    return errors


def check_expected(expected: dict, reference: str) -> list[str]:
    errors: list[str] = []
    if expected.get("granularity") not in GRANULARITIES:
        errors.append(f"granularity {expected.get('granularity')!r} is not one of {sorted(GRANULARITIES)}")
    keys = [fold(word) for word in words(reference)]
    for onset in expected.get("onsets", []):
        quote = [fold(word) for word in words(onset.get("quote", ""))]
        if not quote:
            errors.append("an onset has an empty quote")
            continue
        if not any(keys[index:index + len(quote)] == quote for index in range(len(keys))):
            errors.append(f"quote {onset['quote']!r} does not occur in the reference")
        if not isinstance(onset.get("onsetMs"), int) or onset["onsetMs"] < 0:
            errors.append(f"quote {onset['quote']!r} must have a non-negative integer onsetMs")
    return errors


def main() -> int:
    errors: list[str] = []
    case_dirs = sorted(path for path in CASES.iterdir() if path.is_dir())
    if not case_dirs:
        print(f"no fixture cases found under {CASES}", file=sys.stderr)
        return 1
    for case_dir in case_dirs:
        case = json.loads((case_dir / "case.json").read_text(encoding="utf-8"))
        reference = (case_dir / case["reference"]).read_text(encoding="utf-8")
        hypothesis = json.loads((case_dir / "hypothesis.json").read_text(encoding="utf-8"))
        expected = json.loads((case_dir / "expected.json").read_text(encoding="utf-8"))
        for error in check_hypothesis(hypothesis) + check_expected(expected, reference):
            errors.append(f"{case_dir.name}: {error}")
        if expected["granularity"] != case["expectedGranularity"]:
            errors.append(f"{case_dir.name}: expected.json disagrees with case.json about granularity")

    for error in errors:
        print(error, file=sys.stderr)
    print(f"checked {len(case_dirs)} alignment fixture cases")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
