# Kokoro Multi-Voice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship five more American English Kokoro voices alongside `af_heart` by splitting the 325 MB model graph into its own downloadable pack that per-voice packs depend on.

**Architecture:** Voice packs (~510 KB each) declare a dependency on one shared model pack (~329 MB). `NeuralVoicePackStore` resolves that dependency and composes the pair before any runtime sees it, so `OnDeviceNeuralTtsRuntime.open(pack)` keeps its single-pack signature and `KokoroOnnxRuntime` reads assets through a new `pack.file(path)` accessor instead of `File(pack.directory, path)`. The model pack is deleted when the last voice depending on it is removed, derived from disk state rather than a stored counter.

**Tech Stack:** Kotlin, Android SDK (`:androidApp` module), ONNX Runtime, JUnit 4 + `kotlin.test`, Gradle.

**Spec:** [docs/superpowers/specs/2026-09-10-kokoro-multi-voice-design.md](../specs/2026-09-10-kokoro-multi-voice-design.md)

---

## Prerequisites

**This directory is not a git repository.** `git rev-parse --is-inside-work-tree` fails, and there is no `.git` directory (despite `.github/` existing). Before starting, either:

```bash
git init && git add -A && git commit -m "chore: baseline before Kokoro multi-voice work"
```

...or skip every "Commit" step in this plan. Do not silently invent version control — if you skip commits, say so when reporting completion.

**Test command used throughout:**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.SomeTest"
```

**Full suite:**

```bash
./gradlew :androidApp:testDebugUnitTest
```

## Deviation from the spec, recorded

Two points where the spec is followed by resolving its own tension, both deliberate:

1. Spec §8 says `NeuralVoicePackStore.firstCompatible(languageTag)` "keeps working for the narration path". After Task 8 moves all three of its call sites to the new list-returning `compatible(languageTag)`, `firstCompatible` has no callers, so Task 3 deletes it rather than leaving dead code.
2. Spec §5 says "seven compiled approvals: one model, six voices", while §10 says an unauthorized voice ships with `approval = null`. §10 wins — a compiled approval *is* the authorization, so writing one for a voice the owner has not authorized would defeat the gate. Only `af_heart` and the model pack carry approvals until the owner signs off; the catalog's `voice()` helper builds the remaining five automatically the moment their status flips to `AVAILABLE`.

Everything else follows the spec as written.

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `androidApp/src/test/kotlin/app/narratify/NeuralPackDependencyTest.kt` | Schema v2 pack composition: `kind` rules, `dependency`, `file()` resolution |

**Modified:**

| File | Change |
|---|---|
| `androidApp/src/main/kotlin/app/narratify/NeuralTtsRuntime.kt` | `NeuralPackKind`, `NeuralPackRef`, `NeuralVoicePack.dependsOn`/`dependency`/`file()`, per-kind verifier rules, `ApprovedNeuralVoicePack.kind`/`dependsOn` |
| `androidApp/src/main/kotlin/app/narratify/NeuralVoicePackStore.kt` | Parse schema v2, resolve `dependsOn`, `installedVoices()`, `installedModels()`, `compatible()` |
| `androidApp/src/main/kotlin/app/narratify/NeuralVoicePackInstaller.kt` | Catalog `kind`/`dependency`, dependency auto-install with combined progress, orphaned-model cleanup, manifest v2, six voice entries + model entry |
| `androidApp/src/main/kotlin/app/narratify/KokoroOnnxRuntime.kt` | Voice-agnostic `supports()`/`open()` via `pack.file`, catalog-wide `registerIfApproved` |
| `androidApp/src/main/kotlin/app/narratify/VoiceSelection.kt` | `VoiceRouter.resolve` takes a pack list plus a preference-rank function |
| `androidApp/src/main/kotlin/app/narratify/NarratifyApplication.kt` | Register the whole catalog |
| `androidApp/src/main/kotlin/app/narratify/AndroidReaderTtsController.kt` | Use `compatible()` list |
| `androidApp/src/main/kotlin/app/narratify/EpubNarration.kt` | Use `compatible()` list |
| `androidApp/src/main/kotlin/app/narratify/TtsBenchmark.kt` | Use `compatible()` list |
| `androidApp/src/main/kotlin/app/narratify/DestinationScreens.kt` | Six voice cards instead of one |
| `androidApp/src/main/assets/third_party/KOKORO_NOTICE.txt` | List all six voice styles |
| `benchmarks/tts/license-inventory.pending.json` | Five rows marked `authorization_pending` |
| `benchmarks/tts/results/*.json` | Note that results carry across voices |
| `androidApp/src/test/kotlin/app/narratify/NeuralVoicePackVerifierTest.kt` | Schema v2 fixtures, per-kind rules |
| `androidApp/src/test/kotlin/app/narratify/NeuralVoicePackInstallerTest.kt` | Dependency install, reuse, cleanup |
| `androidApp/src/test/kotlin/app/narratify/VoiceSelectionTest.kt` | List-based router |
| `androidApp/src/test/kotlin/app/narratify/LicenseNoticesTest.kt` | Unchanged assertions, verified still green |

---

### Task 1: Verify the five voice digests against the real files

The catalog pins a SHA-256 per asset and refuses to install anything that does not match, so a wrong digest here produces a voice that can never install. The spec's digests came from HuggingFace's LFS `oid` metadata; this task confirms them against the actual bytes.

**⚠️ This task downloads files (5 × 510 KB from huggingface.co). Ask the user for explicit approval before running it.** If they decline, stop and report that the catalog digests cannot be independently verified.

**Files:**
- No repository files change in this task. Work in the scratchpad.

- [ ] **Step 1: Download the five voice styles and compute digests**

```bash
mkdir -p /tmp/claude-501/-Users-all3n2601-Desktop-Personal-Narratify/e457c956-1e67-42b5-835e-aa7910b2491d/scratchpad/kokoro-voices && cd /tmp/claude-501/-Users-all3n2601-Desktop-Personal-Narratify/e457c956-1e67-42b5-835e-aa7910b2491d/scratchpad/kokoro-voices && for v in af_bella af_nicole am_michael am_fenrir am_puck; do curl -fsSL -o "$v.bin" "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/1939ad2a8e416c0acfeecc08a694d14ef25f2231/voices/$v.bin"; done && shasum -a 256 *.bin && wc -c *.bin
```

Expected: every file is exactly `522240` bytes and the digests are

```
f69d836209b78eb8c66e75e3cda491e26ea838a3674257e9d4e5703cbaf55c8b  af_bella.bin
cd2191ab31b914ed7b318416b0e4440fdf392ddad9106a060819aa600a64f59a  af_nicole.bin
c27989f741f7ee34d273a39d8a595cc0837d35f5ced9a29b7cc162614616df43  am_fenrir.bin
1d1f21dd8da39c30705cd4c75d039d265e9bc4a2a93ed09bc9e1b1225eb95ba1  am_michael.bin
fcf73c989033e9233e0b98713eca600c8c74dcc1614b37009d5450ff4a2274a0  am_puck.bin
```

- [ ] **Step 2: Stop if any digest differs**

If a digest or size does not match, do not edit the catalog. Report the mismatch with both values and stop — a mismatch means either the pinned commit moved or the download was tampered with, and neither is something to work around.

---

### Task 2: Pack kinds, dependencies, and `file()` resolution

**Files:**
- Modify: `androidApp/src/main/kotlin/app/narratify/NeuralTtsRuntime.kt:13-30` (`NeuralVoicePack`), `:76-92` (`ApprovedNeuralVoicePack`), `:120-160` (`NeuralVoicePackVerifier.verify`)
- Test: `androidApp/src/test/kotlin/app/narratify/NeuralPackDependencyTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `androidApp/src/test/kotlin/app/narratify/NeuralPackDependencyTest.kt`:

```kotlin
package app.narratify

import com.narratify.domain.PcmEncoding
import com.narratify.domain.PcmFormat
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class NeuralPackDependencyTest {
    @Test
    fun `a voice pack resolves assets from itself and from its model pack`() {
        val packs = fixture()

        assertEquals("voice-data", packs.voice.file("af_test.bin").readText())
        assertEquals("model-data", packs.voice.file("kokoro-v1.0.onnx").readText())
    }

    @Test
    fun `an undeclared asset path is refused instead of silently missing`() {
        val packs = fixture()

        assertFailsWith<VoicePackValidationException> { packs.voice.file("not-declared.bin") }
    }

    @Test
    fun `an intact voice plus model pair verifies`() {
        val packs = fixture()

        NeuralVoicePackVerifier.verify(packs.voice)
    }

    @Test
    fun `a voice pack without its resolved model pack is refused`() {
        val packs = fixture()

        assertFailsWith<VoicePackValidationException> {
            NeuralVoicePackVerifier.verify(packs.voice.copy(dependency = null))
        }
    }

    @Test
    fun `a voice pack whose resolved model pack is the wrong one is refused`() {
        val packs = fixture()
        val impostor = packs.model.copy(packId = "app.narratify.kokoro.other")

        assertFailsWith<VoicePackValidationException> {
            NeuralVoicePackVerifier.verify(packs.voice.copy(dependency = impostor))
        }
    }

    @Test
    fun `a model pack must not claim a voice identity`() {
        val packs = fixture()

        assertFailsWith<VoicePackValidationException> {
            NeuralVoicePackVerifier.verify(packs.model.copy(voiceId = "af_test", voiceVersion = "1.0.0"))
        }
    }

    @Test
    fun `a voice pack with a blank voice identity is refused`() {
        val packs = fixture()

        assertFailsWith<VoicePackValidationException> {
            NeuralVoicePackVerifier.verify(packs.voice.copy(voiceId = ""))
        }
    }

    @Test
    fun `tampered model bytes fail through the voice pack that depends on them`() {
        val packs = fixture()
        File(packs.model.directory, "kokoro-v1.0.onnx").appendText("tampered")

        assertFailsWith<VoicePackValidationException> { NeuralVoicePackVerifier.verify(packs.voice) }
    }

    private fun fixture(): Packs {
        val root = createTempDirectory("pack-root").toFile().apply { deleteOnExit() }
        val modelDirectory = File(root, "model").apply { mkdirs() }
        val voiceDirectory = File(root, "voice").apply { mkdirs() }
        val modelFile = File(modelDirectory, "kokoro-v1.0.onnx").apply { writeText("model-data") }
        val dictionaryFile = File(modelDirectory, "cmudict.dict").apply { writeText("dictionary-data") }
        val voiceFile = File(voiceDirectory, "af_test.bin").apply { writeText("voice-data") }

        val model = NeuralVoicePack(
            directory = modelDirectory,
            schemaVersion = 2,
            kind = NeuralPackKind.MODEL,
            packId = "app.narratify.kokoro.model",
            packVersion = "1.0.0",
            runtimeId = "narratify-kokoro-onnx",
            modelId = "kokoro-82m-v1.0-fp32-duration",
            modelVersion = "model-files-v1.1",
            voiceId = "",
            voiceVersion = "",
            languageTags = setOf("en-US"),
            licenseSpdxId = "Apache-2.0",
            commercialUseAllowed = true,
            attribution = "Test only",
            pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
            assets = listOf(
                NeuralModelAsset("kokoro-v1.0.onnx", modelFile.length(), sha256(modelFile)),
                NeuralModelAsset("cmudict.dict", dictionaryFile.length(), sha256(dictionaryFile)),
            ),
        )
        val voice = NeuralVoicePack(
            directory = voiceDirectory,
            schemaVersion = 2,
            kind = NeuralPackKind.VOICE,
            packId = "app.narratify.kokoro.en-us.test",
            packVersion = "1.0.0",
            runtimeId = "narratify-kokoro-onnx",
            modelId = "kokoro-82m-v1.0-fp32-duration",
            modelVersion = "model-files-v1.1",
            voiceId = "af_test",
            voiceVersion = "af-test-1939ad2",
            languageTags = setOf("en-US"),
            licenseSpdxId = "Apache-2.0",
            commercialUseAllowed = true,
            attribution = "Test only",
            pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
            assets = listOf(NeuralModelAsset("af_test.bin", voiceFile.length(), sha256(voiceFile))),
            dependsOn = NeuralPackRef("app.narratify.kokoro.model", "1.0.0"),
            dependency = model,
        )
        return Packs(model, voice)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private data class Packs(val model: NeuralVoicePack, val voice: NeuralVoicePack)
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.NeuralPackDependencyTest"
```

Expected: compilation failure — `NeuralPackKind`, `NeuralPackRef`, the `kind`/`dependsOn`/`dependency` parameters, and `file()` do not exist yet.

- [ ] **Step 3: Add the types and the accessor**

In `NeuralTtsRuntime.kt`, above `NeuralVoicePack`:

```kotlin
/**
 * A model pack carries the shared graph and dictionary; a voice pack carries one style and names
 * the model pack it needs. Splitting them keeps the 325 MB graph to a single download.
 */
enum class NeuralPackKind { MODEL, VOICE }

data class NeuralPackRef(val packId: String, val packVersion: String)
```

Replace the `NeuralVoicePack` declaration with:

```kotlin
/** A fully verified, commercially distributable model pack already present on disk. */
data class NeuralVoicePack(
    val directory: File,
    val schemaVersion: Int,
    val kind: NeuralPackKind,
    val packId: String,
    val packVersion: String,
    val runtimeId: String,
    val modelId: String,
    val modelVersion: String,
    val voiceId: String,
    val voiceVersion: String,
    val languageTags: Set<String>,
    val licenseSpdxId: String,
    val commercialUseAllowed: Boolean,
    val attribution: String?,
    val pcmFormat: PcmFormat,
    val assets: List<NeuralModelAsset>,
    val dependsOn: NeuralPackRef? = null,
    /** Resolved by the store, never by a runtime, so adapters keep a single-pack signature. */
    val dependency: NeuralVoicePack? = null,
) {
    /**
     * Assets may live in this pack or in its model pack. Resolving by declared path rather than by
     * directory means a runtime cannot read a file the manifest never listed and the verifier
     * therefore never checked.
     */
    fun file(relativePath: String): File {
        assets.firstOrNull { it.relativePath == relativePath }
            ?.let { return File(directory, it.relativePath) }
        dependency?.let { pack ->
            pack.assets.firstOrNull { it.relativePath == relativePath }
                ?.let { return File(pack.directory, it.relativePath) }
        }
        throw VoicePackValidationException("This voice pack does not declare the asset $relativePath")
    }
}
```

`VoicePackValidationException` is declared further down the same file, which is fine in Kotlin.

- [ ] **Step 4: Add per-kind verifier rules**

In `NeuralVoicePackVerifier.verify`, change the schema check and replace the single voice-identity line.

Change:

```kotlin
        if (pack.schemaVersion != 1) fail("Unsupported voice-pack schema ${pack.schemaVersion}")
```

to:

```kotlin
        if (pack.schemaVersion != 2) fail("Unsupported voice-pack schema ${pack.schemaVersion}")
```

Replace:

```kotlin
        if (pack.voiceId.isBlank() || pack.voiceVersion.isBlank()) fail("Voice identity is incomplete")
```

with:

```kotlin
        when (pack.kind) {
            NeuralPackKind.VOICE -> {
                if (pack.voiceId.isBlank() || pack.voiceVersion.isBlank()) fail("Voice identity is incomplete")
                val declared = pack.dependsOn ?: fail("A voice pack must declare the model pack it needs")
                val resolved = pack.dependency ?: fail("The model pack for this voice is not installed")
                if (resolved.kind != NeuralPackKind.MODEL) fail("A voice pack may only depend on a model pack")
                if (resolved.packId != declared.packId || resolved.packVersion != declared.packVersion) {
                    fail("The resolved model pack does not match the declared dependency")
                }
                verify(resolved)
            }

            NeuralPackKind.MODEL -> {
                if (pack.voiceId.isNotBlank() || pack.voiceVersion.isNotBlank()) {
                    fail("A model pack must not claim a voice identity")
                }
                if (pack.dependsOn != null || pack.dependency != null) {
                    fail("A model pack must not depend on another pack")
                }
            }
        }
```

The recursion terminates because a `MODEL` pack is forbidden from having a dependency.

- [ ] **Step 5: Extend the approval record**

Replace `ApprovedNeuralVoicePack` with:

```kotlin
/**
 * App-owned review record compiled with a runtime adapter. A pack-authored license claim alone
 * is never trusted; every identity and asset digest must match this record exactly.
 */
data class ApprovedNeuralVoicePack(
    val packId: String,
    val packVersion: String,
    val kind: NeuralPackKind,
    val runtimeId: String,
    val modelId: String,
    val modelVersion: String,
    val voiceId: String,
    val voiceVersion: String,
    val licenseSpdxId: String,
    val assets: Set<NeuralModelAsset>,
    /** Pinned so an approved voice cannot be pointed at an unapproved model pack. */
    val dependsOn: NeuralPackRef? = null,
) {
    internal fun matches(pack: NeuralVoicePack): Boolean =
        pack.commercialUseAllowed &&
            pack.packId == packId && pack.packVersion == packVersion && pack.kind == kind &&
            pack.runtimeId == runtimeId && pack.modelId == modelId && pack.modelVersion == modelVersion &&
            pack.voiceId == voiceId && pack.voiceVersion == voiceVersion &&
            pack.licenseSpdxId == licenseSpdxId && pack.assets.toSet() == assets &&
            pack.dependsOn == dependsOn
}
```

- [ ] **Step 6: Run the new test to verify it passes**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.NeuralPackDependencyTest"
```

Expected: PASS, 8 tests.

- [ ] **Step 7: Commit**

```bash
git add androidApp/src/main/kotlin/app/narratify/NeuralTtsRuntime.kt androidApp/src/test/kotlin/app/narratify/NeuralPackDependencyTest.kt && git commit -m "feat: add pack kinds and model-pack dependencies to the neural pack schema"
```

---

### Task 3: Store parses schema v2 and resolves dependencies

**Files:**
- Modify: `androidApp/src/main/kotlin/app/narratify/NeuralVoicePackStore.kt` (whole file)
- Test: `androidApp/src/test/kotlin/app/narratify/NeuralPackDependencyTest.kt` (add cases)

- [ ] **Step 1: Write the failing test**

Append these tests inside `NeuralPackDependencyTest`, before the private helpers:

```kotlin
    @Test
    fun `the store composes an installed voice with its installed model pack`() {
        val root = createTempDirectory("store-root").toFile().apply { deleteOnExit() }
        writeInstalledPair(root)
        val store = NeuralVoicePackStore(root)

        val voices = store.installedVoices()

        assertEquals(1, voices.size)
        assertEquals("af_test", voices.single().voiceId)
        assertEquals("model-data", voices.single().file("kokoro-v1.0.onnx").readText())
        assertEquals(1, store.installedModels().size)
    }

    @Test
    fun `a voice whose model pack is missing is not reported as installed`() {
        val root = createTempDirectory("store-root").toFile().apply { deleteOnExit() }
        writeInstalledPair(root)
        File(root, "app.narratify.kokoro.model-1.0.0").deleteRecursively()
        val store = NeuralVoicePackStore(root)

        assertEquals(emptyList(), store.installedVoices())
    }

    @Test
    fun `a schema version 1 directory is skipped instead of crashing the scan`() {
        val root = createTempDirectory("store-root").toFile().apply { deleteOnExit() }
        writeInstalledPair(root)
        val stale = File(root, "legacy-pack").apply { mkdirs() }
        File(stale, "manifest.json").writeText("""{"schemaVersion":1,"packId":"legacy"}""")
        val store = NeuralVoicePackStore(root)

        assertEquals(1, store.installedVoices().size)
    }

    /** Writes a model pack and a voice pack to [root] exactly as the installer would. */
    private fun writeInstalledPair(root: File) {
        val modelDirectory = File(root, "app.narratify.kokoro.model-1.0.0").apply { mkdirs() }
        val voiceDirectory = File(root, "app.narratify.kokoro.en-us.test-1.0.0").apply { mkdirs() }
        val modelFile = File(modelDirectory, "kokoro-v1.0.onnx").apply { writeText("model-data") }
        val voiceFile = File(voiceDirectory, "af_test.bin").apply { writeText("voice-data") }
        File(modelDirectory, "manifest.json").writeText(
            """
            {"schemaVersion":2,"kind":"MODEL","packId":"app.narratify.kokoro.model","packVersion":"1.0.0",
             "runtimeId":"narratify-kokoro-onnx","modelId":"kokoro-82m-v1.0-fp32-duration",
             "modelVersion":"model-files-v1.1","voiceId":"","voiceVersion":"","languageTags":["en-US"],
             "license":{"spdxId":"Apache-2.0","commercialUseAllowed":true,"attribution":"Test only"},
             "audio":{"sampleRateHz":24000,"channelCount":1,"encoding":"SIGNED_INT_16_LE"},
             "assets":[{"path":"kokoro-v1.0.onnx","sizeBytes":${modelFile.length()},"sha256":"${sha256(modelFile)}"}]}
            """.trimIndent(),
        )
        File(voiceDirectory, "manifest.json").writeText(
            """
            {"schemaVersion":2,"kind":"VOICE","packId":"app.narratify.kokoro.en-us.test","packVersion":"1.0.0",
             "runtimeId":"narratify-kokoro-onnx","modelId":"kokoro-82m-v1.0-fp32-duration",
             "modelVersion":"model-files-v1.1","voiceId":"af_test","voiceVersion":"af-test-1939ad2",
             "languageTags":["en-US"],
             "license":{"spdxId":"Apache-2.0","commercialUseAllowed":true,"attribution":"Test only"},
             "audio":{"sampleRateHz":24000,"channelCount":1,"encoding":"SIGNED_INT_16_LE"},
             "dependsOn":{"packId":"app.narratify.kokoro.model","packVersion":"1.0.0"},
             "assets":[{"path":"af_test.bin","sizeBytes":${voiceFile.length()},"sha256":"${sha256(voiceFile)}"}]}
            """.trimIndent(),
        )
    }
```

`NeuralVoicePackStore(root)` resolves to the existing `internal constructor(root: File, testOnly: Unit = Unit)`.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.NeuralPackDependencyTest"
```

Expected: compilation failure — `installedVoices()` and `installedModels()` do not exist.

- [ ] **Step 3: Rewrite the store**

Replace the body of `NeuralVoicePackStore` (keep the two public constructors) with:

```kotlin
/** Reads only verified packs from app-private storage. */
class NeuralVoicePackStore private constructor(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "tts-models"))

    internal constructor(root: File, testOnly: Unit = Unit) : this(root)

    /** Every installed voice, each already composed with its verified model pack. */
    fun installedVoices(): List<NeuralVoicePack> = scan(resolveDependency = true)
        .filter { it.kind == NeuralPackKind.VOICE }
        .sortedByDescending { it.directory.lastModified() }

    fun installedModels(): List<NeuralVoicePack> = scan(resolveDependency = false)
        .filter { it.kind == NeuralPackKind.MODEL }

    /**
     * Every installed voice for [languageTag] that a registered, approved runtime can open, in
     * install-recency order. Callers apply their own preference ordering; the store stays free of
     * catalog knowledge so it can be tested without one.
     */
    fun compatible(languageTag: String): List<Pair<NeuralVoicePack, OnDeviceNeuralTtsRuntime>> {
        val requested = languageTag.substringBefore('-').lowercase()
        return installedVoices()
            .filter { pack -> pack.languageTags.any { it.substringBefore('-').lowercase() == requested } }
            .mapNotNull { pack -> NeuralTtsRuntimeRegistry.compatible(pack)?.let { pack to it } }
    }

    internal fun installed(packId: String, packVersion: String): NeuralVoicePack? =
        scan(resolveDependency = true).firstOrNull { it.packId == packId && it.packVersion == packVersion }

    internal fun parseAndVerify(directory: File): NeuralVoicePack =
        parseAndVerify(directory, resolveDependency = true)

    private fun scan(resolveDependency: Boolean): List<NeuralVoicePack> =
        root.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.take(MAX_PACKS_TO_SCAN)
            ?.mapNotNull { runCatching { parseAndVerify(it, resolveDependency) }.getOrNull() }
            ?.toList()
            .orEmpty()

    private fun parseAndVerify(directory: File, resolveDependency: Boolean): NeuralVoicePack {
        val parsed = parse(directory)
        val dependency = parsed.dependsOn
            ?.takeIf { resolveDependency }
            ?.let { reference -> findModel(reference) }
        return NeuralVoicePackVerifier.verify(parsed.copy(dependency = dependency))
    }

    /** Only model packs are searched, so dependency resolution can never recurse. */
    private fun findModel(reference: NeuralPackRef): NeuralVoicePack? =
        root.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.take(MAX_PACKS_TO_SCAN)
            ?.mapNotNull { runCatching { parse(it) }.getOrNull() }
            ?.filter { it.kind == NeuralPackKind.MODEL }
            ?.firstOrNull { it.packId == reference.packId && it.packVersion == reference.packVersion }
            ?.let { NeuralVoicePackVerifier.verify(it) }

    private fun parse(directory: File): NeuralVoicePack {
        val manifest = File(directory, "manifest.json")
        require(manifest.isFile && manifest.length() in 1..MAX_MANIFEST_BYTES) { "Invalid voice-pack manifest" }
        val json = JSONObject(manifest.readText(Charsets.UTF_8))
        val license = json.getJSONObject("license")
        val audio = json.getJSONObject("audio")
        val languages = json.getJSONArray("languageTags")
        val assetsJson = json.getJSONArray("assets")
        val assets = buildList {
            require(assetsJson.length() in 1..MAX_ASSETS) { "Invalid model asset count" }
            repeat(assetsJson.length()) { index ->
                val item = assetsJson.getJSONObject(index)
                add(NeuralModelAsset(item.getString("path"), item.getLong("sizeBytes"), item.getString("sha256")))
            }
        }
        return NeuralVoicePack(
            directory = directory,
            schemaVersion = json.getInt("schemaVersion"),
            kind = NeuralPackKind.valueOf(json.getString("kind")),
            packId = json.getString("packId"),
            packVersion = json.getString("packVersion"),
            runtimeId = json.getString("runtimeId"),
            modelId = json.getString("modelId"),
            modelVersion = json.getString("modelVersion"),
            voiceId = json.getString("voiceId"),
            voiceVersion = json.getString("voiceVersion"),
            languageTags = buildSet { repeat(languages.length()) { add(languages.getString(it)) } },
            licenseSpdxId = license.getString("spdxId"),
            commercialUseAllowed = license.optBoolean("commercialUseAllowed", false),
            attribution = license.optString("attribution").ifBlank { null },
            pcmFormat = PcmFormat(
                sampleRateHz = audio.getInt("sampleRateHz"),
                channelCount = audio.getInt("channelCount"),
                encoding = PcmEncoding.valueOf(audio.getString("encoding")),
            ),
            assets = assets,
            dependsOn = json.optJSONObject("dependsOn")?.let {
                NeuralPackRef(it.getString("packId"), it.getString("packVersion"))
            },
        )
    }

    private companion object {
        const val MAX_PACKS_TO_SCAN = 16
        const val MAX_ASSETS = 32
        const val MAX_MANIFEST_BYTES = 64L * 1024L
    }
}
```

`firstCompatible` is gone; Task 8 moves its callers to `compatible`.

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.NeuralPackDependencyTest"
```

Expected: PASS, 11 tests. The main source set will not compile yet if you run the whole suite — three call sites still use `firstCompatible` and are fixed in Task 8. Run only this test class until then.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/app/narratify/NeuralVoicePackStore.kt androidApp/src/test/kotlin/app/narratify/NeuralPackDependencyTest.kt && git commit -m "feat: resolve model-pack dependencies in the voice pack store"
```

---

### Task 4: Installer handles the model dependency

**Files:**
- Modify: `androidApp/src/main/kotlin/app/narratify/NeuralVoicePackInstaller.kt:27-70` (`NeuralPackDownloadAsset`, `NeuralVoicePackCatalogEntry`), `:220-300` (`install`, `remove`), `:302-345` (`validateCatalog`, `writeManifest`)
- Test: `androidApp/src/test/kotlin/app/narratify/NeuralVoicePackInstallerTest.kt`

- [ ] **Step 1: Write the failing test**

In `NeuralVoicePackInstallerTest`, replace the `installsOnlyAnApprovedDataOnlyPackAndCanRemoveIt` test and the two private fixture helpers, and add the dependency tests. The full replacement for everything below `rejectsExecutableAssetsEvenWhenTheirHashIsApproved`:

```kotlin
    @Test
    fun `installing a voice installs its model pack first and reuses it for the next voice`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
            "af_two.bin" to "two-data".encodeToByteArray(),
        )
        val downloaded = mutableListOf<String>()
        val installer = NeuralVoicePackInstaller(root, countingSource(bytes, downloaded))
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)
        val two = voiceFixture("two", "af_two", bytes, model)

        installer.install(one)
        downloaded.clear()
        installer.install(two)

        assertTrue(installer.installed(one))
        assertTrue(installer.installed(two))
        assertTrue(installer.installed(model))
        assertEquals(listOf("af_two.bin"), downloaded)
    }

    @Test
    fun `progress for a first install spans the model and the voice`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
        )
        val installer = NeuralVoicePackInstaller(root, fixtureSource(bytes))
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)
        val expectedTotal = model.sizeBytes + one.sizeBytes
        val totals = mutableSetOf<Long>()

        installer.install(one, onProgress = { _, total -> totals.add(total) })

        assertEquals(setOf(expectedTotal), totals)
    }

    @Test
    fun `removing the last voice removes the shared model pack`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
            "af_two.bin" to "two-data".encodeToByteArray(),
        )
        val installer = NeuralVoicePackInstaller(root, fixtureSource(bytes))
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)
        val two = voiceFixture("two", "af_two", bytes, model)
        installer.install(one)
        installer.install(two)

        assertTrue(installer.remove(one))
        assertTrue(installer.installed(model))

        assertTrue(installer.remove(two))
        assertFalse(installer.installed(model))
    }

    @Test
    fun `a voice cannot be installed against an unapproved model pack`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
        )
        val installer = NeuralVoicePackInstaller(root, fixtureSource(bytes))
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)
        val mismatched = one.copy(approval = one.approval?.copy(dependsOn = NeuralPackRef("app.narratify.other", "1.0.0")))

        assertFailsWith<NeuralVoicePackInstallException> { installer.install(mismatched) }
    }

    @Test
    fun `cancelling during the model download leaves no voice pack behind`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
        )
        val installer = NeuralVoicePackInstaller(root, fixtureSource(bytes))
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)

        assertFailsWith<Throwable> { installer.install(one, isCancelled = { true }) }

        assertFalse(installer.installed(one))
        assertFalse(installer.installed(model))
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith("-installing") })
    }

    private fun fixtureSource(files: Map<String, ByteArray>) = NeuralPackAssetSource { asset, destination, onBytes, isCancelled ->
        if (isCancelled()) throw InterruptedException("cancelled")
        val content = files.getValue(asset.relativePath)
        destination.parentFile?.mkdirs()
        destination.writeBytes(content)
        onBytes(content.size.toLong())
    }

    private fun countingSource(files: Map<String, ByteArray>, downloaded: MutableList<String>) =
        NeuralPackAssetSource { asset, destination, onBytes, _ ->
            downloaded.add(asset.relativePath)
            val content = files.getValue(asset.relativePath)
            destination.parentFile?.mkdirs()
            destination.writeBytes(content)
            onBytes(content.size.toLong())
        }

    private fun modelFixture(files: Map<String, ByteArray>): NeuralVoicePackCatalogEntry {
        val assets = downloadAssets(files)
        val approval = ApprovedNeuralVoicePack(
            packId = "app.narratify.test.model",
            packVersion = "1.0.0",
            kind = NeuralPackKind.MODEL,
            runtimeId = "test-runtime",
            modelId = "test-model",
            modelVersion = "1.0.0",
            voiceId = "",
            voiceVersion = "",
            licenseSpdxId = "Apache-2.0",
            assets = assets.map(NeuralPackDownloadAsset::modelAsset).toSet(),
        )
        return entry("Test model", approval, assets, dependency = null)
    }

    private fun voiceFixture(
        name: String,
        voiceId: String,
        files: Map<String, ByteArray>,
        model: NeuralVoicePackCatalogEntry,
    ): NeuralVoicePackCatalogEntry {
        val assets = downloadAssets(files.filterKeys { it == "$voiceId.bin" })
        val approval = ApprovedNeuralVoicePack(
            packId = "app.narratify.test.voice.$name",
            packVersion = "1.0.0",
            kind = NeuralPackKind.VOICE,
            runtimeId = "test-runtime",
            modelId = "test-model",
            modelVersion = "1.0.0",
            voiceId = voiceId,
            voiceVersion = "$voiceId-1.0.0",
            licenseSpdxId = "Apache-2.0",
            assets = assets.map(NeuralPackDownloadAsset::modelAsset).toSet(),
            dependsOn = NeuralPackRef(model.packId, model.packVersion),
        )
        return entry("Test voice $name", approval, assets, dependency = model)
    }

    private fun downloadAssets(files: Map<String, ByteArray>) = files.map { (path, bytes) ->
        NeuralPackDownloadAsset(path, "https://example.test/$path", bytes.size.toLong(), sha256(bytes))
    }

    private fun entry(
        displayName: String,
        approval: ApprovedNeuralVoicePack,
        assets: List<NeuralPackDownloadAsset>,
        dependency: NeuralVoicePackCatalogEntry?,
    ) = NeuralVoicePackCatalogEntry(
        displayName = displayName,
        detail = "Test only",
        schemaVersion = 2,
        kind = approval.kind,
        packId = approval.packId,
        packVersion = approval.packVersion,
        runtimeId = approval.runtimeId,
        modelId = approval.modelId,
        modelVersion = approval.modelVersion,
        voiceId = approval.voiceId,
        voiceVersion = approval.voiceVersion,
        languageTags = setOf("en-US"),
        licenseSpdxId = approval.licenseSpdxId,
        attribution = "Test",
        pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
        assets = assets,
        releaseStatus = NeuralPackReleaseStatus.AVAILABLE,
        runtimeBundled = true,
        approval = approval,
        dependency = dependency,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
```

Also update the two surviving tests at the top of the class. Replace:

```kotlin
    @Test
    fun `approvedAndroidCandidateIsDownloadableAndMatchesItsReviewRecord`() {
        val entry = NarratifyNeuralVoiceCatalog.kokoroEnglish
```

with (the old test name was `approvedAndroidCandidateIsDownloadableAndMatchesItsReviewRecord`, without backticks):

```kotlin
    @Test
    fun approvedAndroidCandidateIsDownloadableAndMatchesItsReviewRecord() {
        val entry = NarratifyNeuralVoiceCatalog.kokoroVoices.first()
```

and change `rejectsDownloadedBytesThatDoNotMatchTheApprovedHash` / `rejectsExecutableAssetsEvenWhenTheirHashIsApproved` to build their entries with `modelFixture(...)` instead of `approvedFixture(...)`:

```kotlin
    @Test
    fun rejectsDownloadedBytesThatDoNotMatchTheApprovedHash() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf("kokoro-v1.0.onnx" to "model-data".encodeToByteArray())
        val entry = modelFixture(bytes)
        val tampered = mapOf("kokoro-v1.0.onnx" to "wrong-data".encodeToByteArray())
        val installer = NeuralVoicePackInstaller(root, fixtureSource(tampered))

        assertFailsWith<Throwable> { installer.install(entry) }
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith("-installing") })
    }

    @Test
    fun rejectsExecutableAssetsEvenWhenTheirHashIsApproved() {
        val bytes = mapOf("runtime.so" to "native-code".encodeToByteArray())
        val entry = modelFixture(bytes)

        assertFailsWith<NeuralVoicePackInstallException> {
            NeuralVoicePackInstaller(createTempDirectory("voice-pack-root").toFile(), fixtureSource(bytes)).install(entry)
        }
    }
```

Add `import kotlin.test.assertEquals` to the imports.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.NeuralVoicePackInstallerTest"
```

Expected: compilation failure — `kind` and `dependency` are not parameters of `NeuralVoicePackCatalogEntry`, and `kokoroVoices` does not exist.

- [ ] **Step 3: Extend the catalog entry type**

In `NeuralVoicePackInstaller.kt`, replace the `NeuralVoicePackCatalogEntry` declaration with:

```kotlin
data class NeuralVoicePackCatalogEntry(
    val displayName: String,
    val detail: String,
    val schemaVersion: Int,
    val kind: NeuralPackKind,
    val packId: String,
    val packVersion: String,
    val runtimeId: String,
    val modelId: String,
    val modelVersion: String,
    val voiceId: String,
    val voiceVersion: String,
    val languageTags: Set<String>,
    val licenseSpdxId: String,
    val attribution: String,
    val pcmFormat: PcmFormat,
    val assets: List<NeuralPackDownloadAsset>,
    val releaseStatus: NeuralPackReleaseStatus,
    /** True only in an app build that contains the matching reviewed native runtime/frontend. */
    val runtimeBundled: Boolean,
    /** This record must be added by a release after human legal review. */
    val approval: ApprovedNeuralVoicePack? = null,
    /** The model pack this voice needs. Null for a model pack itself. */
    val dependency: NeuralVoicePackCatalogEntry? = null,
) {
    /** This pack's own assets only. Use [NeuralVoicePackInstaller.pendingDownloadBytes] for a download estimate. */
    val sizeBytes: Long get() = assets.sumOf { it.sizeBytes }
    val dependsOn: NeuralPackRef? get() = dependency?.let { NeuralPackRef(it.packId, it.packVersion) }
    val canDownload: Boolean
        get() = releaseStatus == NeuralPackReleaseStatus.AVAILABLE && runtimeBundled && approval != null &&
            (dependency == null || dependency.canDownload)

    fun asPack(directory: File) = NeuralVoicePack(
        directory = directory,
        schemaVersion = schemaVersion,
        kind = kind,
        packId = packId,
        packVersion = packVersion,
        runtimeId = runtimeId,
        modelId = modelId,
        modelVersion = modelVersion,
        voiceId = voiceId,
        voiceVersion = voiceVersion,
        languageTags = languageTags,
        licenseSpdxId = licenseSpdxId,
        commercialUseAllowed = canDownload,
        attribution = attribution,
        pcmFormat = pcmFormat,
        assets = assets.map(NeuralPackDownloadAsset::modelAsset),
        dependsOn = dependsOn,
    )
}
```

- [ ] **Step 4: Teach the installer about dependencies**

Replace `install` and `remove` in `NeuralVoicePackInstaller` with:

```kotlin
    /** Bytes still to download for [entry], including its model pack when that is not installed. */
    fun pendingDownloadBytes(entry: NeuralVoicePackCatalogEntry): Long {
        val dependency = entry.dependency?.takeIf { !installed(it) }?.sizeBytes ?: 0L
        return dependency + entry.sizeBytes
    }

    fun install(
        entry: NeuralVoicePackCatalogEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): NeuralVoicePack {
        val total = pendingDownloadBytes(entry)
        var completed = 0L
        entry.dependency?.takeIf { !installed(it) }?.let { dependency ->
            installOne(dependency, completed, total, onProgress, isCancelled)
            completed += dependency.sizeBytes
        }
        return installOne(entry, completed, total, onProgress, isCancelled)
    }

    private fun installOne(
        entry: NeuralVoicePackCatalogEntry,
        alreadyCompleted: Long,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): NeuralVoicePack {
        val approval = entry.approval
            ?: throw NeuralVoicePackInstallException("This voice is awaiting commercial licensing approval")
        if (!entry.canDownload) throw NeuralVoicePackInstallException("This voice is not released for download")
        validateCatalog(entry)
        root.mkdirs()
        if (root.usableSpace < entry.sizeBytes + MIN_FREE_SPACE_AFTER_INSTALL) {
            throw NeuralVoicePackInstallException("Not enough free space to install this voice")
        }

        val directoryName = safeDirectoryName(entry)
        val target = child(directoryName)
        store.installed(entry.packId, entry.packVersion)?.let { existing ->
            if (approval.matches(existing)) return existing
        }
        val staging = child(".$directoryName-installing")
        deleteOwnedDirectory(staging)
        if (!staging.mkdirs()) throw NeuralVoicePackInstallException("Could not create voice-pack staging storage")

        return try {
            var downloaded = 0L
            var lastReported = 0L
            entry.assets.forEach { asset ->
                val output = File(staging, asset.relativePath).canonicalFile
                if (!output.path.startsWith(staging.canonicalPath + File.separator)) {
                    throw NeuralVoicePackInstallException("Voice-pack asset escapes staging storage")
                }
                source.download(asset, output, { count ->
                    downloaded += count
                    if (downloaded - lastReported >= PROGRESS_REPORT_BYTES || downloaded == entry.sizeBytes) {
                        lastReported = downloaded
                        onProgress(alreadyCompleted + downloaded, total)
                    }
                }, isCancelled)
                if (output.length() != asset.sizeBytes) {
                    throw NeuralVoicePackInstallException("Downloaded asset has the wrong size: ${asset.relativePath}")
                }
            }
            writeManifest(entry, staging)
            val verified = store.parseAndVerify(staging)
            if (!approval.matches(verified)) {
                throw NeuralVoicePackInstallException("Downloaded voice does not match the app-reviewed approval")
            }
            if (target.exists()) deleteOwnedDirectory(target)
            if (!staging.renameTo(target)) throw NeuralVoicePackInstallException("Could not activate the downloaded voice")
            store.parseAndVerify(target).also { onProgress(alreadyCompleted + entry.sizeBytes, total) }
        } catch (error: Throwable) {
            deleteOwnedDirectory(staging)
            throw error
        }
    }

    /**
     * Removing a voice also drops the shared model pack once nothing depends on it. The count is
     * derived from what is on disk, so there is no separate state that can disagree with it.
     */
    fun remove(entry: NeuralVoicePackCatalogEntry): Boolean {
        val installed = store.installed(entry.packId, entry.packVersion) ?: return false
        deleteOwnedDirectory(installed.directory)
        if (entry.kind == NeuralPackKind.VOICE) removeOrphanedModels()
        return true
    }

    private fun removeOrphanedModels() {
        val required = store.installedVoices().mapNotNull { it.dependsOn }.toSet()
        store.installedModels()
            .filterNot { NeuralPackRef(it.packId, it.packVersion) in required }
            .forEach { deleteOwnedDirectory(it.directory) }
    }
```

Note the staging verification: `store.parseAndVerify(staging)` resolves the voice pack's dependency from the real root, which is why the model pack must be installed first.

- [ ] **Step 5: Pin kind and dependency in catalog validation, and write them to the manifest**

In `validateCatalog`, replace the final approval check:

```kotlin
        if (entry.approval?.assets != entry.assets.map { it.modelAsset() }.toSet()) {
            throw NeuralVoicePackInstallException("Catalog assets do not match the app-owned approval")
        }
```

with:

```kotlin
        val approval = entry.approval
        if (approval?.assets != entry.assets.map { it.modelAsset() }.toSet()) {
            throw NeuralVoicePackInstallException("Catalog assets do not match the app-owned approval")
        }
        if (approval.kind != entry.kind || approval.dependsOn != entry.dependsOn) {
            throw NeuralVoicePackInstallException("Catalog pack kind or dependency does not match the app-owned approval")
        }
```

In `writeManifest`, add `kind` after `schemaVersion` and `dependsOn` after `languageTags`:

```kotlin
        val json = JSONObject()
            .put("schemaVersion", entry.schemaVersion)
            .put("kind", entry.kind.name)
            .put("packId", entry.packId)
            .put("packVersion", entry.packVersion)
            .put("runtimeId", entry.runtimeId)
            .put("modelId", entry.modelId)
            .put("modelVersion", entry.modelVersion)
            .put("voiceId", entry.voiceId)
            .put("voiceVersion", entry.voiceVersion)
            .put("languageTags", JSONArray(entry.languageTags.toList()))
        entry.dependsOn?.let { reference ->
            json.put("dependsOn", JSONObject().put("packId", reference.packId).put("packVersion", reference.packVersion))
        }
        json
            .put("license", JSONObject()
```

...leaving the rest of the chained calls as they are, and ending the expression with `File(directory, "manifest.json").writeText(json.toString(), Charsets.UTF_8)` as before.

- [ ] **Step 6: Run test to verify it passes**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.NeuralVoicePackInstallerTest"
```

Expected: still failing to compile until Task 5 replaces `NarratifyNeuralVoiceCatalog.kokoroEnglish` with `kokoroVoices`. Run Task 5, then return here; the two tasks land in one commit.

---

### Task 5: The catalog — one model pack, six voices

**Files:**
- Modify: `androidApp/src/main/kotlin/app/narratify/NeuralVoicePackInstaller.kt:72-150` (`NarratifyNeuralVoiceCatalog`)

- [ ] **Step 1: Replace the catalog object**

Replace all of `object NarratifyNeuralVoiceCatalog { ... }` with:

```kotlin
/**
 * The 325 MB graph and the CMU dictionary live in one model pack; each voice is a ~510 KB style
 * that names it. A voice ships locked until the project owner authorizes its exact artifact,
 * digest, and documented synthetic identity in benchmarks/tts/license-inventory.pending.json.
 */
object NarratifyNeuralVoiceCatalog {
    private const val VOICE_COMMIT = "1939ad2a8e416c0acfeecc08a694d14ef25f2231"
    private const val ATTRIBUTION =
        "Kokoro-82M by hexgrad (Apache-2.0). CMUdict Copyright (C) 1993-2015 Carnegie Mellon University (BSD-style license included in the model pack)."

    private val modelAssets = listOf(
        NeuralPackDownloadAsset(
            relativePath = "kokoro-v1.0.onnx",
            url = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/kokoro-v1.0.onnx",
            sizeBytes = 325_505_369,
            sha256 = "beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a",
        ),
        NeuralPackDownloadAsset(
            relativePath = "cmudict.dict",
            url = "https://raw.githubusercontent.com/cmusphinx/cmudict/74790861f652b15e4ac49015a90074ad62a27690/cmudict.dict",
            sizeBytes = 3_618_488,
            sha256 = "81917843c7f44ce2b094ac63873c2c7a4cf802040792c455ba3ca406891c3d22",
        ),
        NeuralPackDownloadAsset(
            relativePath = "LICENSE.cmudict.txt",
            url = "https://raw.githubusercontent.com/cmusphinx/cmudict/74790861f652b15e4ac49015a90074ad62a27690/LICENSE",
            sizeBytes = 1_754,
            sha256 = "bd4ce8e44170a5f9f481310ca85c51de3c4f851a65e679b40e603b143bd3542a",
        ),
    )

    val kokoroModel = NeuralVoicePackCatalogEntry(
        displayName = "Kokoro English · shared model",
        detail = "The speech model and pronunciation dictionary every Kokoro voice uses. Downloaded once.",
        schemaVersion = 2,
        kind = NeuralPackKind.MODEL,
        packId = "app.narratify.kokoro.model",
        packVersion = "1.0.0",
        runtimeId = "narratify-kokoro-onnx",
        modelId = "kokoro-82m-v1.0-fp32-duration",
        modelVersion = "model-files-v1.1",
        voiceId = "",
        voiceVersion = "",
        languageTags = setOf("en-US"),
        licenseSpdxId = "Apache-2.0",
        attribution = ATTRIBUTION,
        pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
        assets = modelAssets,
        releaseStatus = NeuralPackReleaseStatus.AVAILABLE,
        runtimeBundled = true,
        approval = ApprovedNeuralVoicePack(
            packId = "app.narratify.kokoro.model",
            packVersion = "1.0.0",
            kind = NeuralPackKind.MODEL,
            runtimeId = "narratify-kokoro-onnx",
            modelId = "kokoro-82m-v1.0-fp32-duration",
            modelVersion = "model-files-v1.1",
            voiceId = "",
            voiceVersion = "",
            licenseSpdxId = "Apache-2.0",
            assets = modelAssets.map(NeuralPackDownloadAsset::modelAsset).toSet(),
        ),
    )

    /**
     * Order is the display order and the Automatic preference order: highest overall grade in
     * hexgrad/Kokoro-82M VOICES.md first.
     */
    val kokoroVoices = listOf(
        voice(
            name = "heart",
            voiceId = "af_heart",
            displayName = "Kokoro English · Heart",
            detail = "American English, warm and even. Kokoro's highest-graded voice (A).",
            sha256 = "d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b",
            releaseStatus = NeuralPackReleaseStatus.AVAILABLE,
        ),
        voice(
            name = "bella",
            voiceId = "af_bella",
            displayName = "Kokoro English · Bella",
            detail = "American English, expressive. Kokoro's most-trained voice (A-).",
            sha256 = "f69d836209b78eb8c66e75e3cda491e26ea838a3674257e9d4e5703cbaf55c8b",
            releaseStatus = NeuralPackReleaseStatus.AWAITING_LICENSE_APPROVAL,
        ),
        voice(
            name = "nicole",
            voiceId = "af_nicole",
            displayName = "Kokoro English · Nicole",
            detail = "American English, soft and close-miked (B-).",
            sha256 = "cd2191ab31b914ed7b318416b0e4440fdf392ddad9106a060819aa600a64f59a",
            releaseStatus = NeuralPackReleaseStatus.AWAITING_LICENSE_APPROVAL,
        ),
        voice(
            name = "michael",
            voiceId = "am_michael",
            displayName = "Kokoro English · Michael",
            detail = "American English, steady male narration (C+).",
            sha256 = "1d1f21dd8da39c30705cd4c75d039d265e9bc4a2a93ed09bc9e1b1225eb95ba1",
            releaseStatus = NeuralPackReleaseStatus.AWAITING_LICENSE_APPROVAL,
        ),
        voice(
            name = "fenrir",
            voiceId = "am_fenrir",
            displayName = "Kokoro English · Fenrir",
            detail = "American English, deeper male narration (C+).",
            sha256 = "c27989f741f7ee34d273a39d8a595cc0837d35f5ced9a29b7cc162614616df43",
            releaseStatus = NeuralPackReleaseStatus.AWAITING_LICENSE_APPROVAL,
        ),
        voice(
            name = "puck",
            voiceId = "am_puck",
            displayName = "Kokoro English · Puck",
            detail = "American English, brighter male narration (C+).",
            sha256 = "fcf73c989033e9233e0b98713eca600c8c74dcc1614b37009d5450ff4a2274a0",
            releaseStatus = NeuralPackReleaseStatus.AWAITING_LICENSE_APPROVAL,
        ),
    )

    val all: List<NeuralVoicePackCatalogEntry> get() = listOf(kokoroModel) + kokoroVoices

    /** Lower is preferred. Used only to pick a voice for [VoiceSelection.Automatic]. */
    fun preferenceRank(packId: String): Int =
        kokoroVoices.indexOfFirst { it.packId == packId }.takeIf { it >= 0 } ?: Int.MAX_VALUE

    private fun voice(
        name: String,
        voiceId: String,
        displayName: String,
        detail: String,
        sha256: String,
        releaseStatus: NeuralPackReleaseStatus,
    ): NeuralVoicePackCatalogEntry {
        val packId = "app.narratify.kokoro.en-us.$name"
        val voiceVersion = "${voiceId.replace('_', '-')}-1939ad2"
        val assets = listOf(
            NeuralPackDownloadAsset(
                relativePath = "$voiceId.bin",
                url = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/$VOICE_COMMIT/voices/$voiceId.bin",
                sizeBytes = 522_240,
                sha256 = sha256,
            ),
        )
        val approved = releaseStatus == NeuralPackReleaseStatus.AVAILABLE
        return NeuralVoicePackCatalogEntry(
            displayName = displayName,
            detail = detail,
            schemaVersion = 2,
            kind = NeuralPackKind.VOICE,
            packId = packId,
            packVersion = "1.0.0",
            runtimeId = "narratify-kokoro-onnx",
            modelId = "kokoro-82m-v1.0-fp32-duration",
            modelVersion = "model-files-v1.1",
            voiceId = voiceId,
            voiceVersion = voiceVersion,
            languageTags = setOf("en-US"),
            licenseSpdxId = "Apache-2.0",
            attribution = ATTRIBUTION,
            pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
            assets = assets,
            releaseStatus = releaseStatus,
            runtimeBundled = true,
            approval = if (!approved) null else ApprovedNeuralVoicePack(
                packId = packId,
                packVersion = "1.0.0",
                kind = NeuralPackKind.VOICE,
                runtimeId = "narratify-kokoro-onnx",
                modelId = "kokoro-82m-v1.0-fp32-duration",
                modelVersion = "model-files-v1.1",
                voiceId = voiceId,
                voiceVersion = voiceVersion,
                licenseSpdxId = "Apache-2.0",
                assets = assets.map(NeuralPackDownloadAsset::modelAsset).toSet(),
                dependsOn = NeuralPackRef(kokoroModel.packId, kokoroModel.packVersion),
            ),
            dependency = kokoroModel,
        )
    }
}
```

Only `af_heart` is `AVAILABLE`, because only its artifact is authorized today (Task 10). The other five render as visible, locked cards. **Do not flip a voice to `AVAILABLE` without the owner's authorization recorded in the license inventory.**

- [ ] **Step 2: Run both installer test classes**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.NeuralVoicePackInstallerTest" --tests "app.narratify.NeuralPackDependencyTest"
```

Expected: PASS, 8 tests. Any remaining failure should come only from files Tasks 6–9 still have to update; if a failure names `NeuralVoicePackInstaller` or the catalog, fix it here.

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/kotlin/app/narratify/NeuralVoicePackInstaller.kt androidApp/src/test/kotlin/app/narratify/NeuralVoicePackInstallerTest.kt && git commit -m "feat: split the Kokoro model pack from six per-voice packs"
```

---

### Task 6: Voice-agnostic Kokoro runtime

**Files:**
- Modify: `androidApp/src/main/kotlin/app/narratify/KokoroOnnxRuntime.kt:22-56`
- Modify: `androidApp/src/main/kotlin/app/narratify/NarratifyApplication.kt:8`
- Test: `androidApp/src/test/kotlin/app/narratify/NeuralPackDependencyTest.kt` (add cases)

- [ ] **Step 1: Write the failing test**

Append to `NeuralPackDependencyTest`, before the private helpers:

```kotlin
    @Test
    fun `the runtime supports every approved catalog voice`() {
        NarratifyNeuralVoiceCatalog.kokoroVoices.forEach { entry ->
            val pack = entry.asPack(File("/tmp/${entry.packId}")).copy(
                dependency = NarratifyNeuralVoiceCatalog.kokoroModel.asPack(File("/tmp/model")),
            )
            assertTrue(KokoroOnnxRuntime.supports(pack), "expected support for ${entry.voiceId}")
        }
    }

    @Test
    fun `the runtime refuses a pack whose voice file is not declared`() {
        val entry = NarratifyNeuralVoiceCatalog.kokoroVoices.first()
        val pack = entry.asPack(File("/tmp/${entry.packId}")).copy(
            assets = emptyList(),
            dependency = NarratifyNeuralVoiceCatalog.kokoroModel.asPack(File("/tmp/model")),
        )

        assertFalse(KokoroOnnxRuntime.supports(pack))
    }

    @Test
    fun `the runtime refuses a model pack`() {
        val model = NarratifyNeuralVoiceCatalog.kokoroModel.asPack(File("/tmp/model"))

        assertFalse(KokoroOnnxRuntime.supports(model))
    }
```

Add `import kotlin.test.assertFalse` and `import kotlin.test.assertTrue` to that file.

`supports()` inspects declared metadata only, never the filesystem, so `/tmp` paths that do not exist are fine here.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.NeuralPackDependencyTest"
```

Expected: FAIL — `supports()` still requires `voiceId == "af_heart"` and reads `pack.assets` for a fixed `af_heart.bin`.

- [ ] **Step 3: Make the runtime voice-agnostic**

In `KokoroOnnxRuntime.kt`, replace everything from `fun registerIfApproved` through the `private const val SAMPLE_RATE` block with:

```kotlin
    /** Registers every catalog pack the app is licensed to ship. Nothing else can be activated. */
    fun registerIfApproved(catalog: List<NeuralVoicePackCatalogEntry>) {
        val approvals = catalog
            .filter { it.runtimeBundled && it.canDownload }
            .mapNotNull { it.approval }
            .toSet()
        if (approvals.isNotEmpty()) NeuralTtsRuntimeRegistry.register(this, approvals)
    }

    override fun supports(pack: NeuralVoicePack): Boolean =
        pack.kind == NeuralPackKind.VOICE &&
            pack.runtimeId == runtimeId &&
            pack.modelId == "kokoro-82m-v1.0-fp32-duration" &&
            pack.pcmFormat.sampleRateHz == SAMPLE_RATE &&
            pack.pcmFormat.channelCount == 1 &&
            pack.assets.any { it.relativePath == voiceFileName(pack) } &&
            pack.dependency?.assets.orEmpty().let { model ->
                model.any { it.relativePath == MODEL_FILE } && model.any { it.relativePath == DICTIONARY_FILE }
            }

    override fun open(pack: NeuralVoicePack): NeuralTtsSession {
        require(supports(pack)) { "Unsupported Kokoro voice pack" }
        NeuralVoicePackVerifier.verify(pack)
        return KokoroOnnxSession(
            model = pack.file(MODEL_FILE),
            voice = pack.file(voiceFileName(pack)),
            dictionary = pack.file(DICTIONARY_FILE),
        )
    }

    private fun voiceFileName(pack: NeuralVoicePack) = "${pack.voiceId}.bin"

    private const val MODEL_FILE = "kokoro-v1.0.onnx"
    private const val DICTIONARY_FILE = "cmudict.dict"
    private const val SAMPLE_RATE = 24_000
```

The `VOICE_FILE` constant is gone. Which voices are permitted is decided by the compiled approvals in `NeuralTtsRuntimeRegistry.compatible`, which runs `matches` before `supports`, so dropping the hardcoded `af_heart` check does not widen what can be activated.

- [ ] **Step 4: Update the registration call**

In `NarratifyApplication.kt`, replace:

```kotlin
        KokoroOnnxRuntime.registerIfApproved(NarratifyNeuralVoiceCatalog.kokoroEnglish)
```

with:

```kotlin
        KokoroOnnxRuntime.registerIfApproved(NarratifyNeuralVoiceCatalog.all)
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.NeuralPackDependencyTest"
```

Expected: PASS, 17 tests.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/kotlin/app/narratify/KokoroOnnxRuntime.kt androidApp/src/main/kotlin/app/narratify/NarratifyApplication.kt androidApp/src/test/kotlin/app/narratify/NeuralPackDependencyTest.kt && git commit -m "feat: open any approved Kokoro voice through its shared model pack"
```

---

### Task 7: Router resolves across several installed voices

**Files:**
- Modify: `androidApp/src/main/kotlin/app/narratify/VoiceSelection.kt:56-80` (`VoiceRouter`)
- Test: `androidApp/src/test/kotlin/app/narratify/VoiceSelectionTest.kt`

- [ ] **Step 1: Write the failing test**

Replace the whole `VoiceSelectionTest` class body with:

```kotlin
class VoiceSelectionTest {
    private val heart = pack("app.narratify.kokoro.en-us.heart", "af_heart")
    private val bella = pack("app.narratify.kokoro.en-us.bella", "af_bella")
    private val rank = { packId: String ->
        listOf(heart.packId, bella.packId).indexOf(packId).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }

    @Test
    fun `selections survive a store and parse round trip`() {
        listOf(
            VoiceSelection.Automatic,
            VoiceSelection.SystemVoice("en-us-x-sfg#female_1"),
            VoiceSelection.NeuralVoice(heart.packId),
        ).forEach { selection ->
            assertEquals(selection, VoiceSelection.parse(selection.store()))
        }
    }

    @Test
    fun `unknown and legacy stored values degrade safely`() {
        assertEquals(VoiceSelection.Automatic, VoiceSelection.parse(null))
        assertEquals(VoiceSelection.Automatic, VoiceSelection.parse(""))
        assertEquals(VoiceSelection.Automatic, VoiceSelection.parse("nonsense"))
        assertEquals(VoiceSelection.SystemVoice("legacy-voice"), VoiceSelection.parseLegacyVoiceId("legacy-voice"))
        assertEquals(VoiceSelection.Automatic, VoiceSelection.parseLegacyVoiceId(null))
    }

    @Test
    fun `a chosen neural voice is picked out of the installed voices`() {
        val resolved = VoiceRouter.resolve(VoiceSelection.NeuralVoice(bella.packId), listOf(heart, bella), rank)

        assertEquals(bella, resolved.neuralPack)
        assertTrue(resolved.reason.contains("chose", ignoreCase = true))
    }

    @Test
    fun `a chosen neural voice falls back to system speech when it is not installed`() {
        val resolved = VoiceRouter.resolve(VoiceSelection.NeuralVoice(bella.packId), listOf(heart), rank)

        assertNull(resolved.neuralPack)
        assertTrue(resolved.reason.contains("not installed", ignoreCase = true))
    }

    @Test
    fun `a chosen system voice is never overridden by an installed neural pack`() {
        val resolved = VoiceRouter.resolve(VoiceSelection.SystemVoice("en-us-x-sfg#female_1"), listOf(heart), rank)

        assertNull(resolved.neuralPack)
        assertEquals("en-us-x-sfg#female_1", resolved.systemVoiceName)
    }

    @Test
    fun `automatic follows catalog order rather than install order`() {
        val resolved = VoiceRouter.resolve(VoiceSelection.Automatic, listOf(bella, heart), rank)

        assertEquals(heart, resolved.neuralPack)
    }

    @Test
    fun `automatic uses system speech when no neural voice is installed`() {
        val resolved = VoiceRouter.resolve(VoiceSelection.Automatic, emptyList(), rank)

        assertNull(resolved.neuralPack)
        assertNull(resolved.systemVoiceName)
    }

    private fun pack(packId: String, voiceId: String) = NeuralVoicePack(
        directory = File("/tmp/$packId"),
        schemaVersion = 2,
        kind = NeuralPackKind.VOICE,
        packId = packId,
        packVersion = "1.0.0",
        runtimeId = "narratify-kokoro-onnx",
        modelId = "kokoro-82m-v1.0-fp32-duration",
        modelVersion = "model-files-v1.1",
        voiceId = voiceId,
        voiceVersion = "${voiceId.replace('_', '-')}-1939ad2",
        languageTags = setOf("en-US"),
        licenseSpdxId = "Apache-2.0",
        commercialUseAllowed = true,
        attribution = "Kokoro-82M",
        pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
        assets = emptyList(),
        dependsOn = NeuralPackRef("app.narratify.kokoro.model", "1.0.0"),
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.VoiceSelectionTest"
```

Expected: compilation failure — `resolve` takes a single nullable pack, not a list plus a rank function.

- [ ] **Step 3: Rewrite the router**

Replace `VoiceRouter` in `VoiceSelection.kt` with:

```kotlin
/**
 * Voice routing lives here rather than inside a controller so the choice is testable and the
 * debug page can explain, in words, why a given engine is speaking.
 */
object VoiceRouter {
    fun resolve(
        selection: VoiceSelection,
        installedNeuralPacks: List<NeuralVoicePack>,
        rank: (String) -> Int = NarratifyNeuralVoiceCatalog::preferenceRank,
    ): ResolvedVoice = when (selection) {
        is VoiceSelection.NeuralVoice ->
            installedNeuralPacks.firstOrNull { it.packId == selection.packId }
                ?.let { ResolvedVoice(it, null, "You chose this downloaded neural voice in Voices") }
                ?: ResolvedVoice(null, null, "The chosen neural voice is not installed — using a system voice")

        is VoiceSelection.SystemVoice ->
            ResolvedVoice(null, selection.voiceName, "You chose this system voice in Voices")

        VoiceSelection.Automatic ->
            installedNeuralPacks.minByOrNull { rank(it.packId) }
                ?.let { ResolvedVoice(it, null, "Automatic: the downloaded neural voice ${it.voiceId} is installed") }
                ?: ResolvedVoice(null, null, "Automatic: no neural voice is installed, using system speech")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "app.narratify.VoiceSelectionTest"
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/app/narratify/VoiceSelection.kt androidApp/src/test/kotlin/app/narratify/VoiceSelectionTest.kt && git commit -m "feat: route between several installed neural voices"
```

---

### Task 8: Move the three narration call sites to the list API

**Files:**
- Modify: `androidApp/src/main/kotlin/app/narratify/AndroidReaderTtsController.kt:32-42`
- Modify: `androidApp/src/main/kotlin/app/narratify/EpubNarration.kt:98-106`
- Modify: `androidApp/src/main/kotlin/app/narratify/TtsBenchmark.kt:89-97`

- [ ] **Step 1: Update `AndroidReaderTtsController`**

Replace:

```kotlin
            val installed = runCatching {
                NeuralVoicePackStore(applicationContext).firstCompatible(Locale.getDefault().toLanguageTag())
            }.getOrNull()
            val resolved = VoiceRouter.resolve(selection, installed?.first)
            main.post {
                if (released) return@post
                val neural = resolved.neuralPack?.let { pack -> installed?.second?.let { pack to it } }
```

with:

```kotlin
            val installed = runCatching {
                NeuralVoicePackStore(applicationContext).compatible(Locale.getDefault().toLanguageTag())
            }.getOrElse { emptyList() }
            val resolved = VoiceRouter.resolve(selection, installed.map { it.first })
            main.post {
                if (released) return@post
                val neural = resolved.neuralPack?.let { pack ->
                    installed.firstOrNull { it.first.packId == pack.packId }?.second?.let { pack to it }
                }
```

- [ ] **Step 2: Update `EpubNarration`**

Replace:

```kotlin
        val installed = withContext(Dispatchers.IO) {
            runCatching {
                NeuralVoicePackStore(applicationContext).firstCompatible(Locale.getDefault().toLanguageTag())
            }.getOrNull()
        }
        val resolved = VoiceRouter.resolve(preferences.voiceSelection, installed?.first)
        val neuralRuntime = installed?.second
```

with:

```kotlin
        val installed = withContext(Dispatchers.IO) {
            runCatching {
                NeuralVoicePackStore(applicationContext).compatible(Locale.getDefault().toLanguageTag())
            }.getOrElse { emptyList() }
        }
        val resolved = VoiceRouter.resolve(preferences.voiceSelection, installed.map { it.first })
        val neuralRuntime = resolved.neuralPack?.let { pack ->
            installed.firstOrNull { it.first.packId == pack.packId }?.second
        }
```

- [ ] **Step 3: Update `TtsBenchmark`**

Replace:

```kotlin
        val installed = runCatching {
            NeuralVoicePackStore(applicationContext).firstCompatible(Locale.getDefault().toLanguageTag())
        }.getOrNull()
        val resolved = VoiceRouter.resolve(selection, installed?.first)
        val runtime = installed?.second
```

with:

```kotlin
        val installed = runCatching {
            NeuralVoicePackStore(applicationContext).compatible(Locale.getDefault().toLanguageTag())
        }.getOrElse { emptyList() }
        val resolved = VoiceRouter.resolve(selection, installed.map { it.first })
        val runtime = resolved.neuralPack?.let { pack ->
            installed.firstOrNull { it.first.packId == pack.packId }?.second
        }
```

- [ ] **Step 4: Compile the main source set**

```bash
./gradlew :androidApp:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL, apart from `DestinationScreens.kt` which Task 9 rewrites. If the only errors name `DestinationScreens.kt` and `kokoroEnglish`, continue to Task 9 and compile again there.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/app/narratify/AndroidReaderTtsController.kt androidApp/src/main/kotlin/app/narratify/EpubNarration.kt androidApp/src/main/kotlin/app/narratify/TtsBenchmark.kt && git commit -m "refactor: resolve narration voices from the installed voice list"
```

---

### Task 9: Six voice cards in the Voices screen

**Files:**
- Modify: `androidApp/src/main/kotlin/app/narratify/DestinationScreens.kt:185-350` and `:450-465`

- [ ] **Step 1: Replace the single-pack state with per-voice state**

Replace the fields and the `init` block's neural section. Change:

```kotlin
    private var neuralPackChecking = true
```

to:

```kotlin
    /** Pack ids of every installed voice. Empty until the first storage scan finishes. */
    private var installedVoicePackIds: Set<String> = emptySet()
    private var neuralPackChecking = true
    private var downloadingPackId: String? = null
```

Replace the `installerExecutor.execute { ... }` block in `init` with:

```kotlin
        installerExecutor.execute {
            val installed = NarratifyNeuralVoiceCatalog.kokoroVoices
                .filter(packInstaller::installed)
                .map { it.packId }
                .toSet()
            mainHandler.post {
                if (!released) {
                    installedVoicePackIds = installed
                    neuralPackChecking = false
                    renderNeuralPack()
                }
            }
        }
```

- [ ] **Step 2: Render one card per voice**

Replace `renderNeuralPack` and `useNeuralVoiceRow` with:

```kotlin
    private fun renderNeuralPack(progress: Pair<Long, Long>? = null, error: String? = null) {
        neuralPack.removeAllViews()
        neuralPack.addView(sectionTitle("OFFLINE NEURAL VOICES"))
        NarratifyNeuralVoiceCatalog.kokoroVoices.forEach { entry ->
            val active = downloadingPackId == entry.packId
            neuralPack.addView(
                neuralVoiceCard(
                    entry = entry,
                    progress = progress?.takeIf { active },
                    error = error?.takeIf { active },
                ),
                topMargin(Gap.XS),
            )
        }
        neuralPack.addView(
            label(
                "Voices share one downloaded speech model. It is removed with the last voice. Every pack contains data only; Narratify verifies its approved size and SHA-256 before activation.",
                Type.MICRO,
                UI_MUTED,
            ),
            topMargin(Gap.SM),
        )
        neuralPack.addView(
            textAction("Licenses & attribution") {
                showLicenseNotices(context, packInstaller.installedDirectory(NarratifyNeuralVoiceCatalog.kokoroModel))
            },
            topMargin(Gap.XS),
        )
    }

    private fun neuralVoiceCard(
        entry: NeuralVoicePackCatalogEntry,
        progress: Pair<Long, Long>?,
        error: String?,
    ) = card().apply {
        val installed = entry.packId in installedVoicePackIds
        addView(label(entry.displayName, Type.TITLE, UI_INK, Typeface.BOLD, serif = true))
        addView(label(entry.detail, Type.BODY, UI_MUTED), topMargin(Gap.XS))

        val status = when {
            error != null -> error
            progress != null -> "Downloading ${formatMegabytes(progress.first)} of ${formatMegabytes(progress.second)}"
            neuralPackChecking -> "Checking installed packs…"
            installed -> "Downloaded · offline"
            !entry.canDownload -> "Awaiting licensing approval"
            else -> "${formatMegabytes(packInstaller.pendingDownloadBytes(entry))} download · Wi-Fi recommended"
        }
        addView(label(status, Type.LABEL, if (error == null) UI_ACCENT else palette.danger, Typeface.BOLD), topMargin(Gap.SM))

        val actionText = when {
            progress != null -> "Cancel download"
            neuralPackChecking -> "Checking…"
            installed -> "Remove voice"
            entry.canDownload -> "Download"
            else -> "Download unavailable"
        }
        val actionEnabled = !neuralPackChecking && (installed || progress != null || entry.canDownload)
        val destructive = installed || progress != null
        val action = {
            when {
                progress != null -> installCancelled.set(true)
                installed -> removeNeuralPack(entry)
                else -> confirmNeuralPackDownload(entry)
            }
        }
        addView(
            if (destructive) destructiveAction(actionText, action) else primaryAction(actionText, actionEnabled, action),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Gap.MD) },
        )
        if (installed) addView(useNeuralVoiceRow(entry), topMargin(Gap.SM))
    }

    /**
     * An installed voice is never forced on the reader: this row is the only thing that makes
     * Kokoro the primary voice, and unchecking it hands narration back to the system voices.
     */
    private fun useNeuralVoiceRow(entry: NeuralVoicePackCatalogEntry) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val chosen = preferences.voiceSelection == VoiceSelection.NeuralVoice(entry.packId)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Use as primary voice", Type.BODY, UI_INK, Typeface.BOLD))
            addView(label(if (chosen) "Narratify reads with this voice" else "Read aloud uses another voice", Type.MICRO, UI_MUTED), topMargin(Gap.XXS))
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(Switch(context).apply {
            tintTo(palette)
            isChecked = chosen
            contentDescription = "Use ${entry.displayName} as the primary voice"
            setOnCheckedChangeListener { _, value ->
                preferences.voiceSelection =
                    if (value) VoiceSelection.NeuralVoice(entry.packId) else VoiceSelection.Automatic
                renderNeuralPack()
                renderVoices()
            }
        })
    }
```

- [ ] **Step 3: Track which voice is downloading, and update the installed set**

Replace `confirmNeuralPackDownload`, `downloadNeuralPack`, and `removeNeuralPack` (`DestinationScreens.kt:341-408`) with:

```kotlin
    private fun confirmNeuralPackDownload(entry: NeuralVoicePackCatalogEntry) {
        AlertDialog.Builder(context)
            .setTitle("Download ${entry.displayName}?")
            .setMessage("This data-only voice pack uses ${formatMegabytes(packInstaller.pendingDownloadBytes(entry))}. Wi-Fi is recommended. After installation, narration stays on this device and works offline.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download") { _, _ -> downloadNeuralPack(entry) }
            .show()
    }

    private fun downloadNeuralPack(entry: NeuralVoicePackCatalogEntry) {
        installCancelled.set(false)
        downloadingPackId = entry.packId
        val total = packInstaller.pendingDownloadBytes(entry)
        renderNeuralPack(0L to total)
        installerExecutor.execute {
            runCatching {
                packInstaller.install(
                    entry,
                    onProgress = { current, reported ->
                        mainHandler.post { if (!released) renderNeuralPack(current to reported) }
                    },
                    isCancelled = installCancelled::get,
                )
            }.onSuccess {
                mainHandler.post {
                    if (!released) {
                        installedVoicePackIds = installedVoicePackIds + entry.packId
                        downloadingPackId = null
                        // A freshly downloaded voice is what the reader asked for, so make it
                        // primary unless they had already pinned a specific system voice.
                        if (preferences.voiceSelection == VoiceSelection.Automatic) {
                            preferences.voiceSelection = VoiceSelection.NeuralVoice(entry.packId)
                        }
                        renderNeuralPack()
                        renderVoices()
                    }
                }
            }.onFailure { failure ->
                mainHandler.post {
                    if (!released) renderNeuralPack(error = failure.message ?: "The voice could not be downloaded")
                }
            }
        }
    }

    private fun removeNeuralPack(entry: NeuralVoicePackCatalogEntry) {
        installerExecutor.execute {
            val removed = runCatching { packInstaller.remove(entry) }.getOrDefault(false)
            mainHandler.post {
                if (!released) {
                    if (removed) {
                        installedVoicePackIds = installedVoicePackIds - entry.packId
                        // Never leave the reader pointing at a voice that is no longer on disk.
                        if (preferences.voiceSelection == VoiceSelection.NeuralVoice(entry.packId)) {
                            preferences.voiceSelection = VoiceSelection.Automatic
                        }
                    }
                    renderNeuralPack()
                    renderVoices()
                }
            }
        }
    }
```

The failure branch deliberately leaves `downloadingPackId` set so the error stays attached to the card that failed; the next `confirmNeuralPackDownload` resets it.

- [ ] **Step 4: Point the two remaining license links at the model pack**

In the LEGAL card of the Settings screen (`DestinationScreens.kt`, the `textAction("Open")` and the `setOnClickListener` inside the "Licenses & notices" card), replace both occurrences of:

```kotlin
NeuralVoicePackInstaller(context).installedDirectory(NarratifyNeuralVoiceCatalog.kokoroEnglish)
```

with:

```kotlin
NeuralVoicePackInstaller(context).installedDirectory(NarratifyNeuralVoiceCatalog.kokoroModel)
```

`LICENSE.cmudict.txt` now ships in the model pack, so that is the directory `NarratifyLicenseNotices` must read.

- [ ] **Step 5: Compile and run the full suite**

```bash
./gradlew :androidApp:compileDebugKotlin && ./gradlew :androidApp:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL and every test green, including `LicenseNoticesTest`, `TtsDiagnosticsTest`, and `KokoroEnglishPhonemizerTest`, which this plan does not otherwise touch.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/kotlin/app/narratify/DestinationScreens.kt && git commit -m "feat: list every Kokoro voice in the Voices screen"
```

---

### Task 10: Licensing rows, notices, and benchmark provenance

**Files:**
- Modify: `benchmarks/tts/license-inventory.pending.json`
- Modify: `androidApp/src/main/assets/third_party/KOKORO_NOTICE.txt`
- Modify: `benchmarks/tts/results/kokoro-v1.0-fp32-m4pro-smoke.json`, `benchmarks/tts/results/kokoro-v1.0-duration-fp32-m4pro-smoke.json`, `benchmarks/tts/results/kokoro-v1.0-duration-fp16-m4pro-smoke.json`, `benchmarks/tts/results/b4-streaming-desktop-spike.json`

The inventory is `{schema_version, review_status, reviewed_by, reviewed_at_utc, release_policy, components}` and keys `components` by artifact, so only voice-style rows are needed — the model, dictionary, and dictionary licence already have their own authorized rows.

- [ ] **Step 1: Append five voice rows**

Add these five objects to the `components` array, immediately after the existing `"Kokoro af_heart voice style"` entry. They mirror that row exactly, except that `commercial_conclusion` is `"pending"` and the note records that authorization has not been given.

```json
    {
      "engine_variant": "kokoro-82m-v1.0-fp32",
      "component": "Kokoro af_bella voice style",
      "kind": "voice",
      "version_or_commit": "onnx-community/Kokoro-82M-v1.0-ONNX@1939ad2a8e416c0acfeecc08a694d14ef25f2231; voices/af_bella.bin",
      "sha256": "f69d836209b78eb8c66e75e3cda491e26ea838a3674257e9d4e5703cbaf55c8b",
      "source_url": "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/blob/1939ad2a8e416c0acfeecc08a694d14ef25f2231/voices/af_bella.bin",
      "license_expression": "Apache-2.0 (declared for source model/voices; verify exact artifact)",
      "license_evidence_url": "https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md",
      "redistributed": true,
      "commercial_conclusion": "pending",
      "authorization_pending": true,
      "notes": "Awaiting project-owner authorization for this exact artifact and digest, including its documented synthetic af_bella identity and attribution. Ships locked (AWAITING_LICENSE_APPROVAL) until that authorization is recorded."
    },
    {
      "engine_variant": "kokoro-82m-v1.0-fp32",
      "component": "Kokoro af_nicole voice style",
      "kind": "voice",
      "version_or_commit": "onnx-community/Kokoro-82M-v1.0-ONNX@1939ad2a8e416c0acfeecc08a694d14ef25f2231; voices/af_nicole.bin",
      "sha256": "cd2191ab31b914ed7b318416b0e4440fdf392ddad9106a060819aa600a64f59a",
      "source_url": "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/blob/1939ad2a8e416c0acfeecc08a694d14ef25f2231/voices/af_nicole.bin",
      "license_expression": "Apache-2.0 (declared for source model/voices; verify exact artifact)",
      "license_evidence_url": "https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md",
      "redistributed": true,
      "commercial_conclusion": "pending",
      "authorization_pending": true,
      "notes": "Awaiting project-owner authorization for this exact artifact and digest, including its documented synthetic af_nicole identity and attribution. Ships locked (AWAITING_LICENSE_APPROVAL) until that authorization is recorded."
    },
    {
      "engine_variant": "kokoro-82m-v1.0-fp32",
      "component": "Kokoro am_michael voice style",
      "kind": "voice",
      "version_or_commit": "onnx-community/Kokoro-82M-v1.0-ONNX@1939ad2a8e416c0acfeecc08a694d14ef25f2231; voices/am_michael.bin",
      "sha256": "1d1f21dd8da39c30705cd4c75d039d265e9bc4a2a93ed09bc9e1b1225eb95ba1",
      "source_url": "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/blob/1939ad2a8e416c0acfeecc08a694d14ef25f2231/voices/am_michael.bin",
      "license_expression": "Apache-2.0 (declared for source model/voices; verify exact artifact)",
      "license_evidence_url": "https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md",
      "redistributed": true,
      "commercial_conclusion": "pending",
      "authorization_pending": true,
      "notes": "Awaiting project-owner authorization for this exact artifact and digest, including its documented synthetic am_michael identity and attribution. Ships locked (AWAITING_LICENSE_APPROVAL) until that authorization is recorded."
    },
    {
      "engine_variant": "kokoro-82m-v1.0-fp32",
      "component": "Kokoro am_fenrir voice style",
      "kind": "voice",
      "version_or_commit": "onnx-community/Kokoro-82M-v1.0-ONNX@1939ad2a8e416c0acfeecc08a694d14ef25f2231; voices/am_fenrir.bin",
      "sha256": "c27989f741f7ee34d273a39d8a595cc0837d35f5ced9a29b7cc162614616df43",
      "source_url": "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/blob/1939ad2a8e416c0acfeecc08a694d14ef25f2231/voices/am_fenrir.bin",
      "license_expression": "Apache-2.0 (declared for source model/voices; verify exact artifact)",
      "license_evidence_url": "https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md",
      "redistributed": true,
      "commercial_conclusion": "pending",
      "authorization_pending": true,
      "notes": "Awaiting project-owner authorization for this exact artifact and digest, including its documented synthetic am_fenrir identity and attribution. Ships locked (AWAITING_LICENSE_APPROVAL) until that authorization is recorded."
    },
    {
      "engine_variant": "kokoro-82m-v1.0-fp32",
      "component": "Kokoro am_puck voice style",
      "kind": "voice",
      "version_or_commit": "onnx-community/Kokoro-82M-v1.0-ONNX@1939ad2a8e416c0acfeecc08a694d14ef25f2231; voices/am_puck.bin",
      "sha256": "fcf73c989033e9233e0b98713eca600c8c74dcc1614b37009d5450ff4a2274a0",
      "source_url": "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/blob/1939ad2a8e416c0acfeecc08a694d14ef25f2231/voices/am_puck.bin",
      "license_expression": "Apache-2.0 (declared for source model/voices; verify exact artifact)",
      "license_evidence_url": "https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md",
      "redistributed": true,
      "commercial_conclusion": "pending",
      "authorization_pending": true,
      "notes": "Awaiting project-owner authorization for this exact artifact and digest, including its documented synthetic am_puck identity and attribution. Ships locked (AWAITING_LICENSE_APPROVAL) until that authorization is recorded."
    },
```

**Do not write an authorization date, an approving party, or `"commercial_conclusion": "approved"` for any of these five.** That record is the project owner's to make.

- [ ] **Step 2: Leave `review_status` and `reviewed_at_utc` alone**

The top-level review fields describe the existing authorized set. Adding pending components does not change when that review happened, so do not touch them.

- [ ] **Step 3: Update the shipped notice**

Replace the "Selected voice style" line in `androidApp/src/main/assets/third_party/KOKORO_NOTICE.txt`:

```
Voice styles from onnx-community/Kokoro-82M-v1.0-ONNX:
  af_heart, af_bella, af_nicole, am_michael, am_fenrir, am_puck
Only styles Narratify is licensed to distribute are offered for download.
```

- [ ] **Step 4: Record why the benchmarks still apply**

Add to each of the four result files a note field alongside `voice_id`:

```json
"voice_scope": "Measured with af_heart. Every Kokoro v1.0 voice uses the same graph and the same 510 KB style-tensor shape, so RTF, latency, and RSS results carry across voices; only the style vector differs."
```

If a result file's schema rejects unknown keys, put the same sentence in the adjacent `README.md` under `benchmarks/tts/results/` instead and say which file you changed.

- [ ] **Step 5: Verify the inventory is still valid JSON and the suite is green**

```bash
python3 -m json.tool benchmarks/tts/license-inventory.pending.json > /dev/null && echo "inventory ok" && ./gradlew :androidApp:testDebugUnitTest
```

Expected: `inventory ok` and all tests passing. `LicenseNoticesTest` reads notices through an injected asset reader, so the notice text change does not break it — confirm it is green rather than assuming.

- [ ] **Step 6: Commit**

```bash
git add benchmarks/tts/license-inventory.pending.json androidApp/src/main/assets/third_party/KOKORO_NOTICE.txt benchmarks/tts/results && git commit -m "docs: record licensing and benchmark provenance for five more Kokoro voices"
```

---

### Task 11: Verify on a device

**Files:**
- No file changes.

- [ ] **Step 1: Run the full suite one more time**

```bash
./gradlew :androidApp:testDebugUnitTest
```

Expected: all green. Paste the summary line into the completion report.

- [ ] **Step 2: Install and exercise the Voices screen**

```bash
./gradlew :androidApp:installDebug
```

Then, on the device: open Voices, confirm six cards appear with `af_heart` downloadable and the other five showing "Awaiting licensing approval", download `af_heart`, confirm the size shown matches ~329 MB on a clean device, read a page aloud, then remove the voice and confirm the storage drops back (the model pack goes with it).

- [ ] **Step 3: Report honestly**

Report which steps ran, what the device check showed, and state plainly that the five new voices ship locked pending the owner's authorization in the license inventory. If you skipped commits because the repo is not git-initialized, say so.

---

## Owner action — DONE 2026-09-10

The project owner authorized all five remaining voices. The inventory rows are `approved` /
`redistributed: true`, all six catalog entries are `AVAILABLE` with compiled approvals, and the
licensing-gate tests were rewritten to prove the approval filter using a fabricated unapproved
entry rather than a real voice. Verified on device: all six cards offer Download, and three
voices share one 325 MB model pack.

`af_nicole` still has not been auditioned — see the risk below. Gate 0 also remains unpassed
(`validate_result.py` reports `gate status=fail`), which licensing approval does not change.

The original requirement, which still governs any voice added later, was:

Nothing in this plan unlocks `af_bella`, `af_nicole`, `am_michael`, `am_fenrir`, or `am_puck`. Each one needs the project owner to authorize its exact artifact, digest, and documented synthetic identity in `benchmarks/tts/license-inventory.pending.json`. Once a row is authorized, flip that entry's `releaseStatus` to `NeuralPackReleaseStatus.AVAILABLE` in `NarratifyNeuralVoiceCatalog.voice(...)` and add its `ApprovedNeuralVoicePack` — the `voice()` helper already builds the approval whenever the status is `AVAILABLE`, so the change is one enum per voice.

Also audition `af_nicole` before unlocking it: it is soft and close-miked, which may not hold up over a chapter. Dropping it is preferable to shipping a voice that sounds broken.
