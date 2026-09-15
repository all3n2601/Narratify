# Narratify

Narratify is an offline-first ebook and audiobook reader for iOS and Android. The repository contains the implementation foundation plus working native reader slices for local books.

## Current modules

- `androidApp`: responsive native Android library with Library, Discover, Voices, and Settings destinations; one search across five open catalogs; direct public-domain EPUB import; durable TXT/Markdown/EPUB reading; Readium 3.2 EPUB narration; offline system-TTS voice selection and word highlighting; a verified neural-PCM runtime boundary plus a data-only, staged, checksum-gated voice-pack installer; and Media3 background playback for individual MP3/M4A/M4B files.
- `iosApp`: responsive SwiftUI library with Library, Discover, Voices, and Settings destinations; one search across five open catalogs; direct public-domain EPUB import; durable TXT/Markdown/EPUB reading; Readium 3.11 EPUB narration with spoken-passage highlighting; locator restoration; and a tested neural-PCM playback boundary plus a data-only, staged, checksum-gated voice-pack installer.
- `shared/domain`: platform-independent locators, text/audio alignment, and playback state.
- `shared/data`: SQLDelight library/index schema, portable search cache, managed-file records, and durable visual positions.
- `shared/playback`: deterministic audiobook/TTS queue state machine and platform-backend contracts.
- `shared/text`: deterministic sentence segmentation, conservative speech normalization, chunking, and source-preserving spoken-token maps.
- `shared/align`: deterministic anchor-and-fill alignment between a chapter's text and a transcript of its narration, with confidence-gated granularity that refuses to claim word-level sync it cannot support.
- `benchmarks`: device acceptance harness for candidate offline TTS engines.
- `test-fixtures/tts`: versioned multilingual and adversarial benchmark corpus.

The production neural TTS runtime is intentionally still behind Gate 0. Android now has a native
Kokoro/ONNX Runtime adapter, a pinned CMUdict frontend without eSpeak, and source-word timings.
Its downloadable data remains locked until the exact distribution receives legal approval and
passes the physical-phone thermal matrix. iOS does not yet bundle the corresponding inference
runtime. Both apps continue to fall back to the offline system voice. See `benchmarks/tts/GATE_0_KOKORO_EVIDENCE.md` and
`IMPLEMENTATION_PLAN.md`. The resumable neural-voice checklist and device matrix are
in `docs/NEURAL_TTS_EXECUTION_PLAN.md`; the auditable release boundary and remaining
legal approvals are recorded in `docs/tts/DEPENDENCY_LICENSE_INVENTORY.md`.

## Build

Use the checked-in Gradle wrapper. Shared and Android verification is independent of an emulator:

```shell
./gradlew check
```

Android compilation requires Android SDK 37. iOS compilation requires Xcode and runs only on macOS.

Generate and test the iOS app with:

```shell
cd iosApp
xcodegen generate
xcodebuild -project Narratify.xcodeproj -scheme Narratify \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test \
  CODE_SIGNING_ALLOWED=NO
```

To build and install the development shell on a running Android emulator or device:

```shell
./gradlew :androidApp:installDebug
adb shell am start -n app.narratify/.MainActivity
```
