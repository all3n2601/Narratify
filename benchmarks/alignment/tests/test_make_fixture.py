"""Tests for the synthetic narration generator.

The generator is the ground truth the alignment gate is measured against, so the properties that
matter are not about speech at all: perturbing a hypothesis must never move the timings of the
words that survived, and the same case must produce the same bytes on every machine. If either
breaks, the gate starts measuring the generator instead of the aligner.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import make_fixture

REFERENCE = "Alpha bravo charlie delta. Echo foxtrot golf hotel. India juliet kilo lima."


class TimelineTest(unittest.TestCase):
    def test_words_are_laid_out_in_order_without_overlapping(self):
        tokens = make_fixture.timeline(["alpha", "bravo", "charlie"], base_ms=120, per_character_ms=45, gap_ms=40)
        self.assertEqual([token["text"] for token in tokens], ["alpha", "bravo", "charlie"])
        for earlier, later in zip(tokens, tokens[1:]):
            self.assertLess(earlier["endMs"], later["startMs"])

    def test_a_longer_word_occupies_more_audio(self):
        tokens = make_fixture.timeline(["it", "extraordinary"], base_ms=120, per_character_ms=45, gap_ms=40)
        self.assertLess(
            tokens[0]["endMs"] - tokens[0]["startMs"],
            tokens[1]["endMs"] - tokens[1]["startMs"],
        )


class PerturbationTest(unittest.TestCase):
    def build(self, **overrides):
        case = {
            "reference": "reference.txt",
            "spoken": "reference.txt",
            "expectedGranularity": "WORD",
            "seed": 1,
            "baseMs": 120,
            "perCharacterMs": 45,
            "gapMs": 40,
            "preamble": [],
            "dropRate": 0.0,
            "substituteRate": 0.0,
        }
        case.update(overrides)
        return make_fixture.build(case, reference=REFERENCE, spoken=REFERENCE)

    def test_a_clean_case_transcribes_every_word(self):
        hypothesis, _ = self.build()
        self.assertEqual(len(hypothesis), len(make_fixture.words(REFERENCE)))

    def test_dropping_words_leaves_the_survivors_at_their_true_times(self):
        clean, _ = self.build()
        dropped, _ = self.build(dropRate=0.3)
        self.assertLess(len(dropped), len(clean))
        clean_by_start = {token["startMs"]: token["text"] for token in clean}
        for token in dropped:
            self.assertEqual(clean_by_start[token["startMs"]], token["text"])

    def test_substitution_keeps_the_slot_and_changes_the_word(self):
        clean, _ = self.build()
        substituted, _ = self.build(substituteRate=1.0)
        self.assertEqual(len(clean), len(substituted))
        self.assertEqual(
            [token["startMs"] for token in clean],
            [token["startMs"] for token in substituted],
        )
        self.assertNotEqual(
            [token["text"] for token in clean],
            [token["text"] for token in substituted],
        )

    def test_a_preamble_delays_every_word_of_the_book(self):
        plain, plain_expected = self.build()
        with_preamble, preamble_expected = self.build(preamble=["this", "is", "a", "recording"])
        self.assertGreater(len(with_preamble), len(plain))
        self.assertGreater(preamble_expected["onsets"][0]["onsetMs"], plain_expected["onsets"][0]["onsetMs"])

    def test_the_same_case_produces_the_same_bytes(self):
        first, first_expected = self.build(dropRate=0.2, substituteRate=0.2)
        second, second_expected = self.build(dropRate=0.2, substituteRate=0.2)
        self.assertEqual(first, second)
        self.assertEqual(first_expected, second_expected)


class GroundTruthTest(unittest.TestCase):
    def test_one_onset_is_recorded_per_sentence(self):
        _, expected = make_fixture.build(
            {
                "reference": "reference.txt",
                "spoken": "reference.txt",
                "expectedGranularity": "WORD",
                "seed": 1,
                "baseMs": 120,
                "perCharacterMs": 45,
                "gapMs": 40,
                "preamble": [],
                "dropRate": 0.0,
                "substituteRate": 0.0,
            },
            reference=REFERENCE,
            spoken=REFERENCE,
        )
        self.assertEqual(len(expected["onsets"]), 3)
        self.assertEqual(expected["onsets"][0]["onsetMs"], 0)

    def test_a_narration_of_another_text_records_no_onsets(self):
        _, expected = make_fixture.build(
            {
                "reference": "reference.txt",
                "spoken": "other.txt",
                "expectedGranularity": "NONE",
                "seed": 1,
                "baseMs": 120,
                "perCharacterMs": 45,
                "gapMs": 40,
                "preamble": [],
                "dropRate": 0.0,
                "substituteRate": 0.0,
            },
            reference=REFERENCE,
            spoken="Xylem zephyr quokka nimbus. Fjord gazebo halcyon ibex.",
        )
        self.assertEqual(expected["onsets"], [])
        self.assertEqual(expected["granularity"], "NONE")


if __name__ == "__main__":
    unittest.main()
