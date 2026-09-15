#!/usr/bin/env python3
"""P1 early listening screen for the neural TTS execution plan.

Phase 0 item P1 decides whether Kokoro is worth funding at all. It renders the
same passages through Kokoro and the best offline OS voices, hides which system
produced which clip, and requires Kokoro to clearly win. A tie fails.

Dependency-free by design, matching the other validators in this directory.
"""

from __future__ import annotations

import array
import csv
import json
import math
import random
import sys
import wave
from pathlib import Path

NEURAL_SYSTEM = "kokoro"
FULL_SCALE = 32767

LISTENER_INSTRUCTIONS = """# P1 listening screen

Rate every clip from 1 to 5 on how close it sounds to a human audiobook
narrator. Judge naturalness, rhythm, and whether words are pronounced
correctly. Do not try to guess which system produced a clip.

Clips for the same passage are grouped by `passage_id` in `manifest.json`, so
compare them against each other before scoring. Fill in the `score` column of
`scoring-sheet.csv` and add anything you noticed to `notes`.

A 1 is robotic or misread. A 5 is indistinguishable from a person reading.
"""


def select_passages(rows, count, seed):
    """Pick `count` English passages spread across categories, deterministically.

    Round-robin over categories so a screen cannot be decided by 20 clips of
    dialogue, and prefer the longer passage inside each category because P1 is a
    long-form narration judgement.
    """
    eligible = [
        row for row in rows if str(row.get("language", "")).lower().startswith("en")
    ]
    if len(eligible) < count:
        raise ValueError(
            f"need {count} English passages, corpus offers {len(eligible)}"
        )

    by_category: dict[str, list] = {}
    for row in eligible:
        by_category.setdefault(row.get("category", "uncategorized"), []).append(row)
    for members in by_category.values():
        members.sort(key=lambda row: (-len(row.get("text", "")), row["id"]))

    order = sorted(by_category)
    random.Random(seed).shuffle(order)

    selected = []
    depth = 0
    while len(selected) < count:
        progressed = False
        for category in order:
            members = by_category[category]
            if depth < len(members):
                selected.append(members[depth])
                progressed = True
                if len(selected) == count:
                    break
        if not progressed:
            break
        depth += 1
    return selected


def rms_dbfs(samples):
    """Return the RMS level of int16 samples in dBFS, or -inf for silence."""
    if not len(samples):
        return float("-inf")
    mean_square = sum(float(sample) ** 2 for sample in samples) / len(samples)
    if mean_square <= 0.0:
        return float("-inf")
    return 20.0 * math.log10(math.sqrt(mean_square) / FULL_SCALE)


def normalize_pcm16(samples, target_dbfs):
    """Match a clip to a target RMS level without ever clipping.

    A listener rates the louder clip higher, so Kokoro and the OS voices are
    levelled before they are compared. The requested gain is reduced when it
    would push the peak past full scale, which keeps the comparison honest at
    the cost of leaving very quiet clips slightly under target.
    """
    current = rms_dbfs(samples)
    if current == float("-inf"):
        return array.array("h", samples)

    gain = 10.0 ** ((target_dbfs - current) / 20.0)
    peak = max(abs(sample) for sample in samples)
    if peak > 0:
        gain = min(gain, FULL_SCALE / peak)

    return array.array(
        "h",
        [
            max(-FULL_SCALE, min(FULL_SCALE, int(round(sample * gain))))
            for sample in samples
        ],
    )


def build_blind_set(passages, systems, seed):
    """Assign an anonymous clip id to every passage/system pair.

    Returns the listener-facing manifest and the separate key. The manifest must
    never carry system identity, and clip ids are handed out after shuffling so
    their order leaks nothing either.
    """
    pairs = [
        (passage["id"], passage.get("text", ""), system)
        for passage in passages
        for system in systems
    ]
    random.Random(seed).shuffle(pairs)

    manifest = []
    key = {}
    for index, (passage_id, text, system) in enumerate(pairs, start=1):
        anonymous_id = f"clip-{index:03d}"
        manifest.append({
            "anonymous_id": anonymous_id,
            "passage_id": passage_id,
            "filename": f"{anonymous_id}.wav",
            "text": text,
        })
        key[anonymous_id] = {"passage_id": passage_id, "system": system}
    return manifest, key


def screen_verdict(scores, key, margin):
    """Decide P1 from listener scores.

    `scores` are per-clip listener ratings keyed by anonymous id. `key` maps each
    anonymous id back to its passage and system. Several Kokoro voices may be
    rated in one screen; the best-scoring one faces the gate, and it must beat
    every OS voice by at least `margin` mean points. Anything less, including a
    tie, fails. Sibling Kokoro voices are candidates, not baselines, so they are
    never counted as competition.
    """
    totals: dict[str, list[float]] = {}
    for entry in scores:
        system = key[entry["anonymous_id"]]["system"]
        totals.setdefault(system, []).append(float(entry["score"]))

    means = {system: sum(values) / len(values) for system, values in totals.items()}
    candidates = sorted(
        (system for system in means if system.startswith(NEURAL_SYSTEM)),
        key=lambda system: (-means[system], system),
    )
    if not candidates:
        raise ValueError(f"no '{NEURAL_SYSTEM}' system was scored")

    selected = candidates[0]
    best = means[selected]
    shortfalls = [
        f"{system} mean {mean:.2f} vs {selected} {best:.2f}"
        for system, mean in sorted(means.items())
        if not system.startswith(NEURAL_SYSTEM) and best - mean < margin
    ]

    return {
        "status": "fail" if shortfalls else "pass",
        "selected_neural_system": selected,
        "systems": means,
        "margin": margin,
        "reason": (
            f"{selected} did not clear the {margin} margin against: "
            + "; ".join(shortfalls)
            if shortfalls
            else f"{selected} cleared the {margin} margin against every OS voice"
        ),
    }


def apple_say_command(voice, text, path):
    """Build the `say` invocation for one clip.

    The format flags matter: without them `say` writes AIFF at its own rate, and
    the screen would compare Kokoro against a resampled OS voice. Text is passed
    as its own argv entry so passage punctuation never reaches a shell.
    """
    return [
        "say",
        "-v", voice,
        "-o", str(path),
        "--file-format=WAVE",
        "--data-format=LEI16@24000",
        text,
    ]


def load_scores(path):
    """Read a filled scoring sheet, skipping rows a listener has not rated."""
    scores = []
    with Path(path).open(newline="") as sheet:
        for row in csv.DictReader(sheet):
            raw = (row.get("score") or "").strip()
            if not raw:
                continue
            score = float(raw)
            if not 1.0 <= score <= 5.0:
                raise ValueError(
                    f"{row.get('anonymous_id')} scored {score}; the scale is 1 to 5"
                )
            scores.append({
                "anonymous_id": row["anonymous_id"],
                "listener": (row.get("listener") or "").strip(),
                "score": score,
                "notes": (row.get("notes") or "").strip(),
            })
    return scores


def audit_scores(scores, key, minimum_listeners):
    """Look for score sheets that were not produced by listening.

    P1 is cheap to fake and expensive to get wrong: a fabricated pass unlocks
    weeks of licensing work. These checks catch the shapes a non-listening rater
    leaves behind. They are heuristics, not proof, so each finding says what it
    saw and the operator has to overrule it deliberately.
    """
    findings = []

    listeners = {entry["listener"] for entry in scores if entry["listener"]}
    if len(listeners) < minimum_listeners:
        findings.append({
            "code": "too_few_listeners",
            "blocking": True,
            "detail": (
                f"{len(listeners)} listener(s) {sorted(listeners)}; "
                f"at least {minimum_listeners} required"
            ),
        })

    notes = [entry["notes"] for entry in scores if entry.get("notes")]
    if notes:
        duplicated = len(notes) - len(set(notes))
        ratio = duplicated / len(notes)
        if ratio > 0.5:
            findings.append({
                "code": "duplicated_notes",
                "blocking": True,
                "detail": (
                    f"{duplicated} of {len(notes)} notes repeat verbatim "
                    f"({ratio:.0%}); notes should describe individual clips"
                ),
            })

    by_system: dict[str, list[float]] = {}
    for entry in scores:
        system = key[entry["anonymous_id"]]["system"]
        by_system.setdefault(system, []).append(float(entry["score"]))
    spreads = {
        system: max(values) - min(values) for system, values in by_system.items()
    }
    means = {
        system: sum(values) / len(values) for system, values in by_system.items()
    }
    if len(by_system) >= 2 and all(spread == 0.0 for spread in spreads.values()):
        if max(means.values()) - min(means.values()) > 0.0:
            findings.append({
                "code": "scores_track_system",
                "blocking": True,
                "detail": (
                    "every clip from a given system received an identical score, "
                    "so the sheet encodes the system mapping rather than the audio"
                ),
            })

    return findings


def read_wav_pcm16(path):
    with wave.open(str(path)) as source:
        if source.getnchannels() != 1 or source.getsampwidth() != 2:
            raise ValueError(f"{path} must be mono int16")
        frames = source.readframes(source.getnframes())
        return array.array("h", frames), source.getframerate()


def write_wav_pcm16(path, samples, sample_rate):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(sample_rate)
        output.writeframes(samples.tobytes())


def assemble_blind_set(passages, sources, output_directory, target_dbfs, seed):
    """Level, anonymize, and lay out a listening set.

    `sources` maps system -> passage id -> rendered wav. Everything a listener
    sees lands in `listener/`; the key that undoes the anonymization is written
    beside that directory, never inside it.
    """
    systems = sorted(sources)
    manifest, key = build_blind_set(passages, systems, seed)

    output_directory = Path(output_directory)
    listener = output_directory / "listener"
    for entry in manifest:
        system = key[entry["anonymous_id"]]["system"]
        samples, sample_rate = read_wav_pcm16(sources[system][entry["passage_id"]])
        write_wav_pcm16(
            listener / "clips" / entry["filename"],
            normalize_pcm16(samples, target_dbfs),
            sample_rate,
        )

    listener.mkdir(parents=True, exist_ok=True)
    (listener / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
    )
    (listener / "README.md").write_text(LISTENER_INSTRUCTIONS)
    with (listener / "scoring-sheet.csv").open("w", newline="") as sheet:
        writer = csv.writer(sheet)
        writer.writerow(["anonymous_id", "passage_id", "listener", "score", "notes"])
        ordered = sorted(manifest, key=lambda row: (row["passage_id"], row["anonymous_id"]))
        for entry in ordered:
            writer.writerow([entry["anonymous_id"], entry["passage_id"], "", "", ""])

    (output_directory / "key.json").write_text(
        json.dumps(key, indent=2, ensure_ascii=False) + "\n"
    )
    return manifest, key


def system_slug(voice):
    cleaned = "".join(
        character if character.isalnum() else "-" for character in voice.lower()
    )
    while "--" in cleaned:
        cleaned = cleaned.replace("--", "-")
    return "apple-" + cleaned.strip("-")


def render_apple_voices(passages, voices, directory):
    import subprocess

    sources = {}
    for voice in voices:
        slug = system_slug(voice)
        sources[slug] = {}
        for passage in passages:
            path = Path(directory) / slug / f"{passage['id']}.wav"
            path.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(
                apple_say_command(voice, passage["text"], path),
                check=True, capture_output=True,
            )
            sources[slug][passage["id"]] = path
    return sources


def collect_external(passages, specifications):
    sources = {}
    for specification in specifications:
        system, _, location = specification.partition("=")
        if not system or not location:
            raise ValueError(f"expected system=directory, got {specification!r}")
        directory = Path(location)
        rendered = {}
        for passage in passages:
            path = directory / f"{passage['id']}.wav"
            if not path.exists():
                raise FileNotFoundError(
                    f"{system} is missing a render for {passage['id']}: {path}"
                )
            rendered[passage["id"]] = path
        sources[system] = rendered
    return sources


def read_corpus(path):
    rows = []
    with Path(path).open() as source:
        for line in source:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def build_command(args):
    passages = select_passages(read_corpus(args.corpus), args.count, args.seed)
    output = Path(args.output)
    sources = render_apple_voices(passages, args.apple_voice, output / "raw")
    sources.update(collect_external(passages, args.external))

    if not any(system.startswith(NEURAL_SYSTEM) for system in sources):
        print(
            f"warning: no '{NEURAL_SYSTEM}' renders supplied, so this set cannot "
            f"decide P1. Supply them with --external {NEURAL_SYSTEM}-<voice>=<dir>.",
            file=sys.stderr,
        )

    manifest, key = assemble_blind_set(
        passages, sources, output, args.target_dbfs, args.seed
    )
    (output / "systems.json").write_text(json.dumps(sorted(sources), indent=2) + "\n")
    print(
        f"Built {len(manifest)} clips for {len(passages)} passages across "
        f"{len(sources)} systems."
    )
    print(f"Give listeners: {output / 'listener'}")
    print(f"Keep private:   {output / 'key.json'}")
    return 0


def score_command(args):
    key = json.loads(Path(args.key).read_text())
    scores = []
    for sheet in args.sheet:
        scores.extend(load_scores(sheet))
    if not scores:
        print("No scored rows found.", file=sys.stderr)
        return 1

    unknown = sorted({entry["anonymous_id"] for entry in scores} - set(key))
    if unknown:
        raise ValueError(f"scored clips missing from the key: {', '.join(unknown)}")

    findings = audit_scores(scores, key, args.min_listeners)
    blocking = [finding for finding in findings if finding["blocking"]]
    for finding in findings:
        label = "BLOCKING" if finding["blocking"] else "warning "
        print(f"{label} {finding['code']}: {finding['detail']}", file=sys.stderr)
    if blocking and not args.accept_audit_warnings:
        print(
            "\nRefusing to emit a P1 decision. P1 is a human listening gate, and "
            "these findings say the sheet was not produced by listening. Fix the "
            "panel and re-score, or pass --accept-audit-warnings to record the "
            "decision anyway with the findings attached.",
            file=sys.stderr,
        )
        return 3

    verdict = screen_verdict(scores, key, args.margin)
    verdict["listeners"] = sorted({entry["listener"] for entry in scores})
    verdict["scored_clips"] = len(scores)
    verdict["audit_findings"] = findings
    if blocking:
        verdict["audit_overridden"] = True
    record = json.dumps(verdict, indent=2) + "\n"
    if args.output:
        Path(args.output).write_text(record)
    print(record, end="")
    print(f"P1 {verdict['status'].upper()}: {verdict['reason']}", file=sys.stderr)
    return 0 if verdict["status"] == "pass" else 2


def main(argv=None):
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    build = subparsers.add_parser("build", help="assemble a blind listening set")
    build.add_argument("--corpus", default="test-fixtures/tts/corpus.jsonl")
    build.add_argument("--count", type=int, default=20)
    build.add_argument("--seed", type=int, default=1)
    build.add_argument("--apple-voice", action="append", default=[])
    build.add_argument(
        "--external", action="append", default=[], metavar="SYSTEM=DIRECTORY"
    )
    build.add_argument("--target-dbfs", type=float, default=-23.0)
    build.add_argument("--output", required=True)
    build.set_defaults(handler=build_command)

    score = subparsers.add_parser("score", help="decide P1 from filled sheets")
    score.add_argument("--sheet", action="append", required=True)
    score.add_argument("--key", required=True)
    score.add_argument("--margin", type=float, default=0.5)
    score.add_argument("--min-listeners", type=int, default=2)
    score.add_argument("--accept-audit-warnings", action="store_true")
    score.add_argument("--output")
    score.set_defaults(handler=score_command)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
