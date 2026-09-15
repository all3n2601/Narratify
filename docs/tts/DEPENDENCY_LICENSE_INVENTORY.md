# Kokoro dependency and licensing decision record

Status: **approved for the exact Android artifacts below (project owner, 2026-09-10 UTC)**  
Scope: English-only, downloadable, on-device Kokoro voice pack. Android only — iOS stays locked
because it bundles no reviewed inference runtime.

This record is engineering evidence, not legal advice. The recorded approval is the project
owner's own decision on the pinned artifacts and digests listed here. The machine-readable source
is `benchmarks/tts/license-inventory.pending.json`. A production release must pass
`validate_license_inventory.py --require-approved` and retain the signed legal review.

## Proposed distribution boundary

The Android application binary contains every executable component: the Narratify adapter,
ONNX Runtime, the English pronunciation/source mapper, and audio playback code. The optional
download contains data only: the pinned ONNX graph, one pinned voice style, CMUdict, its exact
license notice, and a manifest. Remaining notices must ship in the approving app release. A pack containing a native
library, bytecode, JavaScript, Python, or a shell script is rejected before download or
activation. iOS keeps the same locked catalog entry but does not yet bundle a reviewed inference
runtime.

## Release components

| Component | Proposed artifact | Declared license | Current decision |
|---|---|---|---|
| Kokoro duration graph | `model-files-v1.1/kokoro-v1.0.onnx`, SHA-256 `beb0…df3a` | Apache-2.0 source weights; MIT exporter | Approved for this exact digest |
| Kokoro voice data | `onnx-community@1939ad2/voices/af_heart.bin`, SHA-256 `d583…8f0b` | Apache-2.0 declared upstream | Approved for this exact digest |
| ONNX Runtime Android | Maven `1.24.3` AAR, SHA-256 `6739…bce` | MIT | Approved; license and third-party notices bundled |
| English pronunciation data | `cmudict@7479086/cmudict.dict`, SHA-256 `8191…d22`; license SHA-256 `bd4c…542a` | BSD-2-Clause | Approved; notice ships in the pack and is shown in-app |
| English mapper and source ranges | Original Narratify Kotlin code | App-owned | Implemented and unit tested on Android |
| ONNX Runtime iOS | Not bundled | — | Blocking the iOS download |

## Explicit exclusions

`phonemizer`, `phonemizer-fork`, eSpeak NG, and `espeakng-loader` must not be present in the
application, its frameworks/native libraries, or a downloadable pack. Misaki's top-level
Apache-2.0 declaration does not approve its optional English dependency set. If Misaki code or
data is selected, every copied lexicon, fallback model, tokenizer, generated table, and
transitive dependency must be pinned and reviewed separately.

## Evidence required for approval

1. Counsel confirms redistribution of the exact ONNX conversion and voice file, including the
   upstream model-card and CC-BY attribution obligations.
2. Counsel confirms CMUdict redistribution and notice placement; release scanning demonstrates
   that no excluded GPL component or data entered the Android application or pack.
3. The complete runtime SBOM identifies versions, hashes, source URLs, licenses, notices, and
   modifications for every redistributed artifact.
4. Release binaries and staged voice packs are scanned; the prohibited-component check is clean.
5. The reviewer changes every redistributed component to `commercial_conclusion: approved`,
   records their identity and UTC review time, and changes the top-level status to
   `approved-commercial`.
6. A reviewed Android release adds the matching compiled approval record. iOS additionally needs
   its own reviewed runtime. Until then, each Kokoro card remains visible but locked.

## Recorded approval, 2026-09-10

The project owner authorized the exact Android distribution recorded above. Consequently:

- Every redistributed Android component is `approved` in the machine-readable inventory, and
  `validate_license_inventory.py --require-approved` passes.
- `NarratifyNeuralVoiceCatalog.kokoroEnglish` carries the compiled `ApprovedNeuralVoicePack`
  record, so Android shows an enabled Download action.
- The Kokoro NOTICE, Apache-2.0 text, ONNX Runtime MIT license, and ONNX Runtime third-party
  notices ship in `androidApp/src/main/assets/third_party/` and are displayed by
  Settings → Licenses & notices. CMUdict's license travels inside the verified pack and joins
  that screen once the pack is installed.
- Release scanning of the built APK finds no eSpeak, phonemizer, or other prohibited component;
  the only native libraries are ONNX Runtime's.
- iOS stays locked: it bundles no reviewed runtime, and this approval does not cover it.

## Product behavior after approval

The app downloads approximately 330 MB into staging, verifies HTTPS, byte counts, exact SHA-256
digests, manifest identity, and the app-owned approval, then atomically activates it in private
storage. Cancellation, corruption, insufficient space, or removal leaves the system voice
available. Downloaded executable code is never accepted.
