"""Tests for the fixture validator.

The generated files are checked in, so nothing stops someone hand-editing one. These are the
checks that catch it before the Kotlin gate starts failing for reasons that have nothing to do
with alignment.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import validate_fixture

REFERENCE = "Alpha bravo charlie delta. Echo foxtrot golf hotel."


class ValidateHypothesisTest(unittest.TestCase):
    def test_a_well_formed_hypothesis_passes(self):
        hypothesis = [
            {"text": "alpha", "startMs": 0, "endMs": 300, "confidence": 1.0},
            {"text": "bravo", "startMs": 340, "endMs": 640, "confidence": 1.0},
        ]
        self.assertEqual([], validate_fixture.check_hypothesis(hypothesis))

    def test_time_going_backwards_is_reported(self):
        hypothesis = [
            {"text": "alpha", "startMs": 900, "endMs": 1200, "confidence": 1.0},
            {"text": "bravo", "startMs": 100, "endMs": 400, "confidence": 1.0},
        ]
        self.assertEqual(1, len(validate_fixture.check_hypothesis(hypothesis)))

    def test_a_token_ending_before_it_starts_is_reported(self):
        hypothesis = [{"text": "alpha", "startMs": 900, "endMs": 400, "confidence": 1.0}]
        self.assertEqual(1, len(validate_fixture.check_hypothesis(hypothesis)))

    def test_a_blank_token_is_reported(self):
        hypothesis = [{"text": "  ", "startMs": 0, "endMs": 400, "confidence": 1.0}]
        self.assertEqual(1, len(validate_fixture.check_hypothesis(hypothesis)))


class ValidateExpectationTest(unittest.TestCase):
    def test_a_quote_present_in_the_reference_passes(self):
        expected = {"granularity": "WORD", "onsets": [{"quote": "Echo foxtrot golf", "onsetMs": 1200}]}
        self.assertEqual([], validate_fixture.check_expected(expected, REFERENCE))

    def test_a_quote_absent_from_the_reference_is_reported(self):
        expected = {"granularity": "WORD", "onsets": [{"quote": "kilo lima mike", "onsetMs": 1200}]}
        self.assertEqual(1, len(validate_fixture.check_expected(expected, REFERENCE)))

    def test_an_unknown_granularity_is_reported(self):
        expected = {"granularity": "PARAGRAPH", "onsets": []}
        self.assertEqual(1, len(validate_fixture.check_expected(expected, REFERENCE)))


if __name__ == "__main__":
    unittest.main()
