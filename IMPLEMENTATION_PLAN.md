# Narratify Implementation Plan

Status: in progress  
Applies to: iOS, iPadOS, Android phones, and Android tablets  

Implementation snapshot (2026-09-09): the shared locator/data/playback/text foundations are implemented; native phone/tablet libraries are running; and TXT/Markdown plus unencrypted EPUB import, rendering, and position restore work on both platforms. Library, Voices, and Settings are functional native destinations on both platforms, with local search, sorting, density controls, persistent themes/typography/narration preferences, installed system-voice selection or preview, and reader quick settings. Android also has a verified foreground offline system-TTS baseline with word highlighting and Media3 playback for individual MP3/M4A/M4B files, including background notification controls and durable audio position. Both apps now expose tested, cancellable neural-PCM playback boundaries and reject voice/model assets unless their complete identity, license, and checksums match an app-owned approval. Pinned real-model desktop smoke runs cover Kokoro FP32/FP16 and KittenTTS Mini 0.8. Production neural TTS is still gated because the reference English front ends depend on GPL phonemizer/eSpeak components, the models do not expose source-word offsets, and the physical-phone timing/thermal matrix is incomplete. iOS audiobook playback, M4B chapters, MP3-folder grouping, full car catalogs, additional document formats, and deeper annotation/search surfaces remain upcoming milestones.
The detailed resumable plan for closing the neural TTS gate is
`docs/NEURAL_TTS_EXECUTION_PLAN.md`.

Primary architecture: Kotlin Multiplatform shared core with native SwiftUI and Jetpack Compose apps

## 1. Objective

Build an offline-first reader that opens local ebooks and audiobooks, produces high-quality speech entirely on device, highlights source text from the audio playback clock, and restores a stable reading position after layout, modality, or device-state changes.

The implementation is successful when it can sustain uninterrupted, screen-off TTS on an iPhone 12 and a 6 GB Galaxy A53-class Android device while retaining word-level synchronization and background media controls.

## 2. Product boundaries

### Included in the first public release

- Local, unencrypted EPUB 2/3, PDF, MOBI/AZW3, CBZ/CBR, TXT, and Markdown.
- Local M4B, M4A, and ordered MP3-folder audiobooks.
- A downloadable or bundled English neural voice pack that needs no network after installation.
- Native system TTS as a fallback, never as the quality benchmark.
- Visual reading, TTS reading, background playback, lock-screen controls, bookmarks, highlights, search, and persistent position.
- Phone and tablet layouts, VoiceOver and TalkBack support, reduced motion, and configurable reading themes.
- Android Auto and CarPlay-compatible media surfaces, subject to Apple granting the CarPlay entitlement.

### Explicitly excluded

- Circumvention of Kindle, Apple, or Adobe DRM.
- KFX and other undocumented Kindle variants unless separately validated.
- OCR for scanned PDFs.
- Exact alignment between separately purchased ebook and audiobook editions.
- Accounts and cloud synchronization.
- User voice cloning.
- A physical page-curl effect in the initial release.

## 3. Decisions that are already made

| Area | Decision |
|---|---|
| Application architecture | Kotlin Multiplatform for domain/data code; SwiftUI and Jetpack Compose for UI |
| EPUB foundation | Readium Swift Toolkit and Readium Kotlin Toolkit |
| PDF | PDFKit on iOS; Readium Pdfium adapter on Android |
| Primary TTS | Kokoro-82M v1.0 ONNX, initially English only |
| TTS runner-up | None. If Kokoro fails a gate, license a commercial engine or ship the system voice. |
| TTS runtime boundary | Owned C ABI over ONNX Runtime; engines remain replaceable |
| English G2P | Permissively licensed native front end; no bundled eSpeak NG |
| Audio authority | Native playback coordinator and its rendered sample/media clock |
| Shared database | SQLDelight over SQLite, including FTS5 |
| Canonical position | Versioned publication locator with structural, textual, progression, and modality-specific anchors |
| DRM | Reject encrypted files with a precise explanation |
| Sync | Local-only in v1; records are designed for future merging |

No current sherpa-onnx TTS binary should enter the release build until its full dependency graph passes license review. The same rule applies to every model and voice pack.

## 4. Proposed repository layout

```text
Narratify/
  androidApp/                 Android application, Compose UI, Media3 service
  iosApp/                     iOS/iPadOS application, SwiftUI, AVFoundation
  shared/
    domain/                   Publications, locators, playback, settings contracts
    data/                     SQLDelight schema, repositories, migrations
    import/                   Import orchestration and canonical manifests
    text/                     Extraction contracts, normalization, chunking
    search/                   FTS indexing and queries
  native/
    tts/                      C ABI, ONNX sessions, duration/timing extraction
    phonemizer/               Permissive English G2P and pronunciation dictionary
    archives/                 libarchive wrapper for CBZ/CBR
    kindle/                   MOBI/AZW3 conversion adapter
  platform/
    readium-android/          Android publication adapter
    readium-ios/              iOS publication adapter
    pdf-android/              Pdfium text/geometry adapter
    pdf-ios/                  PDFKit text/geometry adapter
  test-fixtures/
    publications/            Licensed or generated format corpus
    tts/                     Text, expected token maps, and benchmark passages
    media/                   Chapter and metadata fixtures
  benchmarks/                Device benchmark definitions and result snapshots
  docs/                      Architecture decisions, licenses, release gates
```

Keep generated models, commercial fixtures, copyrighted books, and voice assets out of source control. Store checksums and acquisition instructions instead.

## 5. Core contracts to define first

These types are implementation boundaries. Their serialized forms must be versioned before production data is written.

### PublicationLocator

```text
publicationId
resourceId
resourceIndex
resourceProgression
totalProgression
structuralAnchor        EPUB CFI or equivalent
textQuote               exact, prefix, suffix
pdfAnchor               page, character range, normalized rectangle
audioAnchor             media item, time in microseconds
ttsAnchor               chunk, source word, sample offset
schemaVersion
```

Restoration order is structural anchor, text quote, resource progression, then total progression. Screen coordinates and generated page numbers are never authoritative.

### SourceTextSpan

Represents display text with a source locator, semantic role, language, direction, visibility, and one or more source character ranges. This is the input to normalization and search indexing.

### SpokenToken

Contains original source ranges, display text, normalized spoken text, phonemes, language, pronunciation source, and flags such as heading, footnote, alternative text, or synthetic expansion.

### TtsChunk

Contains a stable chunk ID, ordered spoken tokens, source start/end locators, voice/model versions, synthesis parameters, PCM format, sample count, and word-to-sample timing entries.

### PlaybackSnapshot

Contains the active publication, modality, locator, playback state, media/sample position, rate, buffered duration, voice, and update timestamp. It is written atomically and throttled during playback, then flushed immediately on pause, interruption, seek, backgrounding, or termination.

## 6. Delivery strategy

### Gate 0: TTS and licensing feasibility

This is a time-boxed technical gate, not production implementation.

#### Work

- Build the same small native benchmark harness for Kokoro FP32, adding FP16/int8 only if FP32 misses a gate.
- Run identical passages on the device floor in section 5 of `docs/NEURAL_TTS_EXECUTION_PLAN.md`.
- Measure cold initialization, warm first audio, p50/p95 RTF, peak memory, CPU utilization, energy, and 60-minute thermal behavior.
- Verify that the selected Kokoro ONNX export exposes duration output and reproduce its word-timing calculation outside Python.
- Prototype the English phonemizer with common words, names, abbreviations, numbers, and out-of-vocabulary terms.
- Create a dependency and model license inventory. Include the runtime, phonemizer, lexicon, model, voice embeddings, training-data attribution requirements, and native audio dependencies.
- Compare at least 100 long-form passages with a blinded listening test covering narration, dialogue, nonfiction, and difficult pronunciation.

#### Exit criteria

- Sustained p95 RTF is at most 0.75 after 30 minutes.
- Warm first playable audio is at most 900 ms.
- Peak TTS memory is at most 900 MB.
- A 60-minute screen-off simulation has no buffer underruns.
- Median word-start error is at most 60 ms and p95 is at most 120 ms.
- The entire selected distribution is commercially usable under the intended source-distribution model.

#### Decision

Choose Kokoro only if it passes every hard gate. If it fails, license a commercial engine or ship the system-TTS reader and keep neural speech experimental. Do not substitute a smaller open model.

## 7. Milestones

### Milestone 1: Local reader foundation

Outcome: a usable local reader for EPUB, TXT, and Markdown plus basic MP3/M4A playback.

#### Shared core

- Establish Gradle/KMP modules and continuous integration for Android and iOS builds.
- Define the core contracts above and add serialization compatibility tests.
- Create SQLDelight tables for publications, files, locators, bookmarks, highlights, settings, playback snapshots, import jobs, derived artifacts, and FTS content.
- Implement content hashing, deduplication, import-job recovery, schema migrations, and versioned derived-cache invalidation.
- Implement per-publication settings with global defaults.

#### Import and rendering

- Integrate Readium on both platforms for EPUB opening and navigation.
- Convert TXT and Markdown into a sanitized internal XHTML/package representation.
- Extract metadata, cover, table of contents, resource order, text spans, and accessibility metadata.
- Enforce archive expansion, path traversal, image dimension, recursion, and file-size limits.
- Index title, author, and publication text into FTS5 without blocking first open.

#### User experience

- Build library grid/list, file import, recent reading, reader chrome, table of contents, search, bookmarks, highlights, and reading settings.
- Implement paginated and continuous modes.
- Add Light, Sepia, Dark, and True Black themes plus the first typography controls.
- Make UI controls accessible from the start and support reduced motion.

#### Position verification

- Restore an EPUB location after font size, margins, orientation, pagination mode, and app restart change.
- Restore TXT/Markdown using text quotations after the derived package is regenerated.
- Add golden locator tests for ASCII, emoji, combining characters, RTL text, and CJK text.

#### Definition of done

A user can import, search, read, annotate, close, and accurately resume an EPUB/TXT/Markdown book on phone and tablet. A user can play and resume MP3/M4A audio. No network or account is required.

Biggest risk: locator stability across reflow and renderer updates.

### Milestone 2: Premium foreground TTS

Outcome: high-quality English narration with source-accurate word and sentence highlighting while the app is visible.

#### Text preparation

- Extract semantic text blocks while excluding scripts, CSS, hidden content, repeated navigation, and non-linear resources.
- Implement ICU-based sentence segmentation with protected abbreviations, initials, decimals, URLs, citations, and ellipses.
- Add locale-aware expansion for numbers, currency, dates, Roman numerals, and units.
- Isolate headings, language changes, and chapter boundaries.
- Default to skipping footnote markers and bodies; add an optional deferred-footnote policy.
- Produce a reversible source-to-spoken token map through every transformation.

#### TTS runtime

- Implement the C ABI for session lifecycle, bounded input, cancellation, generation, errors, PCM access, duration output, and memory-pressure unloading.
- Load signed local voice packs and verify checksums and compatibility versions.
- Generate a short first clause, then pipeline sentence-sized chunks.
- Maintain 15–30 seconds of forward buffer without synthesizing an entire book.
- Add pronunciation overrides keyed by language and normalized spelling.
- Fall back to an offline native system voice when the premium pack is unavailable.

#### Highlighting

- Convert phoneme-duration frames to word sample ranges.
- Map each word range back to the original publication source.
- Render sentence and word decorations without triggering text reflow.
- Drive the active token from the rendered sample clock, not a timer.
- Scroll only at sentence or line boundaries.
- Degrade to sentence-only highlighting if an Android system voice omits range callbacks.

#### Definition of done

The user can start TTS anywhere in an EPUB/TXT/Markdown publication, follow synchronized highlighting, seek visually or audibly, switch reading mode, and resume at the same source location. Voice or synthesis changes safely invalidate incompatible cached timing.

Biggest risk: preserving timing accuracy through normalization, phonemization, playback-rate changes, and renderer offsets.

### Milestone 3: Background and audiobook-grade playback

Outcome: uninterrupted narration and audiobook playback with the device locked or the app backgrounded.

#### iOS

- Configure AVAudioSession for spoken audio, interruptions, route changes, Bluetooth, and background playback.
- Schedule neural PCM through AVAudioEngine/AVAudioPlayerNode while retaining a stable sample clock.
- Publish metadata and elapsed position through Now Playing.
- Implement play, pause, skip sentence/chapter, seek, rate, and sleep timer through remote commands.
- Recover the queue after interruption or engine reconfiguration.

#### Android

- Host playback and TTS generation in a Media3 MediaLibraryService/foreground service.
- Implement a Player-compatible adapter over the neural PCM queue.
- Publish metadata, actions, elapsed position, and chapters through MediaSession.
- Handle audio focus, noisy routes, Bluetooth, process recreation, task removal, and notification controls.

#### Audiobooks

- Model M4B/M4A and MP3 folders as ordered media publications.
- Parse M4B QuickTime chapter tracks and Nero-style chapter metadata.
- Order MP3 folders by disc and track metadata before filename fallback.
- Add chapter navigation, playback rate, sleep timer, rewind-after-interruption, and atomic resume position.

#### Definition of done

TTS and existing audiobooks survive one hour with the screen off, respond correctly to headset and lock-screen controls, recover from phone calls and route changes, and resume within one second of the saved position after process death.

Biggest risk: continuing just-in-time neural generation under iOS background and thermal constraints without audible gaps.

### Milestone 4: Full format coverage and tablet polish

Outcome: the complete advertised local format matrix.

#### PDF

- Integrate PDFKit and Pdfium rendering with page virtualization and memory-pressure eviction.
- Extract per-page text, reading order, character ranges, and normalized rectangles.
- Anchor selection, search, position, and TTS highlighting to page text geometry.
- Detect image-only/scanned pages and mark them visual-only.
- Test multi-column pages, headers/footers, ligatures, rotated pages, RTL, and mixed text/image PDFs.

#### MOBI/AZW3

- Place ebook-rs behind an import-only adapter and convert supported unencrypted files into the canonical EPUB-like package.
- Reject encryption and unsupported KFX/Print Replica cases explicitly.
- Validate navigation, images, CSS, links, metadata, and character encodings against the test corpus.
- Keep the original file and version the converted artifact.
- Remove the feature from release rather than ship silent content loss if the corpus gate fails.

#### Comics

- Use the Readium image navigator for CBZ.
- Extract CBR/RAR/RAR5 with the libarchive wrapper into a lazy image manifest.
- Apply natural filename sorting, manga RTL mode, double-page spreads, crop options, and very-large-image limits.

#### Tablet and animation

- Add adaptive library columns, optional two-page reading, persistent side panels, keyboard shortcuts, and pointer behavior.
- Implement a compositor-only horizontal page transition with snapshot fallback for WebView/PDF surfaces.
- Profile transitions while TTS generation and indexing run concurrently.

#### Definition of done

Every advertised unencrypted format passes its fixture corpus, restores position, survives malformed-input tests, and respects device memory limits. Core reader interaction remains at 60 fps on the floor device.

Biggest risk: inconsistent PDF reading order and young MOBI/AZW3 parser behavior.

### Milestone 5: Accessibility, car integration, and release hardening

Outcome: production-ready distribution.

#### Accessibility completion

- Audit all native screens with VoiceOver and TalkBack.
- Preserve publication headings, lists, links, tables, language changes, and image alternatives.
- Ensure TTS word highlights never steal accessibility focus or create per-word announcements.
- Offer optional sentence-boundary accessibility updates, disabled by default.
- Validate Dynamic Type/font scaling, bold text, high contrast, switch control, keyboard use, and reduced motion.
- Enforce theme contrast thresholds in the custom theme editor.

#### Car surfaces

- Apply for the CarPlay audio entitlement during Milestone 1; implement only after approval.
- Expose recent books, audiobooks, and continue-reading items through driver-safe media lists.
- Support play, pause, chapter/sentence skip, seek, and rate where host policy permits.
- Implement Android Auto browsing through MediaLibraryService and test with the desktop head unit.

#### Reliability and compliance

- Fuzz parsers and native ABI boundaries using generated and malformed files.
- Run migration, low-storage, memory-warning, corrupted-cache, interrupted-import, and process-death tests.
- Produce a software bill of materials and third-party notices from locked dependencies.
- Verify that analytics and crash reports never contain book text, filenames, searches, highlights, or synthesized audio.
- Add signed voice-pack manifests, rollback, partial-download recovery, and minimum-runtime compatibility.
- Complete App Store and Play policy reviews for local files, background audio, media controls, and model licensing.

#### Definition of done

The release candidate passes the device matrix, accessibility audit, license audit, 60-minute background endurance suite, parser security suite, and store-submission checklist with no critical or high-severity defects.

Biggest risk: the combined OS/device matrix and Apple CarPlay entitlement dependency.

## 8. Dependency sequence

```text
TTS/license gate
       |
Core contracts + database
       |
Import pipeline ---- Readium/PDF/media adapters
       |                         |
Source text mapping              |
       |                         |
Normalization + G2P              |
       |                         |
TTS durations -> sample map -----+
       |
Native playback clock
       |
Highlighting + position service
       |
Background media services
       |
Car interfaces and release hardening
```

The playback clock, locator service, and TTS engine must remain separate. This prevents replacing a model or audio backend from invalidating stored reading positions.

## 9. Test strategy

### Fixture corpus

Maintain legally redistributable or generated fixtures for every format and edge case. Each fixture records expected metadata, resource count, table of contents, selected text, locator restoration points, encryption state, and known rendering exceptions.

Target minimums before release:

| Corpus | Minimum |
|---|---:|
| EPUB 2/3 | 300 files |
| PDF | 250 files, including 75 complex-layout and 50 scanned |
| MOBI/AZW3 | 500 files before enabling release support |
| CBZ/CBR | 150 archives across ZIP, RAR4, and RAR5 |
| M4B/M4A/MP3 publications | 100 items/folders |
| TTS normalization passages | 2,000 assertions |
| TTS listening/benchmark passages | 100 representative passages |

### Automated layers

- Shared unit tests for locators, normalization, chunking, storage, migrations, and conflict-free future sync fields.
- Property-based tests for Unicode offsets, normalization reversibility, natural sorting, and locator fallback.
- Native unit tests for the C ABI, cancellation, invalid tensors, buffer ownership, and memory warnings.
- Snapshot tests for themes, responsive layouts, and reduced-motion states.
- End-to-end tests for import, open, seek, TTS start, background, interruption, process death, and resume.
- Performance tests for frame time, page memory, import latency, search indexing, TTS RTF, and energy.
- Fuzzing for ZIP/XML/HTML/MOBI/RAR inputs and every native boundary.

### Device matrix

The hard floor is iPhone 12 and Galaxy A53 6 GB. Also test a small iPhone, current flagship iPhone, low-density Android phone, Snapdragon mid-range device, Samsung flagship, 8-inch Android tablet, and iPad. Bluetooth, wired/headset controls, speaker, CarPlay simulator, and Android Auto desktop head unit are separate test dimensions.

## 10. Operational budgets

| Resource | Budget |
|---|---:|
| Base app download, excluding optional voice packs | Prefer under 100 MB per architecture |
| Premium English voice pack | Prefer under 400 MB |
| TTS peak resident memory | 900 MB maximum on floor device |
| PDF page cache | Adaptive; start at 64–128 MB |
| Audio buffer ahead | 15–30 seconds |
| Position checkpoint while playing | Every 5 seconds, plus immediate lifecycle flush |
| Reader interaction frame budget | 16.7 ms at 60 Hz |
| Warm first TTS audio | 900 ms maximum |
| Search availability after first open | Current resource immediately; full book asynchronously |

## 11. Risk register and stop conditions

| Risk | Mitigation | Stop condition |
|---|---|---|
| Kokoro thermal performance | Early device gate, short first chunk, forward buffering, configurable threads | Stop if sustained p95 RTF exceeds 0.75 or either memory gate in `docs/NEURAL_TTS_EXECUTION_PLAN.md` is breached |
| TTS licensing contamination | Locked SBOM, no eSpeak, per-voice audit | No release build until counsel/dependency review is clean |
| Incorrect word highlighting | Use model durations and sample clock; preserve source mappings | Degrade to sentence highlighting rather than display knowingly incorrect words |
| MOBI/AZW3 data loss | Large corpus, import-only adapter, retain original | Remove advertised support if navigation/content fidelity misses the release threshold |
| PDF reading-order errors | Geometry-aware extraction and explicit scanned/complex detection | Disable TTS for pages whose reading order cannot be established reliably |
| Background underruns | 15–30 second buffer, adaptive chunk size, interruption recovery | No public TTS release until one-hour floor-device test has zero underruns |
| Cross-edition position mismatch | Exact locators within one publication; progression only across editions | Never label cross-edition approximation as exact sync |
| CarPlay approval | Apply during Milestone 1; keep normal lock-screen controls independent | CarPlay may slip without blocking the core reader release |

## 12. Deferred roadmap

After v1, evaluate on-device OCR as a page-aware feature using cached word rectangles and confidence. Evaluate optional encrypted sync only after defining identity, recovery, conflict handling, retention, and privacy operations. Add neural language packs one at a time, with their own model, G2P, timing, license, pronunciation, and device-performance gates.

Exact ebook-to-audiobook alignment should be treated as a separate research project involving edition matching and offline forced alignment. It should not share a delivery milestone with ordinary position persistence. Its first half is built: `shared/align` holds the deterministic aligner and `benchmarks/alignment` holds a synthetic quality gate, both described in `docs/superpowers/plans/2026-09-14-audiobook-forced-alignment.md`. Two things remain before this can be scheduled as a feature — a real-audio evidence run against annotated LibriVox recordings, and a decision on running a recognizer on device, which carries its own licensing, distribution, and thermal gates.

## 13. Immediate next actions

1. Run Phase 0 of `docs/NEURAL_TTS_EXECUTION_PLAN.md`: the early listening screen against the OS premium voices first, then the native feasibility spike on the current floor devices.
2. Move Android imports off the Activity thread, then add M4B chapter parsing and ordered MP3-folder grouping with repository-owned fixtures.
3. Implement the iOS AVAudioEngine/MPNowPlayingInfoCenter audiobook slice and verify screen-off restoration and interruption handling.
4. Promote Android playback to a browsable `MediaLibraryService`, define the shared audiobook catalog, and start the CarPlay entitlement request/catalog implementation.
5. Add PDF as the next visual format, preserving page/text anchors and explicitly detecting image-only documents without bundling OCR.
6. Replace package-checkout test assets with small, generated, repository-owned EPUB and audiobook fixtures.
7. Implement the reading-settings/theme contract and complete VoiceOver/TalkBack plus reduced-motion test passes before expanding decorative motion.

The first irreversible investment should be the position and text-mapping contracts, not a renderer-specific UI or a model-specific API.
