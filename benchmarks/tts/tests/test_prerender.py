"""Tests for whole-book pre-generation planning.

Pre-generating a book is a product decision taken deliberately against the
original stop rule, so the three risks that rule named have to be handled in
code rather than avoided: storage, wall-clock cost, and invalidation. These
tests pin all three.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import prerender

MODEL = "beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a"


class CacheKeyTest(unittest.TestCase):
    """A rendered chunk is only reusable for the exact settings that made it."""

    def key(self, **overrides):
        arguments = {
            "model_sha256": MODEL,
            "voice_id": "af_heart",
            "speed": 1.0,
            "chunk_text": "The lantern went out.",
        }
        arguments.update(overrides)
        return prerender.cache_key(**arguments)

    def test_same_inputs_produce_the_same_key(self) -> None:
        self.assertEqual(self.key(), self.key())

    def test_a_different_voice_produces_a_different_key(self) -> None:
        self.assertNotEqual(self.key(), self.key(voice_id="am_michael"))

    def test_a_different_speed_produces_a_different_key(self) -> None:
        self.assertNotEqual(self.key(), self.key(speed=1.25))

    def test_a_different_model_build_produces_a_different_key(self) -> None:
        self.assertNotEqual(self.key(), self.key(model_sha256="0" * 64))

    def test_different_text_produces_a_different_key(self) -> None:
        self.assertNotEqual(self.key(), self.key(chunk_text="The lantern went out!"))

    def test_the_key_is_filesystem_safe(self) -> None:
        key = self.key()

        self.assertTrue(key.isalnum(), key)
        self.assertLessEqual(len(key), 64)


class PlanPrerenderTest(unittest.TestCase):
    CHUNKS = ["First chunk of the book." * 4, "Second chunk here." * 4]

    def plan(self, **overrides):
        arguments = {
            "chunks": self.CHUNKS,
            "model_sha256": MODEL,
            "voice_id": "af_heart",
            "speed": 1.0,
            "rtf": 0.5,
            "codec": "opus24",
            "rendered_keys": frozenset(),
        }
        arguments.update(overrides)
        return prerender.plan_prerender(**arguments)

    def test_every_chunk_is_pending_when_nothing_is_cached(self) -> None:
        plan = self.plan()

        self.assertEqual(plan["total_chunks"], 2)
        self.assertEqual(plan["pending_chunks"], 2)

    def test_already_rendered_chunks_are_skipped_so_a_job_can_resume(self) -> None:
        first = prerender.cache_key(MODEL, "af_heart", 1.0, self.CHUNKS[0])

        plan = self.plan(rendered_keys=frozenset({first}))

        self.assertEqual(plan["pending_chunks"], 1)
        self.assertEqual([job["chunk_index"] for job in plan["jobs"]], [1])

    def test_a_fully_cached_book_costs_nothing_more(self) -> None:
        keys = frozenset(
            prerender.cache_key(MODEL, "af_heart", 1.0, chunk) for chunk in self.CHUNKS
        )

        plan = self.plan(rendered_keys=keys)

        self.assertEqual(plan["pending_chunks"], 0)
        self.assertEqual(plan["estimated_compute_seconds"], 0.0)
        self.assertEqual(plan["jobs"], [])

    def test_compute_estimate_scales_with_the_real_time_factor(self) -> None:
        slow = self.plan(rtf=1.0)["estimated_compute_seconds"]
        fast = self.plan(rtf=0.5)["estimated_compute_seconds"]

        self.assertAlmostEqual(slow, fast * 2, places=3)

    def test_storage_estimate_reflects_the_chosen_codec(self) -> None:
        raw = self.plan(codec="pcm16")["estimated_bytes"]
        compressed = self.plan(codec="opus24")["estimated_bytes"]

        self.assertGreater(raw, compressed * 10)

    def test_an_unknown_codec_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            self.plan(codec="mp3-guess")

    def test_a_negative_or_zero_real_time_factor_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            self.plan(rtf=0.0)

    def test_estimated_audio_matches_the_measured_characters_per_second(self) -> None:
        # 0.0704 s of audio per character, measured from the Kokoro desktop trace.
        characters = sum(len(chunk) for chunk in self.CHUNKS)

        plan = self.plan()

        self.assertAlmostEqual(
            plan["estimated_audio_seconds"],
            characters * prerender.SECONDS_PER_CHARACTER,
            places=3,
        )


class BudgetTest(unittest.TestCase):
    """The stop rule this feature overrides was about cost, so cost is a gate."""

    def test_a_job_over_the_storage_budget_is_reported_as_over_budget(self) -> None:
        book = ["word " * 200] * 400

        plan = prerender.plan_prerender(
            book, MODEL, "af_heart", 1.0, rtf=0.5, codec="pcm16",
            rendered_keys=frozenset(), storage_budget_bytes=10 * 1024 * 1024,
        )

        self.assertTrue(plan["over_storage_budget"])

    def test_a_job_within_the_storage_budget_is_not_flagged(self) -> None:
        plan = prerender.plan_prerender(
            ["short chunk"], MODEL, "af_heart", 1.0, rtf=0.5, codec="opus24",
            rendered_keys=frozenset(), storage_budget_bytes=10 * 1024 * 1024,
        )

        self.assertFalse(plan["over_storage_budget"])


if __name__ == "__main__":
    unittest.main()
