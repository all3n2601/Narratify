#!/usr/bin/env python3
"""B4 bounded sentence-streaming model for the neural TTS execution plan.

Every latency gate in section 1 assumes B4 exists. The recorded evidence is all
full-utterance, so it cannot say whether chunked synthesis reaches the 900 ms
first-audio gate. This module chunks text the way `shared:text` already does and
models the producer/queue/playback relationship, so a real per-chunk trace can be
scored without a device.

Dependency-free, matching the other tools in this directory.
"""

from __future__ import annotations

import re

# Mirrors TextPreparationOptions in shared/text (45 / 220 / 350).
MINIMUM_MERGE_CHARACTERS = 45
TARGET_CHUNK_CHARACTERS = 220
MAXIMUM_CHUNK_CHARACTERS = 350

_SENTENCE_END = re.compile(r"(?<=[.!?])[\"')\]]*\s+")


def _split_sentences(text):
    return [part.strip() for part in _SENTENCE_END.split(text.strip()) if part.strip()]


def _split_long(sentence, maximum):
    """Break an over-long sentence at clause boundaries, then at word gaps."""
    if len(sentence) <= maximum:
        return [sentence]

    pieces = []
    remaining = sentence
    while len(remaining) > maximum:
        window = remaining[:maximum]
        cut = max(window.rfind("; "), window.rfind(", "), window.rfind(" — "))
        if cut <= 0:
            cut = window.rfind(" ")
        if cut <= 0:
            cut = maximum
        pieces.append(remaining[:cut].strip())
        remaining = remaining[cut:].strip()
    if remaining:
        pieces.append(remaining)
    return pieces


def _merge_short(sentences):
    """Join clipped sentences so a lone 'He left.' is not synthesized alone."""
    merged = []
    for sentence in sentences:
        if not merged:
            merged.append(sentence)
            continue
        current = merged[-1]
        combined = f"{current} {sentence}"
        too_short = (
            len(current) < MINIMUM_MERGE_CHARACTERS
            or len(sentence) < MINIMUM_MERGE_CHARACTERS
        )
        if too_short and len(combined) <= TARGET_CHUNK_CHARACTERS:
            merged[-1] = combined
        else:
            merged.append(sentence)
    return merged


def chunk_text(text, first_chunk_characters=None):
    """Split text into synthesis units using the shared:text chunking rules.

    `first_chunk_characters` shortens only the opening unit. Time to first audio
    is dominated by how long the first chunk takes to synthesize, so a smaller
    opening chunk buys the gate directly: measured on an M4 Pro, a full-size
    opening chunk reached first audio at 933 ms against the 900 ms gate, while a
    56-character opening chunk reached it at 678 ms. Later chunks stay full size
    because the queue has already absorbed the latency by then.
    """
    sentences = []
    for sentence in _split_sentences(text):
        sentences.extend(_split_long(sentence, MAXIMUM_CHUNK_CHARACTERS))
    chunks = _merge_short(sentences)

    if first_chunk_characters and chunks:
        head = chunks[0]
        if len(head) > first_chunk_characters:
            cut = head.rfind(" ", 0, first_chunk_characters + 1)
            if cut > 0:
                chunks = [head[:cut].strip(), head[cut:].strip()] + chunks[1:]
    return chunks


def simulate_stream(chunks, lookahead_chunks, prepare_ms=0.0):
    """Model bounded streaming playback over a measured per-chunk trace.

    The producer synthesizes sequentially and is held back once `lookahead_chunks`
    finished chunks are waiting, which is the bound B4 requires. Playback starts
    when the first chunk is ready; any later chunk that is not ready when the
    previous one finishes playing is an underrun, and the silent gap is measured.

    `prepare_ms` gives the producer a head start before the listener presses play,
    which models rendering ahead while a book is being opened. A head start hides
    the first-audio wait, and when synthesis is slower than real time it buys a
    bounded listening window, but it cannot make a slow producer keep up: the
    queue still drains at the difference between the two rates.
    """
    if not chunks:
        return {
            "first_audio_ms": None,
            "underruns": 0,
            "underrun_ms": 0.0,
            "peak_queued_chunks": 0,
            "audio_ms": 0.0,
            "wall_ms": 0.0,
        }

    ready = []
    playback_start = []
    playback_end = []
    underruns = 0
    underrun_ms = 0.0

    for index, chunk in enumerate(chunks):
        if index == 0:
            begin = 0.0
        else:
            # Backpressure: chunk index cannot start until the chunk that is
            # `lookahead_chunks` earlier has left the queue for the player.
            release = (
                playback_start[index - lookahead_chunks]
                if index >= lookahead_chunks
                else 0.0
            )
            begin = max(ready[index - 1], release)

        finished = begin + float(chunk["synthesis_ms"])
        ready.append(finished)

        if index == 0:
            start = max(0.0, finished - float(prepare_ms))
        else:
            arrives = finished - float(prepare_ms)
            start = max(arrives, playback_end[index - 1])
            if arrives > playback_end[index - 1]:
                underruns += 1
                underrun_ms += arrives - playback_end[index - 1]
        playback_start.append(start)
        playback_end.append(start + float(chunk["audio_ms"]))

    events = []
    for index in range(len(chunks)):
        events.append((ready[index] - float(prepare_ms), 1))
        events.append((playback_start[index], -1))
    events.sort(key=lambda event: (event[0], event[1]))
    queued = 0
    peak = 0
    for _, delta in events:
        queued += delta
        peak = max(peak, queued)

    return {
        "first_audio_ms": playback_start[0],
        "underruns": underruns,
        "underrun_ms": round(underrun_ms, 3),
        "peak_queued_chunks": peak,
        "audio_ms": round(sum(float(c["audio_ms"]) for c in chunks), 3),
        "wall_ms": round(playback_end[-1], 3),
    }
