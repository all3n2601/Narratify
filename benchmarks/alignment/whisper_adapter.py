#!/usr/bin/env python3
"""Convert whisper.cpp full-JSON output into an alignment hypothesis.

Desktop evidence tooling. No model weights and no binary are checked in, and nothing here is a
statement about how the app will transcribe audio on a device — that carries its own licensing,
distribution, and thermal gates and has not been decided.

Prepare audio and transcribe first:

    ffmpeg -i chapter-03.m4b -ar 16000 -ac 1 -c:a pcm_s16le chapter-03.wav
    whisper-cli -m ggml-base.en.bin -f chapter-03.wav --output-json-full --output-file chapter-03

Then convert:

    python3 benchmarks/alignment/whisper_adapter.py chapter-03.json --output hypothesis.json
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def _is_word_character(value: str) -> bool:
    return any(character.isalnum() for character in value)


def convert(payload: dict) -> list[dict]:
    """Merge whisper's byte-pair tokens into words carrying the audio span of all their pieces."""
    words: list[dict] = []
    for segment in payload.get("transcription", []):
        for token in segment.get("tokens", []):
            text = token.get("text", "")
            # Specials such as [_BEG_] and <|endoftext|> carry no audio and no word.
            if text.startswith("[_") or text.startswith("<|"):
                continue
            starts_word = text.startswith(" ") or not words
            stripped = text.strip()
            if not _is_word_character(stripped):
                continue
            offsets = token.get("offsets", {})
            start, end = int(offsets.get("from", 0)), int(offsets.get("to", 0))
            probability = float(token.get("p", 1.0))
            if starts_word:
                words.append(
                    {"text": stripped, "startMs": start, "endMs": end, "confidence": probability}
                )
            else:
                current = words[-1]
                current["text"] += stripped
                current["endMs"] = max(current["endMs"], end)
                current["confidence"] = min(current["confidence"], probability)
    return words


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="whisper.cpp --output-json-full file")
    parser.add_argument("--output", type=Path, required=True, help="hypothesis.json to write")
    arguments = parser.parse_args()

    hypothesis = convert(json.loads(arguments.input.read_text(encoding="utf-8")))
    arguments.output.write_text(json.dumps(hypothesis, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{arguments.output}: {len(hypothesis)} words")
    return 0


if __name__ == "__main__":
    sys.exit(main())
