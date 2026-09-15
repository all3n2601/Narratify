#!/usr/bin/env python3
"""Validate timing jobs and optionally require reviewed ground truth."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("annotations", type=Path)
    parser.add_argument("--corpus", type=Path, default=ROOT / "test-fixtures" / "tts" / "corpus.jsonl")
    parser.add_argument("--require-reviewed", action="store_true")
    args = parser.parse_args()
    corpus = {row["id"]: row for row in (json.loads(line) for line in args.corpus.read_text(encoding="utf-8").splitlines() if line.strip())}
    errors: list[str] = []
    reviewed = 0
    seen: set[str] = set()
    annotations = [json.loads(line) for line in args.annotations.read_text(encoding="utf-8").splitlines() if line.strip()]
    for line_number, item in enumerate(annotations, 1):
        passage_id = item.get("passage_id")
        if passage_id not in corpus:
            errors.append(f"line {line_number}: unknown passage_id {passage_id}")
            continue
        if passage_id in seen:
            errors.append(f"line {line_number}: duplicate passage_id {passage_id}")
        seen.add(passage_id)
        status = item.get("status")
        if status not in {"pending", "annotated", "reviewed", "rejected"}:
            errors.append(f"line {line_number}: invalid status {status}")
        if status == "reviewed":
            reviewed += 1
            audio = item.get("reference_audio", {})
            path = audio.get("path")
            if not path:
                errors.append(f"line {line_number}: reviewed item has no reference audio")
            else:
                audio_path = args.annotations.parent / path
                if not audio_path.is_file():
                    errors.append(f"line {line_number}: missing reference audio {audio_path}")
                elif hashlib.sha256(audio_path.read_bytes()).hexdigest() != audio.get("sha256"):
                    errors.append(f"line {line_number}: reference audio checksum mismatch")
            if not item.get("annotator", {}).get("reviewer_id") or not item.get("annotator", {}).get("reviewed_at_utc"):
                errors.append(f"line {line_number}: reviewed item lacks independent review metadata")
            words = item.get("words", [])
            if not words:
                errors.append(f"line {line_number}: reviewed item has no word onsets")
            previous = -1
            text = corpus[passage_id]["text"]
            for word in words:
                start, end, onset = word.get("text_start"), word.get("text_end"), word.get("onset_sample")
                if not all(isinstance(value, int) for value in (start, end, onset)) or not (0 <= start < end <= len(text)):
                    errors.append(f"line {line_number}: invalid word range")
                    break
                if text[start:end] != word.get("text") or onset <= previous:
                    errors.append(f"line {line_number}: word text mismatch or non-increasing onset")
                    break
                previous = onset
    if args.require_reviewed and reviewed != len(annotations):
        errors.append(f"decision-ready timing set requires every item reviewed; {reviewed}/{len(annotations)} reviewed")
    if errors:
        print("Timing annotation validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"Timing annotations valid: {len(annotations)} jobs, {reviewed} reviewed")
    if reviewed == 0:
        print("Timing accuracy gate remains incomplete; no manual reference has been claimed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
