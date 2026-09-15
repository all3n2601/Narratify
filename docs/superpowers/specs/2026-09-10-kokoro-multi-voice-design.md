# Kokoro Multi-Voice Design

Status: approved, ready for implementation planning
Created: 2026-09-10
Scope: Android only. iOS catalog is deliberately left on its single locked `af_heart` entry.

## 1. Goal

Narratify ships one neural voice, `af_heart`. Add the five highest-graded remaining
American English Kokoro voices without downloading the 325 MB model graph more than once.

Voices to add, with overall grades from `hexgrad/Kokoro-82M` `VOICES.md`:

| Voice | Grade | Training duration | Size (bytes) | SHA-256 |
|---|---|---|---:|---|
| `af_heart` | A | — | 522,240 | `d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b` |
| `af_bella` | A- | HH hours | 522,240 | `f69d836209b78eb8c66e75e3cda491e26ea838a3674257e9d4e5703cbaf55c8b` |
| `af_nicole` | B- | HH hours | 522,240 | `cd2191ab31b914ed7b318416b0e4440fdf392ddad9106a060819aa600a64f59a` |
| `am_michael` | C+ | H hours | 522,240 | `1d1f21dd8da39c30705cd4c75d039d265e9bc4a2a93ed09bc9e1b1225eb95ba1` |
| `am_fenrir` | C+ | H hours | 522,240 | `c27989f741f7ee34d273a39d8a595cc0837d35f5ced9a29b7cc162614616df43` |
| `am_puck` | C+ | H hours | 522,240 | `fcf73c989033e9233e0b98713eca600c8c74dcc1614b37009d5450ff4a2274a0` |

`af_heart` is already installed and its digest is already pinned in the repository. The
five new digests come from the HuggingFace LFS `oid` field of
`onnx-community/Kokoro-82M-v1.0-ONNX` at pinned commit `1939ad2a8e416c0acfeecc08a694d14ef25f2231`.
`af_heart`'s `oid` at that commit equals the digest already pinned in the repository, which
is what establishes that the `oid` is the file's SHA-256. Implementation must still download
each file once and compute the digest locally before writing it into the catalog; the pinned
value is a cross-check, not the source of truth.

British voices (`bf_*`, `bm_*`) are excluded. The phonemizer is CMUdict/ARPAbet, i.e.
American English, so a British voice would apply British timbre to American pronunciations.

## 2. Non-goals

- No change to the phonemizer, the ONNX session, or the streaming path.
- No change to the Gate 0 performance budgets.
- No iOS catalog change.
- No migration path for `schemaVersion` 1 packs already on disk. The app is pre-release
  behind Gate 0, so a stale v1 directory is ignored and cleaned, not upgraded.

## 3. Pack model

Two pack kinds share the existing storage root `filesDir/tts-models`.

**Model pack** — `app.narratify.kokoro.model` @ `1.0.0`, `kind = MODEL`, ~329 MB:

- `kokoro-v1.0.onnx` (325,505,369 B, `beb0d184…`)
- `cmudict.dict` (3,618,488 B, `81917843…`)
- `LICENSE.cmudict.txt` (1,754 B, `bd4ce8e4…`)

It has no `voiceId` and is never selectable in the UI.

**Voice pack** — `app.narratify.kokoro.en-us.<name>` @ `1.0.0`, `kind = VOICE`, 522,240 B:

- `<voiceId>.bin`
- `dependsOn = { packId: app.narratify.kokoro.model, packVersion: 1.0.0 }`

Manifest `schemaVersion` goes 1 → 2, adding `kind` and `dependsOn`. `NeuralVoicePackStore.parseAndVerify`
fails on any `schemaVersion` other than 2, and the existing
`runCatching { parseAndVerify(it) }.getOrNull()` scan already tolerates that — a v1 directory
is skipped, not fatal.

## 4. Dependency resolution without changing the runtime interface

`OnDeviceNeuralTtsRuntime.open(pack)` keeps its single-pack signature. Instead the store
composes the pair before any runtime sees it:

- `NeuralVoicePack` gains `dependency: NeuralVoicePack?`.
- `NeuralVoicePack` gains `fun file(relativePath: String): File` — looks in its own directory,
  then the dependency's, and throws `VoicePackValidationException` if neither declares that
  asset. Callers stop constructing `File(pack.directory, …)` by hand.
- `NeuralVoicePackVerifier.verify` verifies digests for the pack and, recursively, its
  dependency. A voice pack whose dependency fails verification fails as a unit.
- `NeuralVoicePackStore.parseAndVerify` resolves `dependsOn` by scanning the root for the
  matching packId+version. An unresolvable dependency is a verification failure, so a voice
  pack without its model pack is simply not installed as far as every consumer is concerned.

`KokoroOnnxRuntime` then changes in three places:

1. `File(pack.directory, MODEL_FILE)` → `pack.file(MODEL_FILE)`, same for the dictionary.
2. The `pack.voiceId == "af_heart"` clause in `supports()` is removed. The voice file is
   derived as `"${pack.voiceId}.bin"` and must be present via `pack.file`.
3. `registerIfApproved` takes the whole catalog and registers the model approval plus every
   approved voice approval in one `NeuralTtsRuntimeRegistry.register` call.

Unapproved voices are still impossible to activate: `NeuralTtsRuntimeRegistry.compatible`
requires an exact `ApprovedNeuralVoicePack.matches` before `supports()` is consulted, and
approvals are compiled into the app.

## 5. Approval records

`ApprovedNeuralVoicePack` gains `kind`. `NeuralVoicePackVerifier` requires a non-blank
`voiceId`/`voiceVersion` only when `kind == VOICE`; a `MODEL` pack must have both blank.
`matches` compares `kind` alongside the existing fields.

Seven compiled approvals: one model, six voices. The catalog validation in
`NeuralVoicePackInstaller.validateCatalog` keeps its existing rule that the entry's asset
set must equal the approval's asset set exactly, now applied per pack.

## 6. Catalog shape

```kotlin
object NarratifyNeuralVoiceCatalog {
    val kokoroModel: NeuralVoicePackCatalogEntry            // kind = MODEL
    val kokoroVoices: List<NeuralVoicePackCatalogEntry>     // heart, bella, nicole, michael, fenrir, puck
}
```

Catalog order is the display order and the `Automatic` preference order. Each voice entry
carries its own `displayName` ("Kokoro English · Bella"), `detail`, and attribution, and
declares the model dependency.

`NeuralVoicePackInstaller.install(voiceEntry)`:

1. Resolve the dependency entry from the catalog.
2. If the model pack is not installed and verified, install it first.
3. Install the voice pack.
4. Report progress across the union of both asset lists, so a first install reads as one
   ~329 MB operation rather than two.

Cancellation mid-model leaves the staging directory deleted by the existing
`catch (error: Throwable)` path; no voice pack is written, so nothing partial is selectable.

## 7. Removal: derived ref-count

No counter file. The model pack is removable exactly when no installed, verified voice pack
declares a dependency on it.

- `remove(voiceEntry)` deletes the voice directory, then re-scans; if no voice pack remains,
  it deletes the model directory too.
- The Voices screen also offers "Remove all offline voices", which removes every voice and
  then the model.

Deriving the count from what is on disk means there is no separate state that can drift out
of agreement with the filesystem.

## 8. Selection and routing

`VoiceSelection.NeuralVoice(packId)` is unchanged — a packId already identifies exactly one
voice, which is the payoff of one-pack-per-voice.

`VoiceRouter.resolve` changes its second parameter from `installedNeuralPack: NeuralVoicePack?`
to `installedNeuralPacks: List<NeuralVoicePack>`:

- `NeuralVoice(packId)` → the matching installed pack, else system fallback with the reason
  "The chosen neural voice is not installed — using a system voice".
- `Automatic` → the first installed pack in catalog order, else system fallback.
- `SystemVoice` → unchanged.

Because an unresolvable model dependency makes a voice pack fail verification, a voice whose
model pack was removed never appears in `installedNeuralPacks` and falls back for the same
stated reason. The debug page keeps explaining the choice in words.

`NeuralVoicePackStore` gains `installedVoices(): List<NeuralVoicePack>` in catalog-independent
form (packs with `kind == VOICE`), and `firstCompatible(languageTag)` keeps working for the
narration path.

## 9. Voices screen

The single neural card becomes a section of six, under the existing "Offline voices" header
and above "Installed system voices".

Each card shows the voice display name, its detail line, a status line, a primary action, and
a select radio:

| Condition | Status | Action |
|---|---|---|
| checking | "Checking installed packs…" | disabled "Checking…" |
| downloading | "Downloading voice and shared model — X of Y MB" (first) or "Downloading — X of Y MB" | "Cancel download" |
| installed | "Downloaded · offline" | "Remove voice" |
| not installed, model present | "0.5 MB download" | "Download" |
| not installed, model absent | "329 MB download · Wi-Fi recommended" | "Download" |
| `!canDownload` | "Awaiting licensing approval" | disabled "Download unavailable" |

Selecting an installed voice writes `VoiceSelection.NeuralVoice(packId)` and previews a line
of speech, matching the existing system-voice behaviour.

License notices open from the model pack directory, which is where `LICENSE.cmudict.txt`
lives, plus the selected voice's own attribution.

## 10. Licensing — requires project-owner authorization

`benchmarks/tts/license-inventory.pending.json` records project-owner authorization per
artifact and per documented synthetic voice identity. The existing `af_heart` row reads:

> "Project-owner authorization recorded 2026-09-10 for this exact artifact and digest,
> including its documented synthetic af_heart identity and attribution."

**Resolved 2026-09-10:** the project owner authorized all five remaining voices. Each row now
carries `commercial_conclusion: "approved"`, `redistributed: true`, and an authorization note
matching `af_heart`'s, and every catalog voice ships `AVAILABLE` with a compiled approval. The
paragraph below records the gate as it was designed, and as it still applies to any voice added
in future.

Implementation adds five analogous rows carrying each voice's URL, size, and digest, each
marked `"authorization_pending": true` with no authorization date. **The project owner must
authorize each of the five identities; implementation must not write an authorization record
on their behalf.** Until each row is authorized, that voice entry ships with
`releaseStatus = AWAITING_LICENSE_APPROVAL` and `approval = null`, which the existing
`canDownload` gate renders as a visible but locked card. `af_heart` stays `AVAILABLE`: splitting the model out changes its packId and asset
set, so it needs new approval records, but the underlying artifacts and digests are the same
ones already authorized on 2026-09-10, so no new owner authorization is required for it or
for the model pack.

`androidApp/src/main/assets/third_party/KOKORO_NOTICE.txt` and `LicenseNotices.kt` list all
six voices. All voice styles are Apache-2.0 from the same model and commit, so the SPDX id
and the Kokoro attribution text are unchanged per voice.

## 11. Testing

- **Installer** — model auto-installs before a voice; a second voice reuses the installed
  model and downloads only 522,240 B; removing the last voice removes the model; removing one
  of two voices keeps it; a wrong digest on either pack fails the whole install; cancellation
  mid-model leaves no voice pack.
- **Store** — v2 manifest parse; dependency composition; `file()` resolves across both
  directories and throws for an undeclared path; a v1 directory is skipped rather than fatal;
  a voice pack with a missing model pack fails verification.
- **Runtime** — `supports()` accepts each of the six approved voices and rejects an
  unapproved `voiceId` even when the files are present; registry registration covers all
  seven approvals.
- **Router** — multiple installed packs; explicit selection; `Automatic` uses catalog order;
  fallback when the selected voice or the model pack is absent.
- **Existing suites** — `VoiceSelectionTest`, `LicenseNoticesTest`,
  `NeuralVoicePackInstallerTest`, `NeuralVoicePackVerifierTest`, `TtsDiagnosticsTest` updated
  for the new shapes.

## 12. Risks

- `af_nicole` is reported to have a whispery/ASMR character. Audition it in the listening
  gate before its card ships unlocked; if it reads poorly for long-form narration, drop it
  rather than shipping a voice that sounds broken.
- Gate 0 benchmark results pin `voice_id: af_heart`. Every voice uses the same graph and the
  same style-tensor shape, so RTF and RSS results carry over. Record that reasoning in the
  results notes rather than leaving it implicit.
- Six voices multiply the licensing surface. The `authorization_pending` marker keeps an
  unauthorized voice locked by construction, so the failure mode is a greyed-out card, not an
  unlicensed download.
