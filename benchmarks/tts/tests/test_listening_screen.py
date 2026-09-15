"""Tests for the P1 early listening screen harness.

P1 is the go/no-go screen in docs/NEURAL_TTS_EXECUTION_PLAN.md. Its acceptance
rule is deliberately harsh: Kokoro must clearly beat every OS voice, and a tie
fails the gate.
"""

from __future__ import annotations

import array
import json
import random
import math
import sys
import tempfile
import unittest
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import listening_screen


class ScreenVerdictTest(unittest.TestCase):
    def test_kokoro_ahead_of_every_os_voice_by_the_margin_passes(self) -> None:
        key = {
            "a1": {"passage_id": "p1", "system": "kokoro"},
            "a2": {"passage_id": "p1", "system": "apple-premium"},
            "a3": {"passage_id": "p1", "system": "google-neural"},
        }
        scores = [
            {"anonymous_id": "a1", "listener": "L1", "score": 4.0},
            {"anonymous_id": "a2", "listener": "L1", "score": 3.0},
            {"anonymous_id": "a3", "listener": "L1", "score": 3.0},
        ]

        verdict = listening_screen.screen_verdict(scores, key, margin=0.5)

        self.assertEqual(verdict["status"], "pass")
        self.assertAlmostEqual(verdict["systems"]["kokoro"], 4.0)

    def test_a_tie_against_an_os_voice_fails_the_gate(self) -> None:
        key = {
            "a1": {"passage_id": "p1", "system": "kokoro"},
            "a2": {"passage_id": "p1", "system": "apple-premium"},
        }
        scores = [
            {"anonymous_id": "a1", "listener": "L1", "score": 3.5},
            {"anonymous_id": "a2", "listener": "L1", "score": 3.5},
        ]

        verdict = listening_screen.screen_verdict(scores, key, margin=0.5)

        self.assertEqual(verdict["status"], "fail")
        self.assertIn("apple-premium", verdict["reason"])


class NeuralVoiceFamilyTest(unittest.TestCase):
    """P1 rates several Kokoro voices at once; the best one faces the gate."""

    KEY = {
        "a1": {"passage_id": "p1", "system": "kokoro-af-heart"},
        "a2": {"passage_id": "p1", "system": "kokoro-am-michael"},
        "a3": {"passage_id": "p1", "system": "apple-ava-premium"},
    }

    def test_best_kokoro_voice_carries_the_gate(self) -> None:
        scores = [
            {"anonymous_id": "a1", "listener": "L1", "score": 4.2},
            {"anonymous_id": "a2", "listener": "L1", "score": 3.0},
            {"anonymous_id": "a3", "listener": "L1", "score": 3.5},
        ]

        verdict = listening_screen.screen_verdict(scores, self.KEY, margin=0.5)

        self.assertEqual(verdict["status"], "pass")
        self.assertEqual(verdict["selected_neural_system"], "kokoro-af-heart")

    def test_a_weak_best_voice_still_fails_even_with_many_voices(self) -> None:
        scores = [
            {"anonymous_id": "a1", "listener": "L1", "score": 3.7},
            {"anonymous_id": "a2", "listener": "L1", "score": 3.0},
            {"anonymous_id": "a3", "listener": "L1", "score": 3.5},
        ]

        verdict = listening_screen.screen_verdict(scores, self.KEY, margin=0.5)

        self.assertEqual(verdict["status"], "fail")
        self.assertEqual(verdict["selected_neural_system"], "kokoro-af-heart")

    def test_a_losing_kokoro_voice_is_not_compared_against_its_siblings(self) -> None:
        # kokoro-am-michael trails af-heart badly, but sibling voices are not
        # OS baselines and must never appear as a shortfall.
        scores = [
            {"anonymous_id": "a1", "listener": "L1", "score": 4.5},
            {"anonymous_id": "a2", "listener": "L1", "score": 1.0},
            {"anonymous_id": "a3", "listener": "L1", "score": 3.5},
        ]

        verdict = listening_screen.screen_verdict(scores, self.KEY, margin=0.5)

        self.assertEqual(verdict["status"], "pass")
        self.assertNotIn("kokoro-am-michael", verdict["reason"])


class BlindSetTest(unittest.TestCase):
    PASSAGES = [
        {"id": "dialogue-001", "text": "First passage."},
        {"id": "long-sentence-007", "text": "Second passage."},
    ]
    SYSTEMS = ["kokoro", "apple-premium", "google-neural"]

    def test_every_passage_and_system_pair_appears_exactly_once(self) -> None:
        manifest, key = listening_screen.build_blind_set(
            self.PASSAGES, self.SYSTEMS, seed=7
        )

        pairs = sorted(
            (entry["passage_id"], entry["system"]) for entry in key.values()
        )
        expected = sorted(
            (passage["id"], system)
            for passage in self.PASSAGES
            for system in self.SYSTEMS
        )
        self.assertEqual(pairs, expected)
        self.assertEqual(len(manifest), len(expected))

    def test_manifest_never_reveals_which_system_rendered_a_clip(self) -> None:
        manifest, _ = listening_screen.build_blind_set(
            self.PASSAGES, self.SYSTEMS, seed=7
        )

        exposed = json.dumps(manifest)
        for system in self.SYSTEMS:
            self.assertNotIn(system, exposed)

    def test_same_seed_produces_the_same_assignment(self) -> None:
        first, first_key = listening_screen.build_blind_set(
            self.PASSAGES, self.SYSTEMS, seed=7
        )
        second, second_key = listening_screen.build_blind_set(
            self.PASSAGES, self.SYSTEMS, seed=7
        )

        self.assertEqual(first, second)
        self.assertEqual(first_key, second_key)

    def test_clip_order_within_a_passage_is_shuffled_not_system_order(self) -> None:
        # With a fixed seed the ordering must not simply mirror SYSTEMS order,
        # otherwise a listener learns the mapping after one passage.
        orderings = set()
        for seed in range(12):
            _, key = listening_screen.build_blind_set(
                self.PASSAGES, self.SYSTEMS, seed=seed
            )
            for passage in self.PASSAGES:
                ordering = tuple(
                    key[clip]["system"]
                    for clip in sorted(key)
                    if key[clip]["passage_id"] == passage["id"]
                )
                orderings.add(ordering)
        self.assertGreater(len(orderings), 1)


class LoudnessTest(unittest.TestCase):
    """Level differences bias blind listening, so every clip is matched first."""

    @staticmethod
    def sine(amplitude, count=4800):
        return array.array(
            "h",
            [
                int(amplitude * math.sin(2 * math.pi * 220 * n / 24000))
                for n in range(count)
            ],
        )

    def test_quiet_clip_is_boosted_to_the_target_loudness(self) -> None:
        quiet = self.sine(800)

        normalized = listening_screen.normalize_pcm16(quiet, target_dbfs=-20.0)

        self.assertAlmostEqual(
            listening_screen.rms_dbfs(normalized), -20.0, delta=0.2
        )

    def test_loud_clip_is_attenuated_instead_of_clipping(self) -> None:
        loud = self.sine(32000)

        normalized = listening_screen.normalize_pcm16(loud, target_dbfs=-3.0)

        self.assertLessEqual(max(abs(sample) for sample in normalized), 32767)

    def test_gain_is_capped_so_a_near_full_scale_target_never_clips(self) -> None:
        quiet = self.sine(500)

        normalized = listening_screen.normalize_pcm16(quiet, target_dbfs=0.0)

        self.assertLessEqual(max(abs(sample) for sample in normalized), 32767)

    def test_silent_clip_stays_silent_without_dividing_by_zero(self) -> None:
        silence = array.array("h", [0] * 480)

        normalized = listening_screen.normalize_pcm16(silence, target_dbfs=-20.0)

        self.assertEqual(set(normalized), {0})


class PassageSelectionTest(unittest.TestCase):
    ROWS = [
        {
            "id": f"{category}-{index:03d}",
            "category": category,
            "language": language,
            "text": "word " * (10 + index),
        }
        for category in ("dialogue", "long_sentence", "abbreviations")
        for index, language in enumerate(
            ["en-US", "en-GB", "en-US", "en-US", "fr-FR"], start=1
        )
    ]

    def test_returns_the_requested_number_of_passages(self) -> None:
        selected = listening_screen.select_passages(self.ROWS, count=6, seed=3)

        self.assertEqual(len(selected), 6)

    def test_skips_passages_that_are_not_english(self) -> None:
        selected = listening_screen.select_passages(self.ROWS, count=6, seed=3)

        for passage in selected:
            self.assertTrue(passage["language"].lower().startswith("en"))

    def test_spreads_the_selection_across_every_eligible_category(self) -> None:
        selected = listening_screen.select_passages(self.ROWS, count=6, seed=3)

        categories = {passage["category"] for passage in selected}
        self.assertEqual(categories, {"dialogue", "long_sentence", "abbreviations"})

    def test_same_seed_selects_the_same_passages(self) -> None:
        first = listening_screen.select_passages(self.ROWS, count=6, seed=3)
        second = listening_screen.select_passages(self.ROWS, count=6, seed=3)

        self.assertEqual(first, second)

    def test_requesting_more_than_the_corpus_holds_is_an_error(self) -> None:
        with self.assertRaises(ValueError):
            listening_screen.select_passages(self.ROWS, count=99, seed=3)


class AssembleBlindSetTest(unittest.TestCase):
    PASSAGES = [
        {"id": "dialogue-001", "category": "dialogue", "language": "en-US",
         "text": "First passage."},
        {"id": "long-sentence-007", "category": "long_sentence",
         "language": "en-US", "text": "Second passage."},
    ]

    def write_wav(self, path, amplitude):
        path.parent.mkdir(parents=True, exist_ok=True)
        samples = array.array(
            "h",
            [
                int(amplitude * math.sin(2 * math.pi * 220 * n / 24000))
                for n in range(4800)
            ],
        )
        with wave.open(str(path), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(24000)
            output.writeframes(samples.tobytes())

    def sources(self, root):
        # Deliberately mismatched levels: assembly must level them.
        levels = {"kokoro": 3000, "apple-premium": 20000}
        return {
            system: {
                passage["id"]: self.render(root, system, passage["id"], amplitude)
                for passage in self.PASSAGES
            }
            for system, amplitude in levels.items()
        }

    def render(self, root, system, passage_id, amplitude):
        path = root / "raw" / system / f"{passage_id}.wav"
        self.write_wav(path, amplitude)
        return path

    def test_writes_one_levelled_clip_for_every_passage_and_system(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, _ = listening_screen.assemble_blind_set(
                self.PASSAGES, self.sources(root), root / "out",
                target_dbfs=-20.0, seed=5,
            )

            self.assertEqual(len(manifest), 4)
            levels = []
            for entry in manifest:
                clip = root / "out" / "listener" / "clips" / entry["filename"]
                self.assertTrue(clip.exists(), f"missing {clip}")
                with wave.open(str(clip)) as source:
                    samples = array.array("h", source.readframes(source.getnframes()))
                levels.append(listening_screen.rms_dbfs(samples))
            self.assertLess(max(levels) - min(levels), 0.5)

    def test_key_is_written_outside_the_listener_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            listening_screen.assemble_blind_set(
                self.PASSAGES, self.sources(root), root / "out",
                target_dbfs=-20.0, seed=5,
            )

            self.assertTrue((root / "out" / "key.json").exists())
            listener_files = [
                path.name
                for path in (root / "out" / "listener").rglob("*")
                if path.is_file()
            ]
            self.assertNotIn("key.json", listener_files)

    def test_no_listener_facing_file_names_a_system(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            listening_screen.assemble_blind_set(
                self.PASSAGES, self.sources(root), root / "out",
                target_dbfs=-20.0, seed=5,
            )

            for path in (root / "out" / "listener").rglob("*"):
                if path.is_file() and path.suffix != ".wav":
                    text = path.read_text()
                    for system in ("kokoro", "apple-premium"):
                        self.assertNotIn(system, text, f"{path.name} leaks {system}")


class AppleRenderCommandTest(unittest.TestCase):
    def test_requests_mono_24khz_int16_wav_to_match_kokoro_output(self) -> None:
        command = listening_screen.apple_say_command(
            "Ava (Premium)", "Hello there.", Path("/tmp/clip.wav")
        )

        self.assertEqual(command[0], "say")
        self.assertIn("--file-format=WAVE", command)
        self.assertIn("--data-format=LEI16@24000", command)
        self.assertEqual(command[command.index("-v") + 1], "Ava (Premium)")
        self.assertEqual(command[command.index("-o") + 1], "/tmp/clip.wav")
        self.assertEqual(command[-1], "Hello there.")

    def test_passes_text_as_an_argument_not_through_a_shell(self) -> None:
        # A passage containing shell metacharacters must survive verbatim.
        text = "He said \"go\"; then $PATH & `rm -rf /`"
        command = listening_screen.apple_say_command(
            "Evan (Enhanced)", text, Path("/tmp/clip.wav")
        )

        self.assertEqual(command[-1], text)


class LoadScoresTest(unittest.TestCase):
    def test_reads_filled_rows_and_ignores_unscored_ones(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sheet = Path(directory) / "scoring-sheet.csv"
            sheet.write_text(
                "anonymous_id,passage_id,listener,score,notes\n"
                "clip-001,p1,L1,4,\n"
                "clip-002,p1,L1,,not scored yet\n"
                "clip-003,p1,L2,2.5,flat\n"
            )

            scores = listening_screen.load_scores(sheet)

            self.assertEqual(
                scores,
                [
                    {"anonymous_id": "clip-001", "listener": "L1", "score": 4.0,
                     "notes": ""},
                    {"anonymous_id": "clip-003", "listener": "L2", "score": 2.5,
                     "notes": "flat"},
                ],
            )

    def test_a_score_outside_the_one_to_five_scale_is_an_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sheet = Path(directory) / "scoring-sheet.csv"
            sheet.write_text(
                "anonymous_id,passage_id,listener,score,notes\n"
                "clip-001,p1,L1,9,\n"
            )

            with self.assertRaises(ValueError):
                listening_screen.load_scores(sheet)


class ScoreIntegrityAuditTest(unittest.TestCase):
    """A screen decided by one non-listening rater is worse than no screen."""

    KEY = {
        f"clip-{index:03d}": {
            "passage_id": f"p{index}",
            "system": "kokoro-af-heart" if index % 2 else "apple-ava-premium",
        }
        for index in range(1, 11)
    }

    def scores(self, listeners, notes):
        return [
            {
                "anonymous_id": clip,
                "listener": listeners[index % len(listeners)],
                "score": 5.0 if "kokoro" in record["system"] else 3.0,
                "notes": notes[index % len(notes)],
            }
            for index, (clip, record) in enumerate(sorted(self.KEY.items()))
        ]

    def test_a_single_listener_is_a_blocking_finding(self) -> None:
        findings = listening_screen.audit_scores(
            self.scores(["OnlyOne"], ["varied note %d" % n for n in range(10)]),
            self.KEY, minimum_listeners=2,
        )

        blocking = [f for f in findings if f["blocking"]]
        self.assertTrue(any(f["code"] == "too_few_listeners" for f in blocking))

    def test_notes_repeated_across_clips_are_a_blocking_finding(self) -> None:
        findings = listening_screen.audit_scores(
            self.scores(["L1", "L2", "L3"], ["same note", "other note"]),
            self.KEY, minimum_listeners=2,
        )

        blocking = [f for f in findings if f["blocking"]]
        self.assertTrue(any(f["code"] == "duplicated_notes" for f in blocking))

    def test_scores_perfectly_predicted_by_system_are_a_blocking_finding(self) -> None:
        findings = listening_screen.audit_scores(
            self.scores(["L1", "L2"], ["varied note %d" % n for n in range(10)]),
            self.KEY, minimum_listeners=2,
        )

        blocking = [f for f in findings if f["blocking"]]
        self.assertTrue(
            any(f["code"] == "scores_track_system" for f in blocking),
            f"expected a system-tracking finding, got {findings}",
        )

    def test_a_plausible_human_panel_produces_no_blocking_findings(self) -> None:
        rng = random.Random(11)
        scores = []
        for index, (clip, record) in enumerate(sorted(self.KEY.items())):
            for listener in ("L1", "L2", "L3"):
                base = 4.0 if "kokoro" in record["system"] else 3.4
                scores.append({
                    "anonymous_id": clip,
                    "listener": listener,
                    "score": max(1.0, min(5.0, round(base + rng.uniform(-1.5, 1.5)))),
                    "notes": f"{listener} on {clip}: {rng.choice(['warm', 'clipped', 'flat', 'natural', 'rushed'])}",
                })

        findings = listening_screen.audit_scores(scores, self.KEY, minimum_listeners=2)

        self.assertEqual([f for f in findings if f["blocking"]], [])


if __name__ == "__main__":
    unittest.main()
