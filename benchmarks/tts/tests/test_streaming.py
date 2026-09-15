"""Tests for the B4 bounded sentence-streaming model.

Every latency number in section 1 of the execution plan assumes B4 exists, and
nothing measured so far reflects it: the recorded runs are full-utterance, with
warm first audio between 1440 ms and 3836 ms against a 900 ms gate. This module
models the producer/queue/playback relationship so a real chunk trace can be
scored against that gate.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import streaming


class ChunkTextTest(unittest.TestCase):
    """Mirrors the shared:text Kotlin defaults: merge < 45, target 220, max 350."""

    def test_single_short_sentence_is_one_chunk(self) -> None:
        chunks = streaming.chunk_text("The lantern went out.")

        self.assertEqual(chunks, ["The lantern went out."])

    def test_splits_on_sentence_boundaries(self) -> None:
        text = (
            "She counted the coins twice before the door opened again. "
            "The hallway smelled of rain and old paper, and nobody spoke."
        )

        chunks = streaming.chunk_text(text)

        self.assertEqual(len(chunks), 2)
        self.assertTrue(chunks[0].endswith("again."))

    def test_merges_sentences_shorter_than_the_merge_floor(self) -> None:
        # Two clipped sentences would sound choppy synthesized alone.
        chunks = streaming.chunk_text("He left. She stayed.")

        self.assertEqual(chunks, ["He left. She stayed."])

    def test_splits_a_sentence_longer_than_the_maximum(self) -> None:
        text = "word " * 200

        chunks = streaming.chunk_text(text)

        self.assertGreater(len(chunks), 1)
        for chunk in chunks:
            self.assertLessEqual(len(chunk), streaming.MAXIMUM_CHUNK_CHARACTERS)

    def test_every_chunk_preserves_the_source_text(self) -> None:
        text = (
            "The first sentence runs on for a while so it clears the merge floor. "
            "A second one follows it here. And a third arrives at the end."
        )

        chunks = streaming.chunk_text(text)

        self.assertEqual(" ".join(chunks).split(), text.split())


class FirstChunkTest(unittest.TestCase):
    """Measured on an M4 Pro: full-size opening chunk misses the 900 ms gate at
    933 ms, a 56-character opening chunk reaches first audio in 678 ms."""

    TEXT = (
        "She counted the coins twice before the door opened again and the "
        "hallway filled with the smell of rain. Nobody spoke for a while."
    )

    def test_opening_chunk_is_capped_when_a_first_chunk_budget_is_given(self) -> None:
        chunks = streaming.chunk_text(self.TEXT, first_chunk_characters=60)

        self.assertLessEqual(len(chunks[0]), 60)

    def test_capping_the_opening_chunk_splits_on_a_word_boundary(self) -> None:
        chunks = streaming.chunk_text(self.TEXT, first_chunk_characters=60)

        self.assertFalse(chunks[0].endswith(" "))
        self.assertTrue(self.TEXT.startswith(chunks[0]))

    def test_capping_the_opening_chunk_loses_no_text(self) -> None:
        chunks = streaming.chunk_text(self.TEXT, first_chunk_characters=60)

        self.assertEqual(" ".join(chunks).split(), self.TEXT.split())

    def test_only_the_opening_chunk_is_shortened(self) -> None:
        capped = streaming.chunk_text(self.TEXT, first_chunk_characters=60)
        plain = streaming.chunk_text(self.TEXT)

        self.assertEqual(capped[2:], plain[1:])

    def test_no_budget_leaves_chunking_unchanged(self) -> None:
        self.assertEqual(
            streaming.chunk_text(self.TEXT, first_chunk_characters=None),
            streaming.chunk_text(self.TEXT),
        )


class SimulateStreamTest(unittest.TestCase):
    @staticmethod
    def chunks(pairs):
        return [{"synthesis_ms": s, "audio_ms": a} for s, a in pairs]

    def test_first_audio_is_the_first_chunk_synthesis_time(self) -> None:
        result = streaming.simulate_stream(
            self.chunks([(300, 4000), (300, 4000)]), lookahead_chunks=2
        )

        self.assertAlmostEqual(result["first_audio_ms"], 300.0)

    def test_producer_faster_than_realtime_never_underruns(self) -> None:
        result = streaming.simulate_stream(
            self.chunks([(200, 3000)] * 8), lookahead_chunks=2
        )

        self.assertEqual(result["underruns"], 0)
        self.assertAlmostEqual(result["underrun_ms"], 0.0)

    def test_producer_slower_than_realtime_underruns_with_measured_gaps(self) -> None:
        # Each chunk takes 5s to make but only plays for 1s.
        result = streaming.simulate_stream(
            self.chunks([(5000, 1000)] * 4), lookahead_chunks=2
        )

        self.assertEqual(result["underruns"], 3)
        self.assertGreater(result["underrun_ms"], 0.0)

    def test_queue_never_exceeds_the_lookahead_bound(self) -> None:
        # A very fast producer must be held back by the bound, not run away.
        result = streaming.simulate_stream(
            self.chunks([(10, 5000)] * 20), lookahead_chunks=3
        )

        self.assertLessEqual(result["peak_queued_chunks"], 3)

    def test_preparing_ahead_removes_the_first_audio_wait(self) -> None:
        # The producer got a head start before the listener pressed play.
        result = streaming.simulate_stream(
            self.chunks([(300, 4000)] * 6), lookahead_chunks=3, prepare_ms=2000
        )

        self.assertAlmostEqual(result["first_audio_ms"], 0.0)

    def test_a_head_start_shorter_than_the_first_chunk_still_waits(self) -> None:
        result = streaming.simulate_stream(
            self.chunks([(1000, 4000)] * 4), lookahead_chunks=3, prepare_ms=400
        )

        self.assertAlmostEqual(result["first_audio_ms"], 600.0)

    def test_a_head_start_only_delays_the_underrun_when_slower_than_realtime(self) -> None:
        # RTF 2.0: every second of audio costs two seconds to make. A lead buffer
        # buys listening time, it cannot make the producer keep up.
        slow = self.chunks([(2000, 1000)] * 12)

        without = streaming.simulate_stream(slow, lookahead_chunks=4, prepare_ms=0)
        with_lead = streaming.simulate_stream(slow, lookahead_chunks=4, prepare_ms=8000)

        self.assertGreater(without["underruns"], 0)
        self.assertGreater(with_lead["underruns"], 0)
        self.assertLess(with_lead["underruns"], without["underruns"])

    def test_an_empty_trace_reports_no_first_audio(self) -> None:
        result = streaming.simulate_stream([], lookahead_chunks=2)

        self.assertIsNone(result["first_audio_ms"])


if __name__ == "__main__":
    unittest.main()
