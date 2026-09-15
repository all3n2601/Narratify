# Narratify Neural TTS Execution Plan

Status: ready to execute  
Created: 2026-09-09  
Revised: 2026-09-09 — Kitten and Supertonic dropped; Phase 0 and A2/A3 time-boxed; device floor raised; memory gate split into backstop plus drift; FP32-only;
Phase 0 screening added ahead of Phase A; G2P parity gate added to A2  
Scope: English offline neural TTS for iOS and Android  
Candidate: Kokoro-82M v1.0. No runner-up model; see section 6 for excluded candidates.

## 1. Required outcome

Ship one commercially usable voice that runs entirely on-device after installation,
supports background controls and synchronized highlighting, and safely falls back to
the OS voice. It must pass all of these gates on the target devices:

| Gate | Requirement |
|---|---:|
| Warm first audible PCM, p95 | <= 900 ms |
| Sustained RTF, p95 after 30 minutes | <= 0.75 |
| Peak process RSS while reading, absolute backstop | <= 1.2 GB |
| Peak process RSS drift from the pinned native baseline | <= 25% |
| Screen-off endurance | 60 minutes, zero underruns |
| Word-onset error, median / p95 | <= 60 ms / <= 120 ms |
| Cancellation to silence | <= 150 ms |
| Network use while reading | None |
| Licensing | Complete distribution graph approved for commercial use |

Speech generation alone is not an exit condition. Every gate must pass.

The memory gates are deliberately split. The absolute backstop guards against process
termination, especially while backgrounded, where the OS grants a resident audio
process far less headroom than total device RAM implies. The drift gate is the actual
defect detector: pin peak RSS on the first clean native streaming measurement, then
fail on regression from that pin. A single loose ceiling derived from desktop
full-utterance Python runs would pass a leaked session, an unbounded PCM queue, or an
unconfigured ONNX Runtime arena without flagging any of them.

## 2. Baseline already implemented

- Source-preserving normalization and sentence/chunk preparation.
- Android offline system-TTS playback and word highlighting.
- Replaceable Android neural runtime contracts and bounded PCM player.
- Replaceable iOS neural engine contract and AVAudioEngine playback.
- Sample-clock timing consumers on both platforms.
- App-owned identity, license, and asset-checksum approvals on both platforms.
- Automatic OS-voice fallback when an approved neural engine is unavailable.
- Pinned desktop smoke results for Kokoro FP32/FP16.

Open blockers are the GPL-based reference phonemizers, missing original-source word
mapping, full-utterance candidate adapters, missing physical-phone results, and the
unfinished blind listening comparison.

## 3. Fixed safety decisions

| Question | Decision |
|---|---|
| Bundle eSpeak/phonemizer now? | No. Treat as prohibited without written legal approval. |
| Trust a downloaded pack's license claim? | No. Identity and hashes must match an app-owned approval. |
| Guess word timing? | No. Degrade to sentence highlighting when verified timing is unavailable. |
| Select from desktop performance? | No. Desktop runs only validate candidates and tooling. |
| Initial language | English only; every later language gets a separate gate. |
| Runtime | Owned C ABI over ONNX Runtime CPU initially; do not assume NPU acceleration. |
| Distribution | Downloadable, app-private voice pack; OS voice available immediately. |

## 4. Sequenced work plan

### Phase 0 — Go/no-go screening

Both items are cheap, and either one can invalidate the rest of the plan. Neither ships code.

- [ ] **P1. Early listening screen**
  - Build/test: render at least 20 long-form passages with Kokoro FP32 (`af_heart`, `am_michael`, and two alternates), and the same passages with the best offline Apple and Google premium voices. Anonymize and compare blind.
  - Acceptance: Kokoro clearly beats both OS voices on naturalness and pronunciation. A tie fails this gate.
  - Exit on failure: ship the existing system-TTS reader, or price a commercially licensed embedded engine that supplies its own G2P and word timings. Do not proceed to A2.
  - Time box: 3 working days.
  - Acceptance, panel integrity: at least two human listeners score the set, and the scoring sheet passes the integrity audit in `benchmarks/tts/listening_screen.py`. A sheet whose scores or notes track the system rather than the clip is not evidence, and a machine-generated sheet is not a listening result at all.
  - Verify: retain the passage set, anonymized IDs, scores, audit findings, and the decision. This is a screen, not a substitute for D4.

- [ ] **P2. Native feasibility spike**
  - Build/test: throwaway native build — ONNX Runtime Mobile CPU, the pinned Kokoro duration graph, sentence-sized chunking, no timing map, no product integration. Run on one section 5 floor device per platform.
  - Acceptance: record first-audio, RTF, and peak RSS with sentence chunking active, and pin the RSS baseline that section 1's drift gate measures against.
  - Exit on failure: if sentence-chunked first audio or sustained RTF cannot approach the section 1 gates on a floor device, no amount of Phase A work rescues the candidate.
  - Time box: 5 working days.
  - Verify: signed result JSON through the existing benchmark protocol, marked as spike evidence and excluded from release records.

**Exit:** Kokoro is worth funding on measured audio quality and measured on-device
feasibility, not on desktop smoke results alone.

### Phase A — Text frontend and license gate

- [ ] **A1. Dependency and license inventory**
  - Build: enumerate model, voices, runtime, tokenizer, G2P, lexicon, audio code, and transitive native dependencies with exact revisions and hashes.
  - Acceptance: every shipped artifact has an SPDX identifier, attribution, redistribution decision, and reviewer status; unknown/copyleft entries fail validation.
  - Verify: scan a staged voice pack and generate its complete notice inventory.

- [ ] **A2. Permissive English G2P selection**
  - Build: evaluate Misaki (Kokoro's own lexicon plus part-of-speech frontend) with its eSpeak out-of-vocabulary fallback replaced by a permissively licensed neural G2P, an owned rules/lexicon frontend, and a separately licensed commercial frontend. Verify every declared license against source per A1; do not accept a package's own claim.
  - Acceptance: deterministic, offline, Unicode-safe output with abbreviation and number expansion; no unapproved GPL runtime or data.
  - Acceptance, phoneme parity: the candidate frontend matches the eSpeak reference on at least 95% of tokens across the pronunciation corpus, and every divergence is reviewed and classified as correct, acceptable, or a regression.
  - Acceptance, audio parity: Kokoro output rendered from candidate phonemes scores no worse than eSpeak-phonemized reference audio in a blind A/B over at least 50 long-form passages. Kokoro was trained on eSpeak phonemes, so a frontend swap that degrades intelligibility or naturalness fails A2 even when its licensing is clean.
  - Time box: 3 calendar weeks, with a go/no-go review at the end of week 2. On expiry, take the timeout branch: license a commercial English front end, or ship the system-TTS reader and keep neural speech experimental. Do not extend the box to finish an owned rules-and-lexicon frontend; English heteronym and abbreviation coverage has an unbounded tail.
  - Verify: 500-case pronunciation and source-mapping suite, phoneme-diff report, blind A/B record, and recorded license review. eSpeak may be run locally to generate reference phonemes and reference audio for these comparisons; that is measurement, not distribution, and no eSpeak code or data may enter a shipped artifact.

- [ ] **A3. Source-to-phoneme mapping prototype**
  - Build: retain original UTF-16/code-point ranges through normalization, token expansion, G2P, punctuation, and pauses.
  - Acceptance: every phoneme maps to source text or an explicitly synthetic pause; one token may expand to several spoken tokens.
  - Time box: 2 calendar weeks.
  - Verify: golden tests for numbers, currency, dates, abbreviations, names, dialogue, combining characters, emoji, and embedded RTL text.

**Exit:** commercially approved dependency graph and at least 99.5% exact range
mapping over the frontend fixtures. Unmapped cases must use sentence highlighting.

### Phase B — Production native inference core

- [ ] **B1. Freeze the versioned C ABI**
  - Build: model open/close, synthesis start/cancel, PCM/timing callbacks, errors, capabilities, and explicit ownership.
  - Acceptance: Swift and JNI hosts run the same fake streaming engine without leaks or lifecycle races.
  - Verify: contract tests and repeated create/synthesize/cancel/destroy stress tests.

- [ ] **B2. Package ONNX Runtime Mobile CPU**
  - Build: pin one reviewed ORT release for iOS device/simulator and required Android ABIs; minimize operators only after freezing the graph.
  - Acceptance: approved graph loads offline on both platforms; tampered assets are rejected before parsing.
  - Verify: airplane-mode load, checksum-tamper tests, and packaged-binary license scan.

- [ ] **B3. Implement Kokoro duration inference**
  - Build: ship FP32. Aggregate phoneme durations into Phase A source ranges; the pinned graph emits both `waveform` and `duration`, where each duration frame covers 600 output samples.
  - Build: do not produce FP16/int8 variants unless FP32 misses the sustained RTF gate on a floor device. The memory headroom on the current device floor does not justify the voice-quality risk of quantization.
  - Acceptance: deterministic PCM and monotonic, in-range source timings across all 165 passages.
  - Verify: native result records validate with zero malformed timing ranges.

- [ ] **B4. Add bounded sentence streaming**
  - Build: synthesize sentence-sized units ahead and feed a bounded two-stage PCM queue.
  - Build: shorten only the opening chunk. Desktop spike evidence (`benchmarks/tts/results/b4-streaming-desktop-spike.json`) measured a full-size opening chunk at 933 ms first audio against the 900 ms gate, and a 56-character opening chunk at 678 ms, with zero underruns either way. Time to first audio is set by the first chunk alone, so this is a requirement rather than a tuning option. Later chunks stay full size.
  - Build: spend the available memory headroom on lookahead depth and on keeping the session and voices resident across pause and stop, so warm playback never pays a reload. Tune the queue bound upward until the sustained RTF and underrun gates hold with thermal throttling active; the bound stays explicit and asserted, never merely inferred from RSS.
  - Acceptance: warm first PCM p95 <= 900 ms; queue depth stays within its declared bound; cancellation reaches silence within 150 ms.
  - Verify: latency/cancellation tests across short, medium, and long passages, plus a queue-bound assertion test under induced slow synthesis.

**Exit:** Kokoro emits bounded PCM and source-aligned timings through the same C ABI
on both platforms.

### Phase C — Platform completion

- [ ] **C1. Android JNI adapter**
  - Build: register only an approved pack/runtime and connect it to the existing AudioTrack path.
  - Acceptance: play, pause, resume, seek, speed, stop, focus loss, background, lock screen, and OS fallback work.
  - Verify: instrumentation lifecycle suite and manual notification-control pass.

- [ ] **C2. iOS C adapter**
  - Build: implement `LocalNeuralTTSEngine`; add interruptions, route changes, background audio, and media commands.
  - Acceptance: playback/highlighting survive lock, Bluetooth changes, interruption, and restoration.
  - Verify: XCTest adapter suite and manual lock-screen/AirPods pass.

- [ ] **C3. Buffer and cache policy**
  - Build: bounded forward buffering, cancellation of stale work, and version-addressed app-private chunks.
  - Build: an opt-in prepare-ahead lead buffer, capped in audio minutes, that hides the cold start and keeps playback alive on a device slower than real time. Desktop modelling: at RTF 0.7 a five-minute head start banks 7.1 minutes of audio; above RTF 1.0 it buys a bounded window rather than a fix.
  - Build: opt-in whole-book pre-generation over the same chunk cache, resumable, cancellable, cost-estimated before it starts, and bounded by a storage budget. Planning model and estimates live in `benchmarks/tts/prerender.py`.
  - Acceptance: neither feature runs in the background where the OS forbids sustained compute, and neither starts without explicit user consent for its stated time and storage cost.
  - Acceptance: old voice/rate/model audio never plays after a change; cache stays below its ceiling and is purgeable.
  - Verify: rapid seek/settings stress test and cache-version migration test.

**Exit:** installing an approved pack switches both apps to neural speech without
changing reader behavior; removing or corrupting it restores the OS fallback.

### Phase D — Device and quality qualification

- [ ] **D1. Performance-floor matrix**
  - Build/test: run the Kokoro FP32 build on the section 5 floor devices. Add FP16/int8 variants only if FP32 misses a gate.
  - Acceptance: complete full-corpus cold start, first audio, RTF, RSS, CPU, and energy records.
  - Verify: validate signed result JSON with device, OS, and application build IDs.

- [ ] **D2. Thermal/background endurance**
  - Build/test: synthesize and consume continuously for 60 minutes, screen off, background controls active.
  - Acceptance: p95 RTF <= 0.75, peak RSS within both section 1 memory gates, zero underruns/termination, and no uncontrolled thermal decline.
  - Verify: minute-by-minute thermal, RSS, RTF, buffer-depth, and underrun telemetry. Record the OS-reported termination reason for any kill, so memory eviction is never misread as thermal decline.

- [ ] **D3. Highlight accuracy**
  - Build/test: manually annotate audible onsets for the timing subset, including expansions.
  - Acceptance: median <= 60 ms, p95 <= 120 ms, and seeking never highlights future audio.
  - Verify: comparison report and slow-motion screen-recording spot checks.

- [ ] **D4. Blind listening decision**
  - Build/test: compare Kokoro, eligible runner-up, and best offline OS voice over at least 100 long-form passages.
  - Acceptance: selected voice clearly beats the baseline without a recurring blocking artifact.
  - Verify: retain anonymized IDs, scoring rubric, participant count, results, and decision.

**Exit:** select neural TTS only after every performance, timing, license, and quality
gate passes. Otherwise release with system TTS and keep neural speech experimental.

### Phase E — Release hardening

- [ ] **E1. Atomic voice-pack installation**
  - Build: resumable acquisition/import, free-space check, staging, verification, atomic activation, recovery, and removal.
  - Acceptance: interruption or corruption never replaces a valid pack or prevents reading.
  - Verify: interrupt every stage; test low storage and tampering.

- [ ] **E2. Accessibility coexistence**
  - Build: visual highlights that do not move VoiceOver/TalkBack focus each word; pause Narratify speech when the OS reader speaks the content.
  - Acceptance: no double speech, focus theft, or rapid accessibility announcements.
  - Verify: complete VoiceOver/TalkBack scripts with reduced motion on and off.

- [ ] **E3. Offline/privacy proof**
  - Build/test: audit neural code for network APIs and deny networking at OS level.
  - Acceptance: reading, synthesis, controls, and restart work with all networking blocked.
  - Verify: packet capture/network denial plus release-binary inspection.

- [ ] **E4. Release decision record**
  - Build: record approved artifacts/hashes, supported devices, limitations, fallback behavior, notices, and evidence.
  - Acceptance: an independent reviewer can reproduce the pack and decision.
  - Verify: engineering, product, accessibility, and license sign-off.

## 5. Required physical test matrix

| Dimension | Cases |
|---|---|
| Device floor | iOS: iPhone 15 or newer, and iPad with M1 or newer. Android: 8 GB RAM, 2024-class SoC, CPU-only execution. Exact certified SKUs need product-owner sign-off before D1 hardware purchase (section 7). |
| Power | Battery and AC; low-power modes recorded separately |
| Layout | Phone portrait/landscape; tablet/iPad full and split screen |
| Route | Speaker, wired where available, Bluetooth, route disconnect |
| App state | Foreground, background, locked, interruption, process restoration |
| Content | Short/long prose, dialogue, numbers, abbreviations, Unicode, chapter boundary |
| Controls | Play, pause, resume, stop, seek, rate, voice change |
| Accessibility | VoiceOver, TalkBack, large text, reduced motion |

## 6. Stop and pivot rules

- Stop Kokoro integration if no commercially distributable G2P can preserve source ranges.
- Reject a model variant if it breaches either section 1 memory gate or 0.75 sustained p95 RTF on either floor device.
- Whole-book pre-generation is a supported opt-in feature, reversing the earlier
  stop rule. It ships only with the three controls that rule was protecting:
  a compressed codec by default, a cost estimate shown before the job starts, and
  chunk audio addressed by model build, voice, speed, and text so a settings change
  invalidates cleanly and an interrupted job resumes. Never pre-generate silently,
  automatically, or on battery in a low-power state.
- Do not ship guessed word timing. Sentence highlighting is the mandatory degradation.
- KittenTTS Mini and Supertonic are excluded candidates. Kitten measured a worse
  real-time factor than Kokoro despite a smaller graph and produces no word timings;
  Supertonic was never measured. The retained Kitten result under
  `benchmarks/tts/results/` records that basis. If Kokoro fails a gate, the fallback is
  a commercially licensed engine or the system voice, never a smaller open model.
- Stop A2 at its time box. An expired G2P evaluation means licensing a commercial front
  end or shipping system TTS; it never means extending the box.

## 7. Inputs needed later from the product owner

1. A physical iOS floor device per section 5, plus confirmation of the exact iPhone and iPad SKUs to certify.
2. A physical Android floor device per section 5 (8 GB RAM, 2024-class SoC). Note that raising the floor above 6 GB excludes part of the 2023-24 midrange Android install base; this is a product decision, not a technical one.
3. Confirmation that GPL components remain unacceptable; this plan assumes they are.
4. Four to eight blind-test listeners, ideally regular audiobook listeners.
5. Confirmation of voice distribution. Recommendation: downloadable pack with immediate OS fallback.

## 8. Resume point

Resume with **Phase 0**. Both items are days of work, both run on desktop or a single
borrowed device, and together they decide whether Phases A through E are worth funding.
Do not start A2's 500-case suite or A3's mapping work until P1 has cleared.

Order:

1. **P1, early listening screen.** If Kokoro does not clearly beat the OS premium voices,
   take the P1 exit before spending anything on licensing or linguistics.
2. **P2, native feasibility spike**, in parallel with P1 once a floor device is on hand.
3. **A1** dependency inventory, after P1 clears.
4. **A2** G2P selection, with A1 supplying license verification and P1's Kokoro renders
   serving as the eSpeak audio-parity reference.
5. **A3** after A2 narrows to a shortlist.

Do not package mobile ONNX Runtime for release until Phase A has a viable exit path. P2
is exempt because its build is throwaway and never distributed.

The first deliverable is the P1 listening record. The second is
`docs/tts/DEPENDENCY_LICENSE_INVENTORY.md` plus a G2P evaluation covering licensing,
source mapping, pronunciation, phoneme parity, binary size, and effort.

## 9. Definition of complete

Neural TTS is complete only when one exact artifact set passes physical-device
performance, timing, endurance, commercial, accessibility, offline, and blind-listening
gates while both apps retain a verified system fallback.
