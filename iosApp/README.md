# Narratify iOS app

This directory contains the native SwiftUI app for iPhone and iPad. TXT and Markdown import, unencrypted EPUB import and Readium rendering, private managed-file storage, UTF-8 validation, Markdown rendering, resilient reading-position persistence, and EPUB read-aloud with spoken-passage highlighting are functional. TXT/Markdown imports are capped at 20 MB; EPUB imports are streamed for hashing and capped at 500 MB to avoid loading the whole archive into memory. Discover searches five public catalogs and can securely import direct Project Gutenberg EPUB files; metadata and borrowing results remain links to their source. The app has no seeded or sample library entries.

`SpeechRuntime.swift` defines the production boundary for an offline neural engine and a working `AVSpeechSynthesizer` fallback behind the same playback contract. Neural PCM is streamed into `AVAudioEngine`, rate/pitch are applied without re-synthesis, and source-word ranges are emitted from the rendered sample clock for highlighting. `NeuralVoicePackInstaller.swift` adds the data-only HTTPS download, staging, free-space, byte-count, SHA-256, app-owned approval, activation, cancellation, and removal boundary. The Voices screen shows the same exact Kokoro model, `af_heart` voice, and CMUdict candidate as Android, but its Download action remains locked until a reviewed iOS runtime and commercial approval are supplied. No neural weights or iOS inference dependency are bundled yet.

## Generate and build

```sh
cd iosApp
xcodegen generate
xcodebuild -project Narratify.xcodeproj -scheme Narratify \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

## Shared Kotlin integration status

The app currently builds independently. The domain module exposes Kotlin 2.4's experimental `embedSwiftExportForXcode` task, but its generated Swift package does not compile against the current dependency graph and Xcode toolchain. The generated `OrgJetbrainsKotlinxKotlinxSerializationCore.swift` contains duplicate declarations for `decodeSequentially()`, `shouldEncodeElementDefault`, and `encodeNotNullMark()`.

Do not put the experimental export task into the Xcode build until that upstream incompatibility is resolved. The stable integration path is to add a named Kotlin/Native framework binary (or XCFramework) to `shared/domain/build.gradle.kts`, expose only the intended public contracts, then add the generated product and an embed/sign build phase to `project.yml`. Keeping this boundary explicit prevents the shell from silently presenting unverified shared behavior.
