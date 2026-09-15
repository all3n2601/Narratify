# Narratify TTS benchmark gate

This directory defines the repeatable Gate 0 benchmark used to choose an
on-device speech engine. It deliberately contains no model weights, voice
assets, or engine-specific runtime.

## What runs now

From the repository root:

```sh
python3 benchmarks/tts/validate_corpus.py
python3 benchmarks/tts/run_benchmark.py \
  --adapter "python3 benchmarks/tts/mock_adapter.py" \
  --engine-id mock \
  --device-id local-development \
  --output benchmarks/tts/results/mock-local.json
python3 benchmarks/tts/validate_result.py benchmarks/tts/results/mock-local.json
```

The mock adapter produces deterministic silence and synthetic word timings. It
tests the corpus, protocol, runner, metrics aggregation, result format, and gate
evaluation. Its performance numbers are not product measurements.

`GATE_0_KOKORO_EVIDENCE.md` records pinned real-model desktop smoke runs for
Kokoro FP32/FP16 and KittenTTS Mini 0.8, their license blockers, and the exact
remaining mobile decision gates. The accompanying adapters are desktop evidence
tooling only; they deliberately advertise full-utterance synthesis and do not
stand in for the production Swift/JNI adapter.

Run `run_benchmark.py --help` for passage filters, repetitions, and thermal-run
settings. A short smoke run is the default. A release decision requires the
device matrix and duration in `benchmark-config.json`.

## P1 early listening screen

`listening_screen.py` builds the Phase 0 blind listening set and decides P1. It
is dependency-free and covered by `tests/test_listening_screen.py`.

```
python3 benchmarks/tts/listening_screen.py build \
  --count 20 --seed 1 \
  --apple-voice "Ava (Premium)" --apple-voice "Evan (Enhanced)" \
  --external kokoro-af-heart=<render-directory> \
  --output benchmarks/tts/p1-listening-set
```

Apple voices are rendered locally through `say` at mono 24 kHz int16, matching
Kokoro's output format so nothing is resampled. Kokoro and Android renders are
supplied with `--external <system>=<directory>` holding one `<passage-id>.wav`
per selected passage. Android's on-device neural voices cannot be rendered on
macOS, so that leg has to be captured on a device and dropped in the same way.

Every clip is RMS-levelled before comparison, because a louder clip is rated
higher regardless of quality. The default target is -23 dBFS, which leaves
enough headroom that the anti-clipping guard never has to leave a clip quiet;
at -20 dBFS it bound on Kokoro clips, whose crest factor is higher than the OS
voices', and quietly handicapped the candidate under test.

The `listener/` directory is the only thing a listener may see. `key.json` sits
outside it, and no listener-facing file names a system. Score with:

```
python3 benchmarks/tts/listening_screen.py score \
  --sheet <filled-sheet.csv> --key benchmarks/tts/p1-listening-set/key.json \
  --output benchmarks/tts/results/p1-decision.json
```

Several Kokoro voices can be rated in one screen; the best-scoring one faces the
gate and must beat every OS voice by the margin. A tie fails, and sibling Kokoro
voices are never counted as competition. Exit status is 0 for pass, 2 for fail,
3 for a refused decision.

Scoring runs an integrity audit first, because a fabricated pass unlocks weeks of
licensing work. It refuses to emit a decision when fewer than `--min-listeners`
distinct listeners scored the set, when more than half the notes repeat verbatim,
or when every clip from a system received an identical score. Those are the shapes
a rater leaves behind when the sheet was filled from the key rather than from
audio. They are heuristics, not proof; `--accept-audit-warnings` records the
decision anyway with the findings attached, and `results/quarantine/` holds
records kept for provenance but excluded from gate decisions.

## Adding a real engine

Implement the newline-delimited protocol in `ADAPTER_PROTOCOL.md` as a small
native command-line host. The same engine wrapper should be used by the mobile
prototype; avoid benchmarking a Python implementation that will not ship.

Create one adapter executable for each candidate/configuration:

- Kokoro v1.0 ONNX: FP32 and FP16 where supported. Return duration-derived word
  boundaries, not boundaries inferred from generated audio.

The adapter must load all assets from local paths. The runner never downloads a
model and should be invoked with radios disabled for release measurements.

On iOS, build a minimal XCTest or command-line host around the production C ABI
and copy the resulting JSON back into `results/`. On Android, use an
instrumentation/benchmark APK or `adb shell` executable using the production
JNI/C ABI. Preserve the protocol fields even if process launching is implemented
inside the app rather than through this desktop runner.

## Measurement rules

1. Reboot the device, disable Low Power/Battery Saver, and record OS build,
   battery level, ambient temperature, and power state.
2. Run one genuinely cold initialization after the model has been evicted from
   memory. Do not call filesystem cache clearing a normal user cold start.
3. Run the full corpus in a deterministic shuffled order at 1.0x. Repeat at
   2.0x when playback time-stretch is integrated.
4. For the thermal test, synthesize and consume audio continuously for 60
   minutes with the screen off. Record rolling RTF, underruns, peak RSS, energy,
   and thermal state at least every minute.
5. Measure word timing against manually annotated audible word onsets for the
   timing subset. Model-predicted durations alone do not prove timing accuracy.
6. Attach the model, voice, runtime, phonemizer, compiler, quantization, thread
   count, and license inventory to every result.

Do not compare candidate engines unless they use the same corpus revision and
device power/thermal conditions.

## Files

- `benchmark-config.json`: hard gates, required devices, and test policy.
- `schemas/result.schema.json`: portable result record.
- `ADAPTER_PROTOCOL.md`: engine adapter contract.
- `run_benchmark.py`: dependency-free protocol runner and aggregator.
- `validate_corpus.py`: fixture integrity and coverage checks.
- `validate_result.py`: dependency-free structural/gate validator.
- `mock_adapter.py`: pipeline-only fake implementation.
- `adapters/kokoro_onnx_adapter.py`: local Kokoro desktop evidence adapter.
- `adapters/kittentts_onnx_adapter.py`: local KittenTTS desktop evidence adapter.
- `../../test-fixtures/tts/corpus.jsonl`: generated benchmark passages.

Generated output belongs under `benchmarks/tts/results/`. Only reviewed device
snapshots should be committed; transient WAV files are created in a temporary
directory and removed automatically.
