# Android neural TTS runtime contract

Narratify's Android build now contains a native Kokoro adapter and ONNX Runtime, while model
weights remain an optional download. The approval boundary prevents those components from being
activated before the exact distribution set is signed off.

## Pack location and trust gate

An installer must place each pack under the app-private directory
`files/tts-models/<pack-id>/`. A pack is invisible to synthesis until every listed asset passes
canonical-path, byte-size, and SHA-256 checks and the manifest explicitly declares commercial
use. Packs never run directly from shared storage.

The Voices screen now exposes the Kokoro candidate and the production installer. The catalog
permits HTTPS data assets only, rejects executable/script suffixes, downloads into staging,
checks available space and exact byte counts, runs the verifier below, and activates only an
exact app-owned approval. Until the license record is signed and that approval is compiled into
the same release, the Download action remains visibly locked.

The required `manifest.json` shape is:

```json
{
  "schemaVersion": 1,
  "packId": "vendor.voice.en-us",
  "packVersion": "1.0.0",
  "runtimeId": "sherpa-onnx",
  "modelId": "reviewed-model-id",
  "modelVersion": "reviewed-model-version",
  "voiceId": "voice-id",
  "voiceVersion": "voice-version",
  "languageTags": ["en-US"],
  "license": {
    "spdxId": "REVIEWED-SPDX-ID",
    "commercialUseAllowed": true,
    "attribution": "Required attribution text"
  },
  "audio": {
    "sampleRateHz": 24000,
    "channelCount": 1,
    "encoding": "SIGNED_INT_16_LE"
  },
  "assets": [
    {"path": "model.onnx", "sizeBytes": 1, "sha256": "64-lowercase-hex-characters"}
  ]
}
```

## Runtime adapter

A reviewed backend implements `OnDeviceNeuralTtsRuntime`, registers once at application startup
with one or more app-owned `ApprovedNeuralVoicePack` records,
and returns a closeable session. The adapter must be network-free, accept cancellation, announce
the PCM format before audio, stream bounded PCM blocks, and provide spoken-character-to-sample
timings. Narratify maps those timings back through normalized tokens to exact source ranges.

The current Android adapter runs the pinned Kokoro v1.0 duration graph with ONNX Runtime 1.24.3.
Its downloaded data set contains the `af_heart` style and pinned BSD-2-Clause CMUdict. An original
Kotlin mapper converts ARPAbet to Kokoro tokens while retaining UTF-16 source ranges; unknown
words use a deterministic spelling fallback. It has no eSpeak or Python phonemizer dependency.

The reader only selects a pack when its model, voice, license, versions, and complete asset-digest
set exactly match one of those compiled review records. A pack cannot authorize itself by setting
`commercialUseAllowed` in its own JSON. If no approved compatible pack and runtime are registered,
the reader uses the existing offline Android `TextToSpeech` implementation.

## Before enabling a production pack

Record legal approval for both engine and every model/voice artifact. Then benchmark cold start,
peak RSS, real-time factor, first-audio latency, sustained thermal behavior, cancellation latency,
and timing accuracy on the target 2022 Android phone. Do not set `commercialUseAllowed` from an
automated license guess.
