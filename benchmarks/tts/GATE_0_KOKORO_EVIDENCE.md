# Gate 0: Kokoro production-candidate evidence

Evidence date: 2026-09-09

## Decision

**Do not integrate Kokoro into the release apps yet. Keep Kokoro-82M v1.0 as
the primary candidate and KittenTTS Mini 0.8 as the runner-up.** The model
quality candidate is viable, but the production gate is still blocked by the
English phonemizer license, source-word timing mapping, mobile CPU measurements,
and sustained thermal measurements.

The exact Kokoro graph to test is the **duration-output v1.0 graph published in
the `model-files-v1.1` release**, not the similarly named graph from the older
`model-files-v1.0` release. The older graph has only an `audio` output and cannot
drive deterministic highlighting. The selected graphs expose `waveform` and
`duration`; each predicted duration frame represents 600 output samples.

## Pinned inputs

| Component | Exact revision/artifact | SHA-256 | Declared license | Conclusion |
|---|---|---|---|---|
| Kokoro source weights | `hexgrad/Kokoro-82M@f3ff3571791e39611d31c381e3a41a3af07b4987`, `kokoro-v1_0.pth` | `496dba118d1a58f5f3db2efc88dbdc216e0483fc89fe6e47ee1f2c53f18ad1e4` | Apache-2.0 | Commercial use is permitted by the declared model license; notices and model-card limitations still need counsel review. |
| Duration FP32 ONNX | `thewh1teagle/kokoro-onnx`, release `model-files-v1.1`, tag commit `b85309f90fd2660ea3309cf0f2581360e4327555`, `kokoro-v1.0.onnx` | `beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a` | Source model Apache-2.0; exporter repository MIT | Keep for parity and listening reference. Confirm redistribution provenance with counsel. |
| Duration FP16 ONNX | same release, `kokoro-v1.0.fp16.onnx` | `f3a290d384fbb27966d462905c71a46cef9e5fd00516b40df32a0b4afe77ac96` | same as above | Smaller, but slower than FP32 on the measured Apple CPU. Do not assume FP16 is the mobile winner. |
| Voice pack | same release, `voices-v1.0.bin` | `bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d` | derived from the Apache-2.0 model repository voice assets | Attribution/provenance review remains pending. |
| Desktop evidence wrapper | `thewh1teagle/kokoro-onnx@3596b26764286a7de9d90c363e988d50578918e5` (`0.6.1`) | Git commit above | MIT | Benchmark-only Python wrapper; not a shipping runtime. |
| Desktop ONNX Runtime | `onnxruntime==1.29.0` | package pin | MIT | Production mobile binaries and notices need their own checksum pins. |
| Current English front end | `phonemizer==3.4.0`, `espeakng-loader==0.2.4`, bundled eSpeak NG | package pins | GPL-3.0 for phonemizer and eSpeak NG | **Release blocker for a proprietary app unless counsel approves a compliant architecture.** Merely replacing Python with sherpa-onnx does not make the eSpeak dependency disappear. |

Primary evidence: the [Kokoro model repository](https://huggingface.co/hexgrad/Kokoro-82M/tree/f3ff3571791e39611d31c381e3a41a3af07b4987),
the [duration-output ONNX release](https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.1),
the pinned [ONNX wrapper source](https://github.com/thewh1teagle/kokoro-onnx/tree/3596b26764286a7de9d90c363e988d50578918e5),
and [eSpeak NG's license](https://github.com/espeak-ng/espeak-ng/blob/master/COPYING).

## Real-model desktop smoke result

These are six-passage, four-thread, full-utterance measurements on an Apple M4
Pro Mac running macOS 26.5.1. They prove that the assets and benchmark protocol
work; they are **not iPhone or Android performance evidence** and cannot pass the
production gate. The adapter correctly advertises `streaming=false`: it returns
PCM only after a complete utterance has been synthesized.

| Metric | Duration FP32 | Duration FP16 | Gate |
|---|---:|---:|---:|
| Model size | 325.5 MB | 163.5 MB | preference only |
| Cold session initialization | 417 ms | 465 ms | <= 2,500 ms |
| RTF p50 | 0.172 | 0.293 | informational in smoke run |
| RTF p95 | 0.251 | 0.356 | sustained p95 still required |
| Warm first-audio p95 | 1,743 ms | 1,977 ms | **<= 900 ms; both fail** |
| Peak process RSS | 806.7 MB | 897.0 MB | <= 900 MB; FP16 narrowly passes on this Mac only |
| Source word timings | unavailable | unavailable | **required; incomplete** |

FP16 reduces the file size but does not help this Apple CPU: unsupported FP16
operations fall back or execute inefficiently. ONNX Runtime's mobile usability
checker also rejects the FP16 graph for NNAPI, Core ML NeuralNetwork, and Core ML
MLProgram. The best fixed-shape analysis still partitions the graph (NNAPI: 174
partitions/84% of nodes; Core ML MLProgram: 114 partitions/91.1%), so the initial
mobile plan must assume the CPU execution provider rather than NPU acceleration.

Reproduce the compatibility check with:

```sh
python -m onnxruntime.tools.check_onnx_model_mobile_usability \
  /absolute/path/kokoro-v1.0.fp16.onnx --log_level info
```

Validated result records:

- `results/kokoro-v1.0-duration-fp32-m4pro-smoke.json`
- `results/kokoro-v1.0-duration-fp16-m4pro-smoke.json`

## Why highlighting is still blocked

The selected ONNX graph emits a duration per phoneme token. It does not emit
source word offsets. The desktop wrapper can expose phoneme timings, but its
front end does not preserve a reliable mapping back through normalization,
number expansion, abbreviations, punctuation, and Unicode source ranges. Guessing
from audio or distributing time evenly would create visibly wrong highlights.

The production front end must return, for every generated phoneme span, the
original source token/range that produced it. Aggregate the graph's phoneme
durations into those source ranges and schedule highlights from the audio sample
clock. Until that mapping is implemented and compared with manual audible-onset
annotations, advertise sentence highlighting only.

## Runner-up real-model smoke

The runner-up is `KittenML/kitten-tts-mini-0.8@c02725660cea441db4c383af69f1f26f5cd00947`:

| Artifact | SHA-256 | Size |
|---|---|---:|
| `kitten_tts_mini_v0_8.onnx` | `0f5bbae4fc4800c98dbc544a87ecfa79510de2fb8222db30d12e5bfe9177df91` | 78,268,016 bytes |
| `voices.npz` | `40ad2638952b77b7b2f30127e2608e169fc69dd256b53bd8aaa3409a33193c42` | 3,278,902 bytes |
| `config.json` | `6b160bc9b19e24ecb21e84bc14f8a7da21fdf47ec72d42450bc5cf514b61804a` | 613 bytes |

The [model repository](https://huggingface.co/KittenML/kitten-tts-mini-0.8/tree/c02725660cea441db4c383af69f1f26f5cd00947)
and [SDK](https://github.com/KittenML/KittenTTS/tree/f0282f0198d497b7256535b755f9f3e339c1baa7)
declare Apache-2.0. However, SDK 0.8.1 also depends on `phonemizer` and
`espeakng-loader`, so it currently shares Kokoro's GPL front-end blocker. Its
public API returns a completed waveform but no source-aligned word timings.

The same six-passage M4 Pro smoke protocol, using the local low-level ONNX path
and Bella voice, measured 1,006 ms cold initialization, 0.239 p50/0.261 p95 RTF,
3,837 ms warm first-audio p95, and 863 MB peak process RSS. This is desktop
evidence only. It fails the 900 ms first-audio gate, is not streaming, and does
not satisfy the timing, sustained-load, offline-radio, mobile-device, or license
gates. Its small graph is attractive, but it is not currently a drop-in
highlighting solution or an automatic replacement for Kokoro.

Validated result record:

- `results/kittentts-mini-0.8-m4pro-smoke.json`

## Required next measurements

1. Implement or license a commercially compatible English G2P that preserves
   source ranges; do not ship `phonemizer`/eSpeak NG in a proprietary bundle
   without explicit legal approval.
2. Build one native CPU adapter around the duration graph and that G2P. Use the
   same C ABI from Swift and JNI and emit the first bounded PCM chunk rather than
   a full utterance.
3. Run the 165-passage corpus on a physical iPhone 12 and a physical 6 GB,
   2022-class Android device. Test FP32, FP16, and int8; do not infer the winner
   from file size.
4. Run the 60-minute screen-off thermal test and manual word-onset timing subset.
5. Perform blind listening comparisons against Kitten Mini and the system voice,
   then obtain a written commercial redistribution review for the complete
   runtime, model, voice, lexicon, and G2P bundle.
