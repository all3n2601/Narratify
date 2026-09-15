"""Tests for the whisper.cpp output converter.

whisper.cpp emits byte-pair tokens, not words: "harbour" can arrive as " har" + "bour", and
timestamps and specials are interleaved with them. Getting the word boundaries wrong here would
be invisible in the output and would show up as an alignment quality problem, so the merge rules
are pinned rather than eyeballed.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import whisper_adapter


def token(text, start, end, probability=0.9):
    return {"text": text, "offsets": {"from": start, "to": end}, "p": probability}


class ConvertTest(unittest.TestCase):
    def test_leading_space_starts_a_new_word(self):
        result = whisper_adapter.convert(
            {"transcription": [{"tokens": [token(" the", 0, 300), token(" lantern", 340, 900)]}]}
        )
        self.assertEqual([entry["text"] for entry in result], ["the", "lantern"])

    def test_subword_pieces_are_merged_into_one_word(self):
        result = whisper_adapter.convert(
            {"transcription": [{"tokens": [token(" har", 0, 200), token("bour", 200, 420)]}]}
        )
        self.assertEqual([entry["text"] for entry in result], ["harbour"])
        self.assertEqual(result[0]["startMs"], 0)
        self.assertEqual(result[0]["endMs"], 420)

    def test_special_tokens_are_dropped(self):
        result = whisper_adapter.convert(
            {"transcription": [{"tokens": [token("[_BEG_]", 0, 0), token(" the", 0, 300)]}]}
        )
        self.assertEqual([entry["text"] for entry in result], ["the"])

    def test_punctuation_only_pieces_do_not_become_words(self):
        result = whisper_adapter.convert(
            {"transcription": [{"tokens": [token(" the", 0, 300), token(".", 300, 320)]}]}
        )
        self.assertEqual([entry["text"] for entry in result], ["the"])

    def test_confidence_of_a_merged_word_is_its_weakest_piece(self):
        result = whisper_adapter.convert(
            {"transcription": [{"tokens": [token(" har", 0, 200, 0.9), token("bour", 200, 420, 0.4)]}]}
        )
        self.assertAlmostEqual(result[0]["confidence"], 0.4)

    def test_segments_are_concatenated_in_order(self):
        result = whisper_adapter.convert(
            {
                "transcription": [
                    {"tokens": [token(" alpha", 0, 300)]},
                    {"tokens": [token(" bravo", 400, 700)]},
                ]
            }
        )
        self.assertEqual([entry["startMs"] for entry in result], [0, 400])


if __name__ == "__main__":
    unittest.main()
