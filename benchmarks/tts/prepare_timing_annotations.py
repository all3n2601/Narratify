#!/usr/bin/env python3
"""Create deterministic pending timing jobs; never fabricates annotations."""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=ROOT / "test-fixtures" / "tts" / "corpus.jsonl")
    parser.add_argument("--output", type=Path, default=ROOT / "test-fixtures" / "tts" / "timing-annotations.pending.jsonl")
    parser.add_argument("--count", type=int, default=30)
    parser.add_argument("--seed", type=int, default=2601)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.corpus.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not 1 <= args.count <= len(rows):
        raise SystemExit(f"count must be between 1 and {len(rows)}")
    random.Random(args.seed).shuffle(rows)
    jobs = []
    for row in rows[: args.count]:
        jobs.append({
            "schema_version": 1, "passage_id": row["id"], "status": "pending",
            "reference_audio": {"path": None, "sha256": None, "sample_rate_hz": None, "channels": None},
            "annotator": {"id": None, "annotated_at_utc": None, "reviewer_id": None, "reviewed_at_utc": None},
            "words": [], "notes": "Record and annotate audible word onsets; do not use model-predicted timings as reference."
        })
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(json.dumps(job, ensure_ascii=False) + "\n" for job in jobs), encoding="utf-8")
    print(f"Wrote {len(jobs)} pending annotation jobs to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
