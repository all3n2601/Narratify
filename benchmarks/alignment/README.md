# Narratify alignment gate

This directory holds the repeatable quality gate for syncing a user-supplied MP3 or M4B narration
to the matching ebook text. It contains no model weights, no audio, and no recognizer.

## What the gate proves, and what it does not

The checked-in fixtures are **synthetic**. Word durations are a linear function of word length,
there is no narrator, no microphone, and no acoustic model. The gate measures whether the aligner
recovers known word times from a transcript damaged the way real transcripts are damaged: words
dropped, words misheard, narration that is not in the book, narration of only part of the book,
and narration of an entirely different book.

One caveat about the error numbers, because they flatter the aligner. Only `interpolated-openings`
exercises interpolation at all; in the other cases every measured onset falls on a matched token
and inherits the recognizer's exact time, contributing a zero. With seventeen of twenty pooled
onsets at zero the median cannot move off it, so p95 is the only figure carrying information about
interpolation quality. And even that is generous: these fixtures give every word a duration that is
a linear function of its length, so linear interpolation between neighbours is close to exact by
construction. Real speech is not linear. Expect this number to get worse on real audio, and do not
treat the current value as a baseline it should hold to.

A green gate means **the algorithm is sound**. It does not mean read-along works. Nobody should
cite this gate as evidence of accuracy on real audiobooks.

## Running it

```sh
python3 -m unittest discover -s benchmarks/alignment/tests
python3 benchmarks/alignment/validate_fixture.py
./gradlew :shared:align:jvmTest
```

## Regenerating the fixtures

The hypotheses and ground truth are generated and checked in, so the gate is reproducible without
running Python. Regenerate after changing any `case.json`:

```sh
for case in test-fixtures/alignment/cases/*/; do python3 benchmarks/alignment/make_fixture.py "$case"; done
python3 benchmarks/alignment/validate_fixture.py
```

## The thresholds

A note on the error thresholds. Only `interpolated-openings` exercises the interpolation path;
in every other case each measured onset lands on a matched token and inherits the recognizer's
exact time, so it contributes a zero. The median is therefore dominated by zeros and it is the p95
that carries real information about interpolation quality. Read them that way.

`test-fixtures/alignment/gate.json` holds the gate. `maxFalseSyncOnsets` is the one that matters
most: it counts places where the aligner claimed word-level accuracy while being more than two
seconds wrong. It is zero, and it stays zero. A highlight that is visibly lying is worse than no
highlight, because the reader stops trusting the whole feature and cannot tell which parts were
trustworthy.

Changing any threshold is a research finding. Record the new number and the reason here, in the
same commit as the change.

For reference, the run at the time this was written, over all six cases:

| | median | p95 | coverage | false sync |
|---|---|---|---|---|
| measured | 0 ms | 40 ms | 0.983 | 0 |
| gate | ≤ 250 ms | ≤ 750 ms | ≥ 0.85 | 0 |

Per case: `clean-narration` and `narrator-preamble` at WORD with everything matched, `asr-dropouts`
at WORD with 0.95 matched, `interpolated-openings` and `partial-narration` degraded to SENTENCE at
0.74 and 0.76, and `wrong-edition` refused at NONE with 0.025 matched.

## Real-audio evidence (not yet run)

`whisper_adapter.py` converts whisper.cpp word timings into the hypothesis format described in
`ADAPTER_PROTOCOL.md`, so a real run is:

```sh
ffmpeg -i chapter-03.m4b -ar 16000 -ac 1 -c:a pcm_s16le chapter-03.wav
whisper-cli -m ggml-base.en.bin -f chapter-03.wav --output-json-full --output-file chapter-03
python3 benchmarks/alignment/whisper_adapter.py chapter-03.json --output hypothesis.json
```

No such run is recorded yet. Doing it properly needs LibriVox recordings paired with their
Gutenberg source texts, sentence onsets annotated by hand, and the result written up the way
`benchmarks/tts/GATE_0_KOKORO_EVIDENCE.md` writes up Gate 0. That is a separate piece of work,
and its numbers — not the ones in this directory — are what decide whether the feature ships.

## What is still gated

Running a recognizer on a phone is a separate decision with its own licensing, model
distribution, thermal, and battery gates, in the same shape as Gate 0 for neural TTS. Nothing in
this directory implies that decision has been made.
