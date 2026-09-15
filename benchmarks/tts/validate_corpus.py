#!/usr/bin/env python3
"""Validate the machine-readable Narratify TTS fixture corpus."""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "test-fixtures" / "tts" / "corpus.jsonl"
REQUIRED = {"id", "category", "language", "tags", "text"}


def main() -> int:
    errors: list[str] = []
    rows: list[dict] = []
    ids: set[str] = set()
    for number, raw in enumerate(CORPUS.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        try:
            row = json.loads(raw)
        except json.JSONDecodeError as exc:
            errors.append(f"line {number}: invalid JSON: {exc}")
            continue
        missing = REQUIRED - row.keys()
        if missing:
            errors.append(f"line {number}: missing {sorted(missing)}")
        passage_id = row.get("id")
        if not isinstance(passage_id, str) or not passage_id:
            errors.append(f"line {number}: id must be a non-empty string")
        elif passage_id in ids:
            errors.append(f"line {number}: duplicate id {passage_id}")
        else:
            ids.add(passage_id)
        if not isinstance(row.get("text"), str) or not row.get("text", "").strip():
            errors.append(f"line {number}: text must be non-empty")
        if not isinstance(row.get("tags"), list) or not row.get("tags"):
            errors.append(f"line {number}: tags must be a non-empty array")
        if not isinstance(row.get("language"), str) or "-" not in row.get("language", ""):
            errors.append(f"line {number}: language must be a BCP-47 language-region tag")
        rows.append(row)

    counts = Counter(row.get("category") for row in rows)
    if len(counts) < 12:
        errors.append(f"expected at least 12 categories, found {len(counts)}")
    sparse = sorted(str(category) for category, count in counts.items() if count < 3)
    if sparse:
        errors.append(f"categories need at least 3 passages: {', '.join(sparse)}")

    if errors:
        print("Corpus validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"Corpus valid: {len(rows)} passages across {len(counts)} categories")
    if len(rows) >= 100:
        print("Corpus-size gate passed; listening review and device measurements remain separate gates.")
    else:
        print(f"Corpus-size gate incomplete: {len(rows)}/100 reviewed passages")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
