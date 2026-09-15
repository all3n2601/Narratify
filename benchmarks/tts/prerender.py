#!/usr/bin/env python3
"""Whole-book pre-generation planning for the neural TTS reader.

Section 6 of the execution plan originally forbade pre-generating an entire
book, for three reasons: storage, wall-clock cost, and invalidation. The product
decision is to offer it anyway, so those three reasons become requirements
rather than objections:

- Storage: audio is planned as a compressed codec by default, and a job that
  exceeds its budget is reported as over budget before any work starts.
- Cost: the plan estimates wall-clock compute from a measured real-time factor,
  so the user can be told the real number before they commit hours of CPU.
- Invalidation: every chunk is addressed by a key over the model build, voice,
  speed, and chunk text. Changing any of them yields different keys, so stale
  audio is never played and a partial job resumes instead of restarting.

Dependency-free, matching the other tools in this directory.
"""

from __future__ import annotations

import hashlib

# Measured from the Kokoro af_heart desktop trace: 1863 characters of corpus
# text produced 131.1 s of audio. Used only for planning estimates, never for
# timing or highlighting.
SECONDS_PER_CHARACTER = 0.07038

# Bytes per second of 24 kHz mono audio, by storage format.
CODEC_BYTES_PER_SECOND = {
    "pcm16": 24_000 * 2,
    "opus24": 24_000 / 8,
    "opus32": 32_000 / 8,
    "aac64": 64_000 / 8,
}


def cache_key(model_sha256, voice_id, speed, chunk_text):
    """Address a rendered chunk by everything that changes its audio.

    Chunk audio is only reusable for the exact model build, voice, speed, and
    text that produced it. Anything else must miss, so that changing a setting
    can never play stale audio.
    """
    digest = hashlib.sha256()
    for part in (str(model_sha256), str(voice_id), format(float(speed), ".4f")):
        digest.update(part.encode("utf-8"))
        digest.update(b"\x00")
    digest.update(chunk_text.encode("utf-8"))
    return digest.hexdigest()[:32]


def plan_prerender(
    chunks,
    model_sha256,
    voice_id,
    speed,
    rtf,
    codec="opus24",
    rendered_keys=frozenset(),
    storage_budget_bytes=None,
):
    """Cost a whole-book pre-generation job before any of it runs.

    `rtf` is the measured real-time factor for the target device. `rendered_keys`
    are chunks already on disk, so an interrupted job resumes rather than paying
    for the whole book again.
    """
    if codec not in CODEC_BYTES_PER_SECOND:
        raise ValueError(
            f"unknown codec {codec!r}; choose one of "
            f"{', '.join(sorted(CODEC_BYTES_PER_SECOND))}"
        )
    if float(rtf) <= 0.0:
        raise ValueError(f"real-time factor must be positive, got {rtf!r}")

    jobs = []
    pending_characters = 0
    for index, chunk in enumerate(chunks):
        key = cache_key(model_sha256, voice_id, speed, chunk)
        if key in rendered_keys:
            continue
        jobs.append({
            "chunk_index": index,
            "cache_key": key,
            "characters": len(chunk),
            "estimated_audio_seconds": round(len(chunk) * SECONDS_PER_CHARACTER, 3),
        })
        pending_characters += len(chunk)

    total_characters = sum(len(chunk) for chunk in chunks)
    audio_seconds = total_characters * SECONDS_PER_CHARACTER
    pending_audio_seconds = pending_characters * SECONDS_PER_CHARACTER
    estimated_bytes = int(pending_audio_seconds * CODEC_BYTES_PER_SECOND[codec])

    return {
        "total_chunks": len(chunks),
        "pending_chunks": len(jobs),
        "codec": codec,
        "real_time_factor": float(rtf),
        "estimated_audio_seconds": round(audio_seconds, 3),
        "estimated_pending_audio_seconds": round(pending_audio_seconds, 3),
        "estimated_compute_seconds": round(pending_audio_seconds * float(rtf), 3),
        "estimated_bytes": estimated_bytes,
        "storage_budget_bytes": storage_budget_bytes,
        "over_storage_budget": (
            storage_budget_bytes is not None and estimated_bytes > storage_budget_bytes
        ),
        "jobs": jobs,
    }


def describe_plan(plan):
    """Render a plan as the sentence a user should see before committing."""
    hours = plan["estimated_compute_seconds"] / 3600.0
    audio_hours = plan["estimated_pending_audio_seconds"] / 3600.0
    megabytes = plan["estimated_bytes"] / 1e6
    line = (
        f"{plan['pending_chunks']} of {plan['total_chunks']} chunks remaining: "
        f"{audio_hours:.1f} h of audio, about {hours:.1f} h of processing, "
        f"{megabytes:.0f} MB as {plan['codec']}."
    )
    if plan["over_storage_budget"]:
        budget = plan["storage_budget_bytes"] / 1e6
        line += f" Over the {budget:.0f} MB budget; choose a smaller codec or a range."
    return line
