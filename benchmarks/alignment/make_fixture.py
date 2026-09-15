#!/usr/bin/env python3
"""Generate a deterministic synthetic narration for one alignment fixture case.

This produces the hypothesis the alignment gate runs against and the ground-truth sentence onsets
it is scored on. It is not a speech simulator: durations are a linear function of word length and
every perturbation is drawn from a seeded generator, because the gate has to give the same answer
on every machine and every run. Perturbations only ever delete a word or replace its text; they
never move a surviving word, so the recorded onsets stay true no matter how damaged the
hypothesis is.
"""

from __future__ import annotations

import argparse
import json
import random
import re
import sys
from pathlib import Path

WORD = re.compile(r"[0-9A-Za-zÀ-ɏ'’]+")
SENTENCE = re.compile(r"(?<=[.!?])\s+")
QUOTE_WORDS = 6
CONFUSIONS = ["and", "the", "then", "her", "his", "that", "when", "uh", "a", "in"]


def words(text: str) -> list[str]:
    return WORD.findall(text)


def sentences(text: str) -> list[str]:
    return [part for part in SENTENCE.split(text.strip()) if part.strip()]


def timeline(tokens: list[str], base_ms: int, per_character_ms: int, gap_ms: int) -> list[dict]:
    cursor = 0
    result = []
    for token in tokens:
        duration = base_ms + per_character_ms * len(token)
        result.append({"text": token, "startMs": cursor, "endMs": cursor + duration, "confidence": 1.0})
        cursor += duration + gap_ms
    return result


def build(case: dict, reference: str, spoken: str) -> tuple[list[dict], dict]:
    """Return the perturbed hypothesis and the ground truth it should be scored against."""
    preamble = list(case["preamble"])
    spoken_words = words(spoken)
    tokens = timeline(
        preamble + spoken_words,
        base_ms=case["baseMs"],
        per_character_ms=case["perCharacterMs"],
        gap_ms=case["gapMs"],
    )
    book_tokens = tokens[len(preamble):]

    # Ground truth is read off the clean timeline, before anything is damaged, and only when the
    # narration is of this text. A narration of something else has no true onsets in this book.
    onsets = []
    if case["spoken"] == case["reference"]:
        cursor = 0
        for sentence in sentences(reference):
            sentence_words = words(sentence)
            if not sentence_words:
                continue
            onsets.append(
                {
                    "quote": " ".join(sentence_words[:QUOTE_WORDS]),
                    "onsetMs": book_tokens[cursor]["startMs"],
                }
            )
            cursor += len(sentence_words)

    generator = random.Random(case["seed"])
    hypothesis = []
    for token in tokens:
        if generator.random() < case["dropRate"]:
            continue
        if generator.random() < case["substituteRate"]:
            replacement = CONFUSIONS[generator.randrange(len(CONFUSIONS))]
            token = dict(token, text=replacement, confidence=0.4)
        hypothesis.append(token)

    return hypothesis, {"granularity": case["expectedGranularity"], "onsets": onsets}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case_dir", type=Path, help="fixture case directory holding case.json")
    arguments = parser.parse_args()

    case_dir: Path = arguments.case_dir
    case = json.loads((case_dir / "case.json").read_text(encoding="utf-8"))
    reference = (case_dir / case["reference"]).read_text(encoding="utf-8")
    spoken = (case_dir / case["spoken"]).read_text(encoding="utf-8")

    hypothesis, expected = build(case, reference=reference, spoken=spoken)
    (case_dir / "hypothesis.json").write_text(
        json.dumps(hypothesis, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (case_dir / "expected.json").write_text(
        json.dumps(expected, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"{case_dir.name}: {len(hypothesis)} tokens, {len(expected['onsets'])} onsets")
    return 0


if __name__ == "__main__":
    sys.exit(main())
