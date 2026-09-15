# Audiobook Forced Alignment (Tier 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the offline engine and the repeatable quality gate that decide whether a user-supplied MP3/M4B narration can be word-synced to the matching EPUB, without shipping anything that claims exact sync it cannot prove.

**Architecture:** A new `:shared:align` Kotlin Multiplatform module turns a book chapter and an ASR hypothesis of its narration into a time map. The strategy is anchor-and-fill: words occurring exactly once on both sides are matched without search, the longest forward-moving run of those anchors is kept, and only the small gaps between anchors pay for quadratic alignment. Whatever still cannot be matched is interpolated and reported at a *lowered granularity* rather than presented as exact. Book tokens come from the existing `TtsTextPreparer` spoken-token map, so alignment compares the same normalized forms TTS already produces. A dependency-free Python harness in `benchmarks/alignment/` generates deterministic synthetic hypotheses for the contract gate and converts real whisper.cpp output for desktop evidence runs.

**Tech Stack:** Kotlin Multiplatform (`:shared:align`, depends on `:shared:domain` and `:shared:text`), kotlinx.serialization, `kotlin.test`, Gradle; Python 3 standard library + `unittest` for the harness, matching `benchmarks/tts/`.

**Context:** `IMPLEMENTATION_PLAN.md:35` excludes exact cross-edition alignment from v1 and `IMPLEMENTATION_PLAN.md:430` files it as separate research. This plan is that research project's first half.

---

## Scope

**In scope (this plan):**

1. The deterministic alignment engine, as a KMP module with no model, no audio decoding, and no I/O.
2. A serialization format for the resulting map, versioned the same way the rest of the repo versions derived artifacts.
3. A synthetic, checked-in fixture gate with hard numeric thresholds, runnable in CI on a laptop with no audio and no weights.
4. A whisper.cpp adapter so a real-audio evidence run is one command away.

**Explicitly out of scope — each needs its own plan:**

- **Gate A1 (real-audio evidence).** Running LibriVox/Gutenberg pairs through the adapter, annotating true onsets by hand, and recording the result. That experiment cannot be written before the instrument exists, and its numbers are the actual research answer. This plan builds the instrument.
- **On-device ASR runtime.** whisper.cpp on Android/iOS carries its own licensing, model-distribution, thermal, and battery gates — the same shape as Gate 0 for Kokoro, and it must not share a milestone with this.
- **Persistence and UI.** Writing the map to `derived_artifact` (the table at `shared/data/src/commonMain/sqldelight/com/narratify/data/db/DerivedArtifacts.sq:1` already has every column this needs: `kind`, `producer`, `producer_version`, `schema_version`, `source_fingerprint`, `parameters_hash`), the background job, and the read-along UI.

**A warning about what the gate in this plan measures.** The checked-in fixtures use *synthetic* narration timings generated from the reference text. They measure whether the aligner recovers known timings despite dropped and misrecognized words. They do **not** measure real acoustic accuracy — no real narrator, no real ASR, no real room. Anyone reading a green gate as "read-along works" is wrong, and the README written in Task 15 says so in those words.

## Prerequisites

**This directory is not a git repository.** `git rev-parse --is-inside-work-tree` fails and there is no `.git` directory, despite `.github/` existing. Before starting, either:

```bash
git init && git add -A && git commit -m "chore: baseline before forced-alignment work"
```

...or skip every "Commit" step in this plan. Do not silently invent version control — if you skip commits, say so when reporting completion.

**Test commands used throughout:**

```bash
./gradlew :shared:align:jvmTest
```

```bash
python3 -m unittest discover -s benchmarks/alignment/tests
```

**Full suite:**

```bash
./gradlew check
```

**A naming rule that only fails on one target.** Kotlin/Native rejects a comma inside a backticked
declaration name (`Name contains illegal characters: ","`). The JVM accepts it, so a test named
that way passes `:shared:align:jvmTest` and then breaks `compileTestKotlinIosArm64`. No test name
in this plan contains a comma; keep it that way if you add one.

## File structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | Adds `:shared:align` to the build. |
| `shared/align/build.gradle.kts` | Module targets and dependencies, copied from `:shared:text`. |
| `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AlignmentKey.kt` | One folding rule both sides are compared through. |
| `.../AlignmentModels.kt` | Public types: `AsrToken`, `TokenTiming`, `AlignedSpan`, `AlignmentGranularity`, `AlignmentOptions`, `AlignmentResult`, `AlignmentMap`. |
| `.../BookTokenizer.kt` | Book side: `TtsTextPreparer` chunks to a flat, punctuation-free, folded token list that keeps its source ranges. |
| `.../AnchorFinder.kt` | Unique-on-both-sides matches, reduced to the longest forward-moving run. Internal. |
| `.../BandedAligner.kt` | Exact edit-distance alignment for one gap, with a hard cell budget. Internal. |
| `.../ForcedAligner.kt` | The recursive anchor-and-fill driver, interpolation, span grouping, granularity. |
| `.../AlignmentCodec.kt` | JSON encode/decode with a schema-version guard. |
| `shared/align/src/commonTest/kotlin/.../*Test.kt` | Unit and property tests for each of the above. |
| `shared/align/src/jvmTest/kotlin/.../AlignmentGateTest.kt` | Reads `test-fixtures/alignment`, computes metrics, asserts `gate.json`. Lives in `jvmTest` because common code cannot read files — same reason as `shared/domain/src/jvmTest/kotlin/com/narratify/domain/OutlineFixtureParityTest.kt:20`. |
| `test-fixtures/alignment/gate.json` | The numeric gate. |
| `test-fixtures/alignment/cases/<name>/` | `case.json`, `reference.txt`, generated `hypothesis.json` and `expected.json`. |
| `benchmarks/alignment/make_fixture.py` | Deterministic synthetic hypothesis generator. |
| `benchmarks/alignment/validate_fixture.py` | Fixture well-formedness check for CI. |
| `benchmarks/alignment/whisper_adapter.py` | Converts whisper.cpp full-JSON output into `hypothesis.json`. Desktop evidence tooling. |
| `benchmarks/alignment/ADAPTER_PROTOCOL.md` | The hypothesis contract any ASR engine must satisfy. |
| `benchmarks/alignment/README.md` | What the gate does and does not prove. |
| `.github/workflows/verify.yml` | Runs the new checks. |

---

## Task 1: The `:shared:align` module and its folding rule

ASR output and book text never agree on case, punctuation, or apostrophes. Every comparison in this module goes through one folding function so that disagreement is settled in exactly one place.

**Files:**
- Modify: `settings.gradle.kts:24`
- Create: `shared/align/build.gradle.kts`
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AlignmentKey.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/AlignmentKeyTest.kt`

- [ ] **Step 1: Add the module to the build**

Add one line to `settings.gradle.kts` after `include(":shared:text")`:

```kotlin
include(":shared:align")
```

- [ ] **Step 2: Create `shared/align/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "app.narratify.shared.align"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
    }

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:text"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // Reads the alignment fixtures from disk, which common code cannot do.
        jvmTest.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:text"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
```

`implementation` rather than `api` for the two project dependencies matches `shared/text/build.gradle.kts:20`, whose public types also expose `:shared:domain` classes. Every consumer of this module already depends on `:shared:domain` directly.

- [ ] **Step 3: Write the failing test**

Create `shared/align/src/commonTest/kotlin/app/narratify/shared/align/AlignmentKeyTest.kt`:

```kotlin
package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AlignmentKeyTest {
    @Test
    fun `case and punctuation never decide a match`() {
        assertEquals(AlignmentKey.fold("Harbour,"), AlignmentKey.fold("harbour"))
        assertEquals(AlignmentKey.fold("“Seven!”"), AlignmentKey.fold("seven"))
    }

    @Test
    fun `an apostrophe reads the same however it was typeset`() {
        assertEquals(AlignmentKey.fold("don’t"), AlignmentKey.fold("don't"))
        assertEquals("don't", AlignmentKey.fold("don't"))
    }

    @Test
    fun `a contraction is not a homograph of another word`() {
        assertNotEquals(AlignmentKey.fold("we'll"), AlignmentKey.fold("well"))
        assertNotEquals(AlignmentKey.fold("can't"), AlignmentKey.fold("cant"))
    }

    @Test
    fun `quotation marks around a word are not part of it`() {
        assertEquals("seven", AlignmentKey.fold("‘seven’"))
        assertEquals("seven", AlignmentKey.fold("\"seven\""))
    }

    @Test
    fun `a hyphenated word is not a homograph of the word without it`() {
        assertNotEquals(AlignmentKey.fold("re-form"), AlignmentKey.fold("reform"))
        assertEquals("re-form", AlignmentKey.fold("re-form"))
    }

    @Test
    fun `an abbreviation is not a homograph of a word`() {
        assertNotEquals(AlignmentKey.fold("U.S"), AlignmentKey.fold("us"))
    }

    @Test
    fun `a modifier letter apostrophe is a letter and survives folding`() {
        assertEquals("don\u02bct", AlignmentKey.fold("don\u02bct"))
        assertNotEquals(AlignmentKey.fold("don\u02bct"), AlignmentKey.fold("don't"))
    }

    @Test
    fun `a mark at the edge of a token is punctuation rather than part of the word`() {
        assertEquals("tis", AlignmentKey.fold("'tis"))
        assertEquals("readers", AlignmentKey.fold("readers'"))
        assertEquals("", AlignmentKey.fold("'"))
        assertEquals("", AlignmentKey.fold("-"))
    }

    @Test
    fun `digits survive folding`() {
        assertEquals("1984", AlignmentKey.fold("1984."))
    }

    @Test
    fun `a token with nothing to compare folds to the empty key`() {
        assertEquals("", AlignmentKey.fold("—"))
        assertEquals("", AlignmentKey.fold(""))
    }

    @Test
    fun `normalization form never decides a match`() {
        // "café" precomposed (U+00E9) against "café" decomposed (e + U+0301). The two literals are
        // visually identical on purpose — the escape is what distinguishes them.
        assertEquals(AlignmentKey.fold("caf\u00e9"), AlignmentKey.fold("cafe\u0301"))
        assertEquals("caf\u00e9", AlignmentKey.fold("cafe\u0301"))
    }

    @Test
    fun `composing is not stripping so an accent still distinguishes two words`() {
        assertNotEquals(AlignmentKey.fold("caf\u00e9"), AlignmentKey.fold("cafe"))
    }

    @Test
    fun `scripts outside Latin fold to themselves`() {
        assertEquals("\u043c\u043e\u0440\u0435", AlignmentKey.fold("\u041c\u043e\u0440\u0435,"))
        assertEquals("\u6d77", AlignmentKey.fold("\u6d77\u3002"))
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest`
Expected: compilation failure, `Unresolved reference: AlignmentKey`.

- [ ] **Step 5: Write the canonical composition helper**

`fold` compares text from two independent pipelines, so it composes before it folds. Kotlin's
common standard library has no normalizer, which makes this the module's first `expect`/`actual`.

Create `shared/align/src/commonMain/kotlin/app/narratify/shared/align/UnicodeNormalization.kt`:

```kotlin
package app.narratify.shared.align

/**
 * Canonical composition, NFC.
 *
 * An EPUB and a speech recognizer are independent pipelines with no shared normalization form, so
 * the same word can arrive precomposed from one and decomposed from the other. Composing both
 * before folding is what makes the comparison about the word rather than about its encoding. It
 * matters most in languages where diacritics carry meaning: decomposed Vietnamese folds "mã",
 * "mạ", and "mà" onto one key, which would make those texts unmatchable.
 */
internal expect fun String.canonicallyComposed(): String
```

Create the same actual in both `shared/align/src/jvmMain/kotlin/app/narratify/shared/align/UnicodeNormalization.kt`
and `shared/align/src/androidMain/kotlin/app/narratify/shared/align/UnicodeNormalization.kt`. This
module's Android and JVM targets are separate leaf source sets, the way `shared/data` declares
them, so neither can see a file written only for the other:

```kotlin
package app.narratify.shared.align

import java.text.Normalizer

internal actual fun String.canonicallyComposed(): String =
    if (Normalizer.isNormalized(this, Normalizer.Form.NFC)) this
    else Normalizer.normalize(this, Normalizer.Form.NFC)
```

The `isNormalized` guard is there because almost all real text is already NFC and `normalize`
allocates unconditionally. This runs once per token across an entire book.

Create `shared/align/src/iosMain/kotlin/app/narratify/shared/align/UnicodeNormalization.kt`, which
covers both `iosArm64` and `iosSimulatorArm64`:

```kotlin
package app.narratify.shared.align

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

// Kotlin/Native does not model toll-free String/NSString bridging, so it reads this as impossible.
@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun String.canonicallyComposed(): String =
    (this as NSString).precomposedStringWithCanonicalMapping
```

Without the suppression the compiler reports `w: This cast can never succeed` on every iOS build.
The warning is wrong — the cast works at runtime, which `:shared:align:iosSimulatorArm64Test`
demonstrates — but an unexplained warning in the log is how people learn to ignore the warnings
that matter.

Do not try to remove the duplication between the `jvmMain` and `androidMain` copies by declaring a
`jvmAndAndroidMain` intermediate source set. Adding explicit `dependsOn` edges switches off the
default hierarchy template for the whole module, which unwires `iosMain` from `commonMain`; the iOS
targets then fail with `The 'expect' declaration 'canonicallyComposed' has no 'actual' declaration`.
Two six-line files are cheaper than that.

- [ ] **Step 6: Write the implementation**

Create `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AlignmentKey.kt`:

```kotlin
package app.narratify.shared.align

/**
 * The single place where the book's spelling and the transcriber's spelling are reconciled.
 *
 * Folding is deliberately lossy and deliberately cheap: it runs once over every token of a book
 * and once over every token of its narration, so anything clever here is paid for a hundred
 * thousand times.
 *
 * A mark between two letters is kept and canonicalised, so a typesetter's "don’t" and a
 * transcriber's "don't" agree. The set is exactly the one `shared/text`'s lexer treats as
 * word-internal — apostrophe, right single quote, hyphen, period — because deleting any of them
 * collapses two real
 * words onto one key: "we'll" onto "well", "re-form" onto "reform", "U.S" onto "us". That costs
 * matches, since a recognizer rarely writes the hyphen the page does. It is the right trade here:
 * a missed match lowers the granularity the result may claim, while a collision puts a confident
 * highlight on the wrong second.
 *
 * U+02BC MODIFIER LETTER APOSTROPHE is deliberately absent. Unicode classifies it as a letter, the
 * lexer keeps it as one, and in Uzbek and several romanizations it is a letter rather than
 * punctuation. Folding it to an apostrophe would erase a real distinction in those languages.
 *
 * Both sides are canonically composed first, so a precomposed "café" and a decomposed one fold to
 * the same key. Note this composes rather than strips: "café" and "cafe" remain different words,
 * which is correct — a narrator who says one did not say the other.
 */
object AlignmentKey {
    /**
     * The marks `shared/text`'s lexer keeps inside a word lexeme, each canonicalised to one form.
     * Folding preserves exactly this set so that two components cannot disagree about where a
     * word ends.
     */
    private val WORD_INTERNAL_MARKS = mapOf(
        '\'' to '\'', '\u2019' to '\'',
        '-' to '-',
        '.' to '.',
    )

    fun fold(value: String): String {
        val composed = value.canonicallyComposed()
        return buildString(composed.length) {
            for (index in composed.indices) {
                val character = composed[index]
                val mark = WORD_INTERNAL_MARKS[character]
                when {
                    character.isLetterOrDigit() -> append(character.lowercaseChar())
                    mark != null && composed.isInsideWord(index) -> append(mark)
                }
            }
        }
    }

    /** A mark is part of a word only between two letters; elsewhere it is punctuation. */
    private fun String.isInsideWord(index: Int): Boolean =
        index > 0 && index + 1 < length && this[index - 1].isLetterOrDigit() && this[index + 1].isLetterOrDigit()
}
```

- [ ] **Step 7: Run the tests, on every target**

This is the module's first `expect`/`actual`, so a JVM-only run proves nothing about the source-set
layout:

```bash
./gradlew :shared:align:jvmTest :shared:align:compileKotlinIosArm64 :shared:align:compileTestKotlinIosArm64
```

Expected: `BUILD SUCCESSFUL`, 13 tests passing, and the `This cast can never succeed` warning
described above.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts shared/align
git commit -m "feat(align): add the shared alignment module and its folding rule"
```

---

## Task 2: Alignment models

Every invariant this module promises is enforced in a constructor, so a bad map cannot be constructed, serialized, or stored. Monotonicity is the important one: a read-along that ever moves backwards is worse than no read-along.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AlignmentModels.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/AlignmentModelsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/align/src/commonTest/kotlin/app/narratify/shared/align/AlignmentModelsTest.kt`:

```kotlin
package app.narratify.shared.align

import com.narratify.domain.MediaItemId
import com.narratify.domain.PublicationId
import com.narratify.domain.ResourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AlignmentModelsTest {
    private fun span(
        start: Int,
        endExclusive: Int,
        startMs: Long,
        endMs: Long,
        ratio: Double = 1.0,
    ) = AlignedSpan(start, endExclusive, startMs, endMs, ratio, AlignmentGranularity.WORD)

    @Test
    fun `an ASR token cannot end before it starts`() {
        assertFailsWith<IllegalArgumentException> { AsrToken("harbour", startMs = 900, endMs = 400) }
    }

    @Test
    fun `an ASR token confidence stays a probability`() {
        assertFailsWith<IllegalArgumentException> { AsrToken("harbour", 0, 100, confidence = 1.4) }
    }

    @Test
    fun `a span covers at least one token`() {
        assertFailsWith<IllegalArgumentException> { span(4, 4, 0, 100) }
    }

    @Test
    fun `a map refuses spans that move backwards in the book`() {
        assertFailsWith<IllegalArgumentException> {
            AlignmentMap(
                publicationId = PublicationId("p"),
                mediaItemId = MediaItemId("m"),
                resourceId = ResourceId("r"),
                granularity = AlignmentGranularity.WORD,
                spans = listOf(span(4, 8, 1000, 2000), span(0, 4, 2000, 3000)),
            )
        }
    }

    @Test
    fun `a map refuses spans that move backwards in the audio`() {
        assertFailsWith<IllegalArgumentException> {
            AlignmentMap(
                publicationId = PublicationId("p"),
                mediaItemId = MediaItemId("m"),
                resourceId = ResourceId("r"),
                granularity = AlignmentGranularity.WORD,
                spans = listOf(span(0, 4, 5000, 6000), span(4, 8, 1000, 2000)),
            )
        }
    }

    @Test
    fun `a map refuses an audio window nested inside the one before it`() {
        // Both spans start in order, so checking starts alone lets this through. Playing it would
        // run the highlight forward through the book while the audio jumped from 9s back to 2s.
        assertFailsWith<IllegalArgumentException> {
            AlignmentMap(
                publicationId = PublicationId("p"),
                mediaItemId = MediaItemId("m"),
                resourceId = ResourceId("r"),
                granularity = AlignmentGranularity.WORD,
                spans = listOf(span(0, 4, 1000, 9000), span(4, 8, 2000, 3000)),
            )
        }
    }

    @Test
    fun `granularity falls with the share of the text that was actually matched`() {
        val options = AlignmentOptions()
        assertEquals(AlignmentGranularity.WORD, options.granularityFor(0.97))
        assertEquals(AlignmentGranularity.SENTENCE, options.granularityFor(0.70))
        assertEquals(AlignmentGranularity.CHAPTER, options.granularityFor(0.30))
        assertEquals(AlignmentGranularity.NONE, options.granularityFor(0.05))
    }

    @Test
    fun `a ratio exactly on a threshold earns the better granularity`() {
        val options = AlignmentOptions()
        assertEquals(AlignmentGranularity.WORD, options.granularityFor(options.wordThreshold))
        assertEquals(AlignmentGranularity.SENTENCE, options.granularityFor(options.sentenceThreshold))
        assertEquals(AlignmentGranularity.CHAPTER, options.granularityFor(options.chapterThreshold))
    }

    @Test
    fun `a valid multi-span map constructs`() {
        val map = AlignmentMap(
            publicationId = PublicationId("p"),
            mediaItemId = MediaItemId("m"),
            resourceId = ResourceId("r"),
            granularity = AlignmentGranularity.WORD,
            spans = listOf(span(0, 4, 0, 1000), span(4, 8, 1000, 2000)),
        )
        assertEquals(2, map.spans.size)
        assertEquals(CURRENT_ALIGNMENT_SCHEMA_VERSION, map.schemaVersion)
    }

    @Test
    fun `a map may summarise spans that are not all equally good`() {
        val map = AlignmentMap(
            publicationId = PublicationId("p"),
            mediaItemId = MediaItemId("m"),
            resourceId = ResourceId("r"),
            granularity = AlignmentGranularity.SENTENCE,
            spans = listOf(
                span(0, 4, 0, 1000),
                span(4, 8, 1000, 2000, ratio = 0.1).copy(granularity = AlignmentGranularity.CHAPTER),
            ),
        )
        assertEquals(AlignmentGranularity.SENTENCE, map.granularity)
    }

    @Test
    fun `a map cannot claim a granularity none of its spans reached`() {
        assertFailsWith<IllegalArgumentException> {
            AlignmentMap(
                publicationId = PublicationId("p"),
                mediaItemId = MediaItemId("m"),
                resourceId = ResourceId("r"),
                granularity = AlignmentGranularity.WORD,
                spans = listOf(span(0, 4, 0, 1000, ratio = 0.0).copy(granularity = AlignmentGranularity.NONE)),
            )
        }
    }

    @Test
    fun `a refused alignment carries no spans`() {
        assertFailsWith<IllegalArgumentException> {
            AlignmentMap(
                publicationId = PublicationId("p"),
                mediaItemId = MediaItemId("m"),
                resourceId = ResourceId("r"),
                granularity = AlignmentGranularity.NONE,
                spans = listOf(span(0, 4, 0, 1000)),
            )
        }
    }

    @Test
    fun `a result cannot describe tokens it has no timings for`() {
        assertFailsWith<IllegalArgumentException> {
            AlignmentResult(
                timings = listOf(TokenTiming(0, 0, 100, matched = true)),
                spans = listOf(span(0, 4, 0, 100)),
                matchedRatio = 1.0,
                granularity = AlignmentGranularity.WORD,
            )
        }
    }

    @Test
    fun `a result numbers its timings from zero without gaps`() {
        assertFailsWith<IllegalArgumentException> {
            AlignmentResult(
                timings = listOf(TokenTiming(5, 0, 100, matched = true)),
                spans = emptyList(),
                matchedRatio = 1.0,
                granularity = AlignmentGranularity.WORD,
            )
        }
    }

    @Test
    fun `a refused result is a valid empty result`() {
        val result = AlignmentResult.refused(0)
        assertEquals(0, result.timings.size)
        assertEquals(AlignmentGranularity.NONE, result.granularity)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest`
Expected: compilation failure, `Unresolved reference: AsrToken`.

- [ ] **Step 3: Write the implementation**

Create `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AlignmentModels.kt`:

```kotlin
package app.narratify.shared.align

import com.narratify.domain.MediaItemId
import com.narratify.domain.PublicationId
import com.narratify.domain.ResourceId
import kotlinx.serialization.Serializable

const val CURRENT_ALIGNMENT_SCHEMA_VERSION: Int = 1

/** One word as a speech recognizer heard it, with the audio it occupied. */
@Serializable
data class AsrToken(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Double = 1.0,
) {
    init {
        require(text.isNotBlank()) { "ASR token text must not be blank" }
        require(startMs >= 0) { "ASR token start must be non-negative" }
        require(endMs >= startMs) { "ASR token must not end before it starts" }
        require(confidence in 0.0..1.0) { "ASR token confidence must be a probability" }
    }
}

/**
 * How much of the text a granularity is honest about.
 *
 * WORD means the word under the cursor is the word being spoken. SENTENCE means only the
 * sentence is trustworthy. CHAPTER means nothing finer than the chapter was established.
 * NONE means the narration and the text did not agree enough to claim anything, which is the
 * correct answer for an abridgement, a different translation, or the wrong book.
 */
@Serializable
enum class AlignmentGranularity { WORD, SENTENCE, CHAPTER, NONE }

/** The predicted audio position of one book token, and whether it was matched or interpolated. */
@Serializable
data class TokenTiming(
    val bookTokenIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val matched: Boolean,
) {
    init {
        require(bookTokenIndex >= 0) { "Book token index must be non-negative" }
        require(startMs >= 0) { "Token start must be non-negative" }
        require(endMs >= startMs) { "Token must not end before it starts" }
    }
}

/** A run of book tokens the reader can be moved to as a unit. */
@Serializable
data class AlignedSpan(
    val bookTokenStart: Int,
    val bookTokenEndExclusive: Int,
    val startMs: Long,
    val endMs: Long,
    val matchedRatio: Double,
    val granularity: AlignmentGranularity,
) {
    init {
        require(bookTokenStart >= 0) { "Span start must be non-negative" }
        require(bookTokenEndExclusive > bookTokenStart) { "A span must cover at least one token" }
        require(startMs >= 0) { "Span start must be non-negative" }
        require(endMs >= startMs) { "Span must not end before it starts" }
        require(matchedRatio in 0.0..1.0) { "Matched ratio must be a proportion" }
    }
}

/** The persisted result for one text resource against one media item. */
@Serializable
data class AlignmentMap(
    val publicationId: PublicationId,
    val mediaItemId: MediaItemId,
    val resourceId: ResourceId,
    val granularity: AlignmentGranularity,
    val spans: List<AlignedSpan>,
    val schemaVersion: Int = CURRENT_ALIGNMENT_SCHEMA_VERSION,
) {
    init {
        require(schemaVersion > 0) { "Schema version must be positive" }
        require(
            spans.zipWithNext().all { (earlier, later) ->
                earlier.bookTokenEndExclusive <= later.bookTokenStart &&
                    earlier.startMs <= later.startMs &&
                    earlier.endMs <= later.endMs
            },
        ) { "Spans must move forward in both the book and the audio" }
        require(granularity != AlignmentGranularity.NONE || spans.isEmpty()) {
            "A refused alignment must not carry spans"
        }
        // The map's granularity comes from the whole chapter's matched share, which is the
        // token-weighted mean of its spans'. Because granularity falls monotonically with that
        // share, the summary can sit anywhere between the best and worst span but never outside
        // them. A chapter that is word-accurate overall may still contain a sentence nobody
        // matched, so requiring agreement instead would be wrong.
        require(
            spans.isEmpty() ||
                granularity in spans.minOf { it.granularity }..spans.maxOf { it.granularity },
        ) { "Map granularity must lie between its best and worst span" }
    }
}

/**
 * Where the line sits between a claim and a guess.
 *
 * These are not tuning knobs for making a book look aligned. Lowering them moves text out of
 * "interpolated" and into "exact" without any new evidence, which is the one failure the
 * product cannot recover from: a reader who catches the highlight lying stops trusting it.
 */
@Serializable
data class AlignmentOptions(
    val wordThreshold: Double = 0.85,
    val sentenceThreshold: Double = 0.50,
    val chapterThreshold: Double = 0.20,
) {
    init {
        require(wordThreshold in 0.0..1.0) { "wordThreshold must be a proportion" }
        require(sentenceThreshold in 0.0..wordThreshold) { "sentenceThreshold must not exceed wordThreshold" }
        require(chapterThreshold in 0.0..sentenceThreshold) { "chapterThreshold must not exceed sentenceThreshold" }
    }

    fun granularityFor(matchedRatio: Double): AlignmentGranularity = when {
        matchedRatio >= wordThreshold -> AlignmentGranularity.WORD
        matchedRatio >= sentenceThreshold -> AlignmentGranularity.SENTENCE
        matchedRatio >= chapterThreshold -> AlignmentGranularity.CHAPTER
        else -> AlignmentGranularity.NONE
    }
}

/** Everything one alignment run produced, before anything is persisted. */
data class AlignmentResult(
    val timings: List<TokenTiming>,
    val spans: List<AlignedSpan>,
    val matchedRatio: Double,
    val granularity: AlignmentGranularity,
) {
    init {
        require(matchedRatio in 0.0..1.0) { "Matched ratio must be a proportion" }
        require(timings.withIndex().all { (position, timing) -> timing.bookTokenIndex == position }) {
            "Timings must cover book token indices 0 until n in order"
        }
        require(spans.all { it.bookTokenEndExclusive <= timings.size }) {
            "Spans must not reference token indices beyond the timings list"
        }
    }

    companion object {
        fun refused(bookTokenCount: Int): AlignmentResult = AlignmentResult(
            timings = List(bookTokenCount) { TokenTiming(it, 0L, 0L, matched = false) },
            spans = emptyList(),
            matchedRatio = 0.0,
            granularity = AlignmentGranularity.NONE,
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest`
Expected: `BUILD SUCCESSFUL`, 28 tests passing.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): add alignment models with forward-only invariants"
```

---

## Task 3: The book token list

The book side must be the *spoken* form, not the printed form — a narrator says "seven", the page says "7". `TtsTextPreparer` already produces exactly that mapping and keeps the source ranges needed to highlight the printed word later, so this is a flattening, not a second tokenizer.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/BookTokenizer.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/BookTokenizerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/align/src/commonTest/kotlin/app/narratify/shared/align/BookTokenizerTest.kt`:

```kotlin
package app.narratify.shared.align

import com.narratify.domain.PublicationId
import com.narratify.domain.PublicationLocator
import com.narratify.domain.ResourceId
import com.narratify.domain.SemanticRole
import com.narratify.domain.SourceRange
import com.narratify.domain.SourceTextSpan
import com.narratify.domain.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookTokenizerTest {
    private val resource = ResourceId("chapter-1.xhtml")

    private fun span(text: String, role: SemanticRole = SemanticRole.PARAGRAPH) = SourceTextSpan(
        displayText = text,
        locator = PublicationLocator(publicationId = PublicationId("p"), resourceId = resource),
        semanticRole = role,
        language = "en-US",
        sourceRanges = listOf(SourceRange(resource, TextRange(0, text.length))),
    )

    @Test
    fun `tokens are folded spoken forms in reading order`() {
        val tokens = BookTokenizer.tokenize(listOf(span("The harbour turned grey.")))
        assertEquals(listOf("the", "harbour", "turned", "grey"), tokens.map(BookToken::key))
    }

    @Test
    fun `indices are contiguous from zero across spans`() {
        val tokens = BookTokenizer.tokenize(listOf(span("One two."), span("Three four.")))
        assertEquals(tokens.indices.toList(), tokens.map(BookToken::index))
    }

    @Test
    fun `punctuation never becomes a token a narrator could match`() {
        val tokens = BookTokenizer.tokenize(listOf(span("Wait — stop, now.")))
        assertEquals(listOf("wait", "stop", "now"), tokens.map(BookToken::key))
    }

    @Test
    fun `numerals align against what a narrator actually says`() {
        val tokens = BookTokenizer.tokenize(listOf(span("Seven of 7 boats.")))
        assertEquals(listOf("seven", "of", "seven", "boats"), tokens.map(BookToken::key))
    }

    @Test
    fun `every token keeps a source range so the printed word can be highlighted`() {
        val tokens = BookTokenizer.tokenize(listOf(span("The harbour turned grey.")))
        assertTrue(tokens.all { it.token.sourceRanges.isNotEmpty() })
    }

    @Test
    fun `tokens of one chunk are contiguous so sentences can be regrouped by scanning once`() {
        val tokens = BookTokenizer.tokenize(
            listOf(span("One two three four five six seven eight. Nine ten eleven twelve thirteen fourteen fifteen sixteen.")),
        )
        val chunkIds = tokens.map { it.chunkId }
        val runs = chunkIds.fold(mutableListOf<com.narratify.domain.ChunkId>()) { accumulator, id ->
            if (accumulator.lastOrNull() != id) accumulator.add(id)
            accumulator
        }
        assertEquals(runs, runs.distinct(), "a chunk id must not reappear after another chunk")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest`
Expected: compilation failure, `Unresolved reference: BookTokenizer`.

- [ ] **Step 3: Write the implementation**

Create `shared/align/src/commonMain/kotlin/app/narratify/shared/align/BookTokenizer.kt`:

```kotlin
package app.narratify.shared.align

import app.narratify.shared.text.PreparedTtsChunk
import app.narratify.shared.text.TextPreparationOptions
import app.narratify.shared.text.TtsTextPreparer
import com.narratify.domain.ChunkId
import com.narratify.domain.SourceTextSpan
import com.narratify.domain.SpokenToken
import com.narratify.domain.SpokenTokenFlag

/**
 * One word of the book as the narrator would have said it, with everything needed to point back
 * at the printed word it came from.
 */
data class BookToken(
    val index: Int,
    val key: String,
    val chunkId: ChunkId,
    val token: SpokenToken,
) {
    init {
        require(index >= 0) { "Book token index must be non-negative" }
        require(key.isNotEmpty()) { "Book token key must not be empty" }
    }
}

/**
 * Flattens the TTS spoken-token map into the sequence alignment compares against.
 *
 * Alignment deliberately reuses the TTS front end rather than tokenizing the book a second time.
 * A narrator reads "seven", not "7", and `TtsTextPreparer` is already the component that knows
 * that — a second tokenizer here would drift from it and silently lose matches on exactly the
 * tokens (numbers, abbreviations, roman numerals) that make the best anchors, because they are
 * the rarest words on the page.
 */
object BookTokenizer {
    fun tokenize(
        spans: List<SourceTextSpan>,
        options: TextPreparationOptions = TextPreparationOptions(),
    ): List<BookToken> = fromChunks(TtsTextPreparer.prepare(spans, options))

    fun fromChunks(chunks: List<PreparedTtsChunk>): List<BookToken> = buildList {
        for (chunk in chunks) {
            val chunkId = ChunkId(chunk.id)
            for (token in chunk.tokens) {
                if (SpokenTokenFlag.PUNCTUATION in token.flags) continue
                val key = AlignmentKey.fold(token.spokenText)
                if (key.isEmpty()) continue
                add(BookToken(index = size, key = key, chunkId = chunkId, token = token))
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest`
Expected: `BUILD SUCCESSFUL`, 34 tests passing.

If `numerals align against what a narrator actually says` fails, read `shared/text/src/commonMain/kotlin/app/narratify/shared/text/TextNormalizer.kt:36` before changing anything — the expectation, not the code, is what is wrong, and the fix is to correct the test to the normalizer's real output.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): derive book tokens from the TTS spoken-token map"
```

---

## Task 4: Anchors

A chapter has thousands of tokens on each side. Quadratic alignment over the whole thing is minutes of CPU and hundreds of megabytes; it is also unnecessary, because most of the matching is unambiguous. A word appearing exactly once in the chapter and exactly once in the transcript needs no search at all.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AnchorFinder.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/AnchorFinderTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/align/src/commonTest/kotlin/app/narratify/shared/align/AnchorFinderTest.kt`:

```kotlin
package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnchorFinderTest {
    private fun find(book: List<String>, hypothesis: List<String>) =
        AnchorFinder.find(book, hypothesis, 0, book.size, 0, hypothesis.size)

    @Test
    fun `a word unique on both sides anchors`() {
        val anchors = find(listOf("the", "harbour", "the"), listOf("uh", "the", "harbour", "the"))
        assertEquals(listOf(Anchor(bookIndex = 1, hypothesisIndex = 2)), anchors)
    }

    @Test
    fun `a word repeated on either side is not an anchor`() {
        assertEquals(emptyList(), find(listOf("the", "the"), listOf("the", "the")))
        assertEquals(emptyList(), find(listOf("harbour"), listOf("harbour", "harbour")))
    }

    @Test
    fun `anchors that would require going backwards are discarded`() {
        // "slate" and "rope" are unique on both sides but the narrator said them in the other
        // order, which cannot happen in a real reading; keeping both would invert the timeline.
        val anchors = find(
            listOf("slate", "a", "rope"),
            listOf("rope", "a", "slate"),
        )
        assertEquals(1, anchors.size)
        assertTrue(anchors.single().bookIndex in listOf(0, 2))
    }

    @Test
    fun `anchors come back sorted and strictly increasing on both sides`() {
        val book = listOf("alpha", "and", "bravo", "and", "charlie", "and", "delta")
        val hypothesis = listOf("alpha", "and", "bravo", "and", "and", "charlie", "and", "delta")
        val anchors = find(book, hypothesis)
        assertEquals(listOf("alpha", "bravo", "charlie", "delta"), anchors.map { book[it.bookIndex] })
        assertTrue(anchors.zipWithNext().all { (a, b) -> a.bookIndex < b.bookIndex && a.hypothesisIndex < b.hypothesisIndex })
    }

    @Test
    fun `uniqueness is judged inside the range being searched and not the whole chapter`() {
        // "the" repeats across the chapter but appears once inside the searched window, which is
        // what makes recursive narrowing find matches the first pass could not.
        val book = listOf("the", "harbour", "the", "boat")
        val hypothesis = listOf("the", "harbour", "the", "boat")
        val anchors = AnchorFinder.find(book, hypothesis, 2, 4, 2, 4)
        assertEquals(listOf(Anchor(2, 2), Anchor(3, 3)), anchors)
    }

    @Test
    fun `an empty range has no anchors`() {
        assertEquals(emptyList(), AnchorFinder.find(listOf("a"), listOf("a"), 0, 0, 0, 1))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest`
Expected: compilation failure, `Unresolved reference: AnchorFinder`.

- [ ] **Step 3: Write the implementation**

Create `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AnchorFinder.kt`:

```kotlin
package app.narratify.shared.align

/** A book token index paired with the hypothesis token index that says the same word. */
internal data class Anchor(val bookIndex: Int, val hypothesisIndex: Int)

/**
 * Finds the matches that need no search.
 *
 * Words occurring exactly once on each side of a range can only correspond to each other, so they
 * are free. What is not free is that a handful of them will still be wrong — a transcriber's
 * "sleight" landing where the book says "slate" — and a wrong anchor drags every interpolated
 * token between it and its neighbours to the wrong second. Keeping only the longest run that
 * moves forward on both sides throws those away, because a real reading never goes backwards.
 */
internal object AnchorFinder {
    fun find(
        book: List<String>,
        hypothesis: List<String>,
        bookFrom: Int,
        bookTo: Int,
        hypothesisFrom: Int,
        hypothesisTo: Int,
    ): List<Anchor> {
        if (bookFrom >= bookTo || hypothesisFrom >= hypothesisTo) return emptyList()

        val bookCounts = HashMap<String, Int>()
        for (index in bookFrom until bookTo) {
            bookCounts[book[index]] = (bookCounts[book[index]] ?: 0) + 1
        }
        val hypothesisCounts = HashMap<String, Int>()
        val hypothesisIndices = HashMap<String, Int>()
        for (index in hypothesisFrom until hypothesisTo) {
            val key = hypothesis[index]
            hypothesisCounts[key] = (hypothesisCounts[key] ?: 0) + 1
            hypothesisIndices[key] = index
        }

        val candidates = ArrayList<Anchor>()
        for (index in bookFrom until bookTo) {
            val key = book[index]
            if (bookCounts[key] != 1 || hypothesisCounts[key] != 1) continue
            candidates.add(Anchor(index, hypothesisIndices.getValue(key)))
        }
        return longestForwardRun(candidates)
    }

    /**
     * Patience sorting over the hypothesis indices. The candidates already ascend by book index,
     * so the longest increasing subsequence of hypothesis indices is the largest set of anchors
     * that can all be true at once.
     */
    private fun longestForwardRun(candidates: List<Anchor>): List<Anchor> {
        if (candidates.isEmpty()) return emptyList()
        val pileTops = ArrayList<Int>()
        val previous = IntArray(candidates.size) { -1 }
        for ((position, candidate) in candidates.withIndex()) {
            var low = 0
            var high = pileTops.size
            while (low < high) {
                val middle = (low + high) / 2
                if (candidates[pileTops[middle]].hypothesisIndex < candidate.hypothesisIndex) {
                    low = middle + 1
                } else {
                    high = middle
                }
            }
            if (low > 0) previous[position] = pileTops[low - 1]
            if (low == pileTops.size) pileTops.add(position) else pileTops[low] = position
        }
        val run = ArrayDeque<Anchor>()
        var cursor = pileTops.last()
        while (cursor != -1) {
            run.addFirst(candidates[cursor])
            cursor = previous[cursor]
        }
        return run.toList()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest`
Expected: `BUILD SUCCESSFUL`, 40 tests passing.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): match unique words and keep only forward-moving anchors"
```

---

## Task 5: Gap alignment with a cell budget

Between two anchors sits a short stretch where words repeat and nothing is unique. That is where exact edit-distance alignment belongs — and where a hard refusal belongs too, because one pathological gap must not turn a background job into an out-of-memory crash.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/BandedAligner.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/BandedAlignerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/align/src/commonTest/kotlin/app/narratify/shared/align/BandedAlignerTest.kt`:

```kotlin
package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BandedAlignerTest {
    private fun align(book: List<String>, hypothesis: List<String>) =
        BandedAligner.align(book, hypothesis, 0, book.size, 0, hypothesis.size)

    @Test
    fun `identical runs match position for position`() {
        val words = listOf("and", "the", "and", "the")
        assertEquals(listOf(Anchor(0, 0), Anchor(1, 1), Anchor(2, 2), Anchor(3, 3)), align(words, words))
    }

    @Test
    fun `a word the narrator skipped leaves the rest matched`() {
        val matches = align(listOf("and", "then", "the", "and"), listOf("and", "the", "and"))
        assertEquals(listOf("and", "the", "and"), matches!!.map { listOf("and", "then", "the", "and")[it.bookIndex] })
        assertTrue(matches.zipWithNext().all { (a, b) -> a.bookIndex < b.bookIndex && a.hypothesisIndex < b.hypothesisIndex })
    }

    @Test
    fun `a word the recognizer invented leaves the rest matched`() {
        val matches = align(listOf("and", "the"), listOf("and", "uh", "the"))
        assertEquals(listOf(Anchor(0, 0), Anchor(1, 2)), matches)
    }

    @Test
    fun `a misheard word is reported as unmatched rather than forced`() {
        val matches = align(listOf("and", "slate", "the"), listOf("and", "sleight", "the"))
        assertEquals(listOf(Anchor(0, 0), Anchor(2, 2)), matches)
    }

    @Test
    fun `an empty side matches nothing without failing`() {
        assertEquals(emptyList(), align(emptyList(), listOf("and")))
        assertEquals(emptyList(), align(listOf("and"), emptyList()))
    }

    @Test
    fun `a gap too large to align refuses instead of allocating`() {
        val book = List(600) { "word" }
        val hypothesis = List(600) { "word" }
        assertNull(align(book, hypothesis))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest`
Expected: compilation failure, `Unresolved reference: BandedAligner`.

- [ ] **Step 3: Write the implementation**

Create `shared/align/src/commonMain/kotlin/app/narratify/shared/align/BandedAligner.kt`:

```kotlin
package app.narratify.shared.align

/**
 * Exact alignment for one gap between anchors.
 *
 * This is ordinary Levenshtein with a traceback that reports only the substitutions that were
 * actually equal, so a misheard word comes back unmatched rather than confidently wrong. It is
 * quadratic in both directions, which is affordable for the gaps anchoring leaves behind and
 * ruinous for anything larger, so it refuses rather than trying: a returned `null` means the
 * caller should narrow the range or interpolate across it.
 */
internal object BandedAligner {
    /** Roughly 1 MB of int cells. A gap this size means anchoring failed, not that the book is hard. */
    const val MAX_CELLS: Long = 250_000L

    fun align(
        book: List<String>,
        hypothesis: List<String>,
        bookFrom: Int,
        bookTo: Int,
        hypothesisFrom: Int,
        hypothesisTo: Int,
    ): List<Anchor>? {
        val rows = bookTo - bookFrom
        val columns = hypothesisTo - hypothesisFrom
        if (rows <= 0 || columns <= 0) return emptyList()
        if (rows.toLong() * columns.toLong() > MAX_CELLS) return null

        val cost = Array(rows + 1) { IntArray(columns + 1) }
        for (row in 0..rows) cost[row][0] = row
        for (column in 0..columns) cost[0][column] = column
        for (row in 1..rows) {
            for (column in 1..columns) {
                val same = book[bookFrom + row - 1] == hypothesis[hypothesisFrom + column - 1]
                cost[row][column] = minOf(
                    cost[row - 1][column - 1] + if (same) 0 else 1,
                    cost[row - 1][column] + 1,
                    cost[row][column - 1] + 1,
                )
            }
        }

        val matches = ArrayDeque<Anchor>()
        var row = rows
        var column = columns
        while (row > 0 && column > 0) {
            val bookIndex = bookFrom + row - 1
            val hypothesisIndex = hypothesisFrom + column - 1
            val same = book[bookIndex] == hypothesis[hypothesisIndex]
            when {
                cost[row][column] == cost[row - 1][column - 1] + if (same) 0 else 1 -> {
                    if (same) matches.addFirst(Anchor(bookIndex, hypothesisIndex))
                    row -= 1
                    column -= 1
                }
                cost[row][column] == cost[row - 1][column] + 1 -> row -= 1
                else -> column -= 1
            }
        }
        return matches.toList()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest`
Expected: `BUILD SUCCESSFUL`, 46 tests passing.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): align anchor gaps exactly, and refuse gaps that are too large"
```

---

## Task 6: Recursive anchor-and-fill matching

Anchoring a whole chapter once leaves gaps. Anchoring *inside* a gap works, because uniqueness is a property of the range: "the" is hopeless chapter-wide and decisive inside eleven tokens. Recursing until anchors stop appearing, then paying for exact alignment on what is left, is the whole algorithm.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AlignmentMatcher.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/AlignmentMatcherTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/align/src/commonTest/kotlin/app/narratify/shared/align/AlignmentMatcherTest.kt`:

```kotlin
package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlignmentMatcherTest {
    @Test
    fun `an exact transcript matches every token`() {
        val keys = "the lantern went out at half past four and the harbour turned grey".split(" ")
        val matches = AlignmentMatcher.match(keys, keys)
        assertEquals(keys.indices.toList(), matches.map(Anchor::bookIndex))
        assertEquals(keys.indices.toList(), matches.map(Anchor::hypothesisIndex))
    }

    @Test
    fun `matches always move forward on both sides`() {
        val book = ("alpha and the bravo and the charlie and the delta and the echo and the foxtrot").split(" ")
        val hypothesis = ("alpha and the bravo and uh the charlie and the delta the echo and the foxtrot").split(" ")
        val matches = AlignmentMatcher.match(book, hypothesis)
        assertTrue(matches.zipWithNext().all { (a, b) -> a.bookIndex < b.bookIndex && a.hypothesisIndex < b.hypothesisIndex })
    }

    @Test
    fun `common words inside a gap still match once the gap is narrow`() {
        // No token is unique chapter-wide, so the first anchoring pass finds nothing and the
        // result comes entirely from narrowing and exact gap alignment.
        val book = "the and the and the and".split(" ")
        val hypothesis = "the and the and the and".split(" ")
        assertEquals(6, AlignmentMatcher.match(book, hypothesis).size)
    }

    @Test
    fun `text the narrator never read stays unmatched instead of being pulled forward`() {
        val book = "alpha bravo charlie delta echo foxtrot".split(" ")
        val hypothesis = "alpha bravo foxtrot".split(" ")
        val matches = AlignmentMatcher.match(book, hypothesis)
        assertEquals(listOf(0, 1, 5), matches.map(Anchor::bookIndex))
    }

    @Test
    fun `audio the book never contained stays unmatched`() {
        val book = "alpha bravo charlie".split(" ")
        val hypothesis = "this is a recording alpha bravo charlie thank you for listening".split(" ")
        val matches = AlignmentMatcher.match(book, hypothesis)
        assertEquals(listOf(0, 1, 2), matches.map(Anchor::bookIndex))
        assertEquals(listOf(4, 5, 6), matches.map(Anchor::hypothesisIndex))
    }

    @Test
    fun `two texts with nothing in common produce no matches`() {
        val matches = AlignmentMatcher.match(
            "alpha bravo charlie".split(" "),
            "xylem zephyr quokka".split(" "),
        )
        assertEquals(emptyList(), matches)
    }

    @Test
    fun `a chapter-sized input completes without a stack overflow`() {
        val book = List(20_000) { "word$it" }
        val hypothesis = book.filterIndexed { index, _ -> index % 13 != 0 }
        val matches = AlignmentMatcher.match(book, hypothesis)
        assertEquals(hypothesis.size, matches.size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest`
Expected: compilation failure, `Unresolved reference: AlignmentMatcher`.

- [ ] **Step 3: Write the implementation**

Create `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AlignmentMatcher.kt`:

```kotlin
package app.narratify.shared.align

/**
 * Pairs book tokens with hypothesis tokens, anchor first and exactly afterwards.
 *
 * Uniqueness is a property of the range being searched, not of the chapter, so a gap between two
 * anchors is worth anchoring again: "the" decides nothing across ten thousand tokens and decides
 * everything across eleven. Recursion stops when a range yields no anchors, and that range is
 * handed to exact alignment — or, if it is too large for exact alignment to be affordable, left
 * unmatched for the caller to interpolate across and mark as a guess.
 */
internal object AlignmentMatcher {
    /**
     * Each level strictly shrinks the range, so this bound is never reached by well-behaved text.
     * It exists so that adversarial input degrades into interpolation instead of a stack overflow.
     */
    private const val MAX_DEPTH = 32

    fun match(book: List<String>, hypothesis: List<String>): List<Anchor> {
        val matches = ArrayList<Anchor>()
        matchRange(book, hypothesis, 0, book.size, 0, hypothesis.size, depth = 0, into = matches)
        return matches
    }

    private fun matchRange(
        book: List<String>,
        hypothesis: List<String>,
        bookFrom: Int,
        bookTo: Int,
        hypothesisFrom: Int,
        hypothesisTo: Int,
        depth: Int,
        into: MutableList<Anchor>,
    ) {
        if (bookFrom >= bookTo || hypothesisFrom >= hypothesisTo) return

        val anchors = if (depth < MAX_DEPTH) {
            AnchorFinder.find(book, hypothesis, bookFrom, bookTo, hypothesisFrom, hypothesisTo)
        } else {
            emptyList()
        }
        if (anchors.isEmpty()) {
            BandedAligner.align(book, hypothesis, bookFrom, bookTo, hypothesisFrom, hypothesisTo)
                ?.let(into::addAll)
            return
        }

        var bookCursor = bookFrom
        var hypothesisCursor = hypothesisFrom
        for (anchor in anchors) {
            matchRange(
                book, hypothesis,
                bookCursor, anchor.bookIndex,
                hypothesisCursor, anchor.hypothesisIndex,
                depth + 1, into,
            )
            into.add(anchor)
            bookCursor = anchor.bookIndex + 1
            hypothesisCursor = anchor.hypothesisIndex + 1
        }
        matchRange(book, hypothesis, bookCursor, bookTo, hypothesisCursor, hypothesisTo, depth + 1, into)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest`
Expected: `BUILD SUCCESSFUL`, 53 tests passing.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): match a chapter by recursive anchoring with exact gap fill"
```

---

## Task 7: Timings, spans, and an honest granularity

Matched tokens get a real time. Everything else gets a straight-line guess between its matched neighbours — which is fine, and must never be presented as if it were measured. The share of the text that was actually matched is what decides how much the result is allowed to claim.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/ForcedAligner.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/ForcedAlignerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/align/src/commonTest/kotlin/app/narratify/shared/align/ForcedAlignerTest.kt`:

```kotlin
package app.narratify.shared.align

import com.narratify.domain.PublicationId
import com.narratify.domain.PublicationLocator
import com.narratify.domain.ResourceId
import com.narratify.domain.SemanticRole
import com.narratify.domain.SourceRange
import com.narratify.domain.SourceTextSpan
import com.narratify.domain.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForcedAlignerTest {
    private val resource = ResourceId("chapter-1.xhtml")

    private fun bookOf(text: String): List<BookToken> = BookTokenizer.tokenize(
        listOf(
            SourceTextSpan(
                displayText = text,
                locator = PublicationLocator(publicationId = PublicationId("p"), resourceId = resource),
                semanticRole = SemanticRole.PARAGRAPH,
                language = "en-US",
                sourceRanges = listOf(SourceRange(resource, TextRange(0, text.length))),
            ),
        ),
    )

    /** One spoken word every 500 ms, so expected times are readable in the assertions. */
    private fun narrate(words: String, firstStartMs: Long = 0L): List<AsrToken> =
        words.split(" ").mapIndexed { index, word ->
            val start = firstStartMs + index * 500L
            AsrToken(word, startMs = start, endMs = start + 400L)
        }

    private val sentence = "The lantern went out and the harbour turned grey."

    @Test
    fun `a matched token takes the time the recognizer measured`() {
        val book = bookOf(sentence)
        val result = ForcedAligner.align(book, narrate("the lantern went out and the harbour turned grey"))
        assertEquals(0L, result.timings[0].startMs)
        assertEquals(500L, result.timings[1].startMs)
        assertEquals(4000L, result.timings[8].startMs)
        assertTrue(result.timings.all { it.matched })
        assertEquals(AlignmentGranularity.WORD, result.granularity)
    }

    @Test
    fun `an unmatched token is interpolated between its neighbours and flagged`() {
        val book = bookOf(sentence)
        // The narrator's third word is misheard, so "went" has no measurement of its own.
        val result = ForcedAligner.align(book, narrate("the lantern wend out and the harbour turned grey"))
        assertFalse(result.timings[2].matched)
        assertTrue(result.timings[2].startMs in 400L..1500L, "was ${result.timings[2].startMs}")
    }

    @Test
    fun `token times never move backwards`() {
        val book = bookOf(sentence)
        val result = ForcedAligner.align(book, narrate("the lantern wend out and uh the harbour turnd grey"))
        assertTrue(result.timings.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }

    @Test
    fun `text before the first match holds at the first measured time rather than guessing`() {
        val book = bookOf(sentence)
        val result = ForcedAligner.align(book, narrate("harbour turned grey", firstStartMs = 9_000L))
        assertEquals(9_000L, result.timings[0].startMs)
        assertFalse(result.timings[0].matched)
    }

    @Test
    fun `spans cover every token exactly once and move forward`() {
        val book = bookOf("One two three. Four five six. Seven eight nine.")
        val result = ForcedAligner.align(book, narrate("one two three four five six seven eight nine"))
        assertEquals(0, result.spans.first().bookTokenStart)
        assertEquals(book.size, result.spans.last().bookTokenEndExclusive)
        assertTrue(
            result.spans.zipWithNext().all { (a, b) ->
                a.bookTokenEndExclusive == b.bookTokenStart && a.startMs <= b.startMs
            },
        )
    }

    @Test
    fun `a half-recognized chapter is offered as sentences rather than words`() {
        val book = bookOf("alpha bravo charlie delta echo foxtrot golf hotel india juliet")
        val result = ForcedAligner.align(book, narrate("alpha bravo charlie delta echo"))
        assertEquals(0.5, result.matchedRatio)
        assertEquals(AlignmentGranularity.SENTENCE, result.granularity)
    }

    @Test
    fun `a narration of a different book is refused outright`() {
        val book = bookOf("alpha bravo charlie delta echo foxtrot golf hotel india juliet")
        val result = ForcedAligner.align(book, narrate("xylem zephyr quokka nimbus fjord"))
        assertEquals(AlignmentGranularity.NONE, result.granularity)
        assertEquals(emptyList(), result.spans)
    }

    @Test
    fun `empty input is refused rather than throwing`() {
        assertEquals(AlignmentGranularity.NONE, ForcedAligner.align(emptyList(), narrate("alpha")).granularity)
        assertEquals(AlignmentGranularity.NONE, ForcedAligner.align(bookOf(sentence), emptyList()).granularity)
    }

    @Test
    fun `a timing is produced for every book token even when nothing matched`() {
        val book = bookOf(sentence)
        val result = ForcedAligner.align(book, emptyList())
        assertEquals(book.size, result.timings.size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest`
Expected: compilation failure, `Unresolved reference: ForcedAligner`.

- [ ] **Step 3: Write the implementation**

Create `shared/align/src/commonMain/kotlin/app/narratify/shared/align/ForcedAligner.kt`:

```kotlin
package app.narratify.shared.align

import com.narratify.domain.ChunkId

/**
 * Turns a chapter and a transcript of its narration into a time map.
 *
 * The only interesting decision here is what to do with the tokens that did not match. They get a
 * straight-line time between their matched neighbours, which is usually close and occasionally
 * seconds out, and they are flagged so nothing downstream can mistake the guess for a
 * measurement. The share of the chapter that did match then caps what the whole result is allowed
 * to claim: a book read at word granularity, an abridgement at sentence granularity, and a
 * narration of some other book refused entirely.
 */
object ForcedAligner {
    /**
     * Every granularity in the result is derived here, from the matched share, and never set
     * alongside it independently. No data class can check that rule: an `AlignedSpan` has no
     * reference to the [AlignmentOptions] that produced it, and a map read back from disk may have
     * been aligned under thresholds that have since changed, so recomputing and comparing would
     * reject old data that was correct when it was written.
     */
    fun align(
        bookTokens: List<BookToken>,
        hypothesis: List<AsrToken>,
        options: AlignmentOptions = AlignmentOptions(),
    ): AlignmentResult {
        if (bookTokens.isEmpty() || hypothesis.isEmpty()) return AlignmentResult.refused(bookTokens.size)

        val matches = AlignmentMatcher.match(
            bookTokens.map(BookToken::key),
            hypothesis.map { AlignmentKey.fold(it.text) },
        )
        if (matches.isEmpty()) return AlignmentResult.refused(bookTokens.size)

        val timings = timings(bookTokens.size, hypothesis, matches)
        val matchedRatio = matches.size.toDouble() / bookTokens.size
        val granularity = options.granularityFor(matchedRatio)
        if (granularity == AlignmentGranularity.NONE) {
            return AlignmentResult(timings, spans = emptyList(), matchedRatio = matchedRatio, granularity = granularity)
        }
        return AlignmentResult(
            timings = timings,
            spans = spans(bookTokens, timings, options),
            matchedRatio = matchedRatio,
            granularity = granularity,
        )
    }

    private fun timings(
        bookTokenCount: Int,
        hypothesis: List<AsrToken>,
        matches: List<Anchor>,
    ): List<TokenTiming> {
        val start = LongArray(bookTokenCount) { -1L }
        val end = LongArray(bookTokenCount) { -1L }
        for (anchor in matches) {
            start[anchor.bookIndex] = hypothesis[anchor.hypothesisIndex].startMs
            end[anchor.bookIndex] = hypothesis[anchor.hypothesisIndex].endMs
        }
        val matched = BooleanArray(bookTokenCount) { start[it] >= 0L }
        val first = matched.indexOfFirst { it }
        val last = matched.indexOfLast { it }

        // Outside the matched range there is no evidence at all, so hold at the nearest known
        // time. Extrapolating a reading rate outwards would invent seconds of audio and put the
        // highlight on words the narrator has not reached.
        for (index in 0 until first) {
            start[index] = start[first]
            end[index] = start[first]
        }
        for (index in last + 1 until bookTokenCount) {
            start[index] = end[last]
            end[index] = end[last]
        }

        var cursor = first
        while (cursor < last) {
            var next = cursor + 1
            while (!matched[next]) next += 1
            val gap = next - cursor
            if (gap > 1) {
                val available = (start[next] - end[cursor]).coerceAtLeast(0L)
                val step = available / gap
                for (offset in 1 until gap) {
                    start[cursor + offset] = end[cursor] + step * (offset - 1)
                    end[cursor + offset] = end[cursor] + step * offset
                }
            }
            cursor = next
        }

        return List(bookTokenCount) { TokenTiming(it, start[it], end[it], matched[it]) }
    }

    /**
     * Groups tokens back into the TTS chunks they came from. Those chunks are already
     * sentence-shaped, which is the unit a reader can be moved to without landing mid-clause,
     * and their tokens are contiguous, so one scan is enough.
     */
    private fun spans(
        bookTokens: List<BookToken>,
        timings: List<TokenTiming>,
        options: AlignmentOptions,
    ): List<AlignedSpan> = buildList {
        var index = 0
        var earliestStart = 0L
        var earliestEnd = 0L
        while (index < bookTokens.size) {
            val chunkId: ChunkId = bookTokens[index].chunkId
            var endExclusive = index
            while (endExclusive < bookTokens.size && bookTokens[endExclusive].chunkId == chunkId) {
                endExclusive += 1
            }
            val matched = (index until endExclusive).count { timings[it].matched }
            val ratio = matched.toDouble() / (endExclusive - index)
            // Interpolated times can overlap or nest; the map refuses both, so clamp each span to
            // start and end no earlier than the one before it.
            val startMs = maxOf(timings[index].startMs, earliestStart)
            val endMs = maxOf(timings[endExclusive - 1].endMs, startMs, earliestEnd)
            add(
                AlignedSpan(
                    bookTokenStart = index,
                    bookTokenEndExclusive = endExclusive,
                    startMs = startMs,
                    endMs = endMs,
                    matchedRatio = ratio,
                    granularity = options.granularityFor(ratio),
                ),
            )
            earliestStart = startMs
            earliestEnd = endMs
            index = endExclusive
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest`
Expected: `BUILD SUCCESSFUL`, 62 tests passing.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): interpolate unmatched tokens and cap the claim at the matched share"
```

---

## Task 8: Serializing the map

An alignment costs minutes of CPU, so it will be cached in `derived_artifact` (`shared/data/src/commonMain/sqldelight/com/narratify/data/db/DerivedArtifacts.sq:1`). That table already keys reuse on `producer_version` and `schema_version`, and `invalidateArtifactsByProducer` already sweeps stale rows — so the only thing this module owes it is a format that refuses to be read at the wrong version instead of being misread at it.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AlignmentCodec.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/AlignmentCodecTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/align/src/commonTest/kotlin/app/narratify/shared/align/AlignmentCodecTest.kt`:

```kotlin
package app.narratify.shared.align

import com.narratify.domain.MediaItemId
import com.narratify.domain.PublicationId
import com.narratify.domain.PublicationLocator
import com.narratify.domain.ResourceId
import com.narratify.domain.SemanticRole
import com.narratify.domain.SourceRange
import com.narratify.domain.SourceTextSpan
import com.narratify.domain.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AlignmentCodecTest {
    private val resource = ResourceId("chapter-1.xhtml")
    private val publication = PublicationId("p")
    private val media = MediaItemId("chapter-1.m4b#3")

    private fun result(): AlignmentResult {
        val text = "One two three. Four five six."
        val book = BookTokenizer.tokenize(
            listOf(
                SourceTextSpan(
                    displayText = text,
                    locator = PublicationLocator(publicationId = publication, resourceId = resource),
                    semanticRole = SemanticRole.PARAGRAPH,
                    language = "en-US",
                    sourceRanges = listOf(SourceRange(resource, TextRange(0, text.length))),
                ),
            ),
        )
        val hypothesis = "one two three four five six".split(" ").mapIndexed { index, word ->
            AsrToken(word, startMs = index * 500L, endMs = index * 500L + 400L)
        }
        return ForcedAligner.align(book, hypothesis)
    }

    @Test
    fun `a map survives a round trip unchanged`() {
        val map = result().toAlignmentMap(publication, media, resource)
        assertEquals(map, AlignmentCodec.decode(AlignmentCodec.encode(map)))
    }

    @Test
    fun `the map carries the granularity the alignment earned`() {
        val alignment = result()
        val map = alignment.toAlignmentMap(publication, media, resource)
        assertEquals(alignment.granularity, map.granularity)
        assertEquals(alignment.spans, map.spans)
    }

    @Test
    fun `a map written by a future schema is refused rather than misread`() {
        val map = result().toAlignmentMap(publication, media, resource)
        val future = AlignmentCodec.encode(map).replace(
            "\"schemaVersion\":$CURRENT_ALIGNMENT_SCHEMA_VERSION",
            "\"schemaVersion\":${CURRENT_ALIGNMENT_SCHEMA_VERSION + 1}",
        )
        assertFailsWith<IllegalArgumentException> { AlignmentCodec.decode(future) }
    }

    @Test
    fun `a map with an unknown field is refused rather than silently losing it`() {
        val map = result().toAlignmentMap(publication, media, resource)
        val extended = AlignmentCodec.encode(map).replaceFirst("{", "{\"narratorId\":\"x\",")
        assertFailsWith<Exception> { AlignmentCodec.decode(extended) }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest`
Expected: compilation failure, `Unresolved reference: AlignmentCodec`.

- [ ] **Step 3: Write the implementation**

Create `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AlignmentCodec.kt`:

```kotlin
package app.narratify.shared.align

import com.narratify.domain.MediaItemId
import com.narratify.domain.PublicationId
import com.narratify.domain.ResourceId
import kotlinx.serialization.json.Json

/**
 * Builds the persistable map from a finished alignment.
 *
 * The per-token timings are deliberately not persisted. They are large, they are mostly
 * interpolation, and a reader that needs them can recompute them from the spans it is showing.
 */
fun AlignmentResult.toAlignmentMap(
    publicationId: PublicationId,
    mediaItemId: MediaItemId,
    resourceId: ResourceId,
): AlignmentMap = AlignmentMap(
    publicationId = publicationId,
    mediaItemId = mediaItemId,
    resourceId = resourceId,
    granularity = granularity,
    spans = spans,
)

/**
 * The on-disk form of an alignment.
 *
 * An alignment is expensive enough to cache and cheap enough to recompute, which makes strictness
 * free: an unreadable cache entry costs one background job, while a misread one puts the
 * highlight in the wrong place and looks like a bug in the reader.
 */
object AlignmentCodec {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(map: AlignmentMap): String = json.encodeToString(AlignmentMap.serializer(), map)

    fun decode(value: String): AlignmentMap {
        val map = json.decodeFromString(AlignmentMap.serializer(), value)
        require(map.schemaVersion == CURRENT_ALIGNMENT_SCHEMA_VERSION) {
            "Alignment schema ${map.schemaVersion} cannot be read by version $CURRENT_ALIGNMENT_SCHEMA_VERSION"
        }
        return map
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest`
Expected: `BUILD SUCCESSFUL`, 66 tests passing.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): persist alignment maps with a strict schema guard"
```

---

## Task 9: The synthetic hypothesis generator

The gate needs narration that is realistic in the ways that break alignment — dropped words, misrecognitions, a narrator who reads a preamble that is not in the book — and whose true timings are known exactly. Real audio cannot give both. A generator can, and is honest about what it is as long as nobody reads its output as an accuracy measurement.

**Files:**
- Create: `benchmarks/alignment/make_fixture.py`
- Test: `benchmarks/alignment/tests/test_make_fixture.py`

- [ ] **Step 1: Write the failing test**

Create `benchmarks/alignment/tests/test_make_fixture.py`:

```python
"""Tests for the synthetic narration generator.

The generator is the ground truth the alignment gate is measured against, so the properties that
matter are not about speech at all: perturbing a hypothesis must never move the timings of the
words that survived, and the same case must produce the same bytes on every machine. If either
breaks, the gate starts measuring the generator instead of the aligner.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import make_fixture

REFERENCE = "Alpha bravo charlie delta. Echo foxtrot golf hotel. India juliet kilo lima."


class TimelineTest(unittest.TestCase):
    def test_words_are_laid_out_in_order_without_overlapping(self):
        tokens = make_fixture.timeline(["alpha", "bravo", "charlie"], base_ms=120, per_character_ms=45, gap_ms=40)
        self.assertEqual([token["text"] for token in tokens], ["alpha", "bravo", "charlie"])
        for earlier, later in zip(tokens, tokens[1:]):
            self.assertLess(earlier["endMs"], later["startMs"])

    def test_a_longer_word_occupies_more_audio(self):
        tokens = make_fixture.timeline(["it", "extraordinary"], base_ms=120, per_character_ms=45, gap_ms=40)
        self.assertLess(
            tokens[0]["endMs"] - tokens[0]["startMs"],
            tokens[1]["endMs"] - tokens[1]["startMs"],
        )


class PerturbationTest(unittest.TestCase):
    def build(self, **overrides):
        case = {
            "reference": "reference.txt",
            "spoken": "reference.txt",
            "expectedGranularity": "WORD",
            "seed": 1,
            "baseMs": 120,
            "perCharacterMs": 45,
            "gapMs": 40,
            "preamble": [],
            "dropRate": 0.0,
            "substituteRate": 0.0,
        }
        case.update(overrides)
        return make_fixture.build(case, reference=REFERENCE, spoken=REFERENCE)

    def test_a_clean_case_transcribes_every_word(self):
        hypothesis, _ = self.build()
        self.assertEqual(len(hypothesis), len(make_fixture.words(REFERENCE)))

    def test_dropping_words_leaves_the_survivors_at_their_true_times(self):
        clean, _ = self.build()
        dropped, _ = self.build(dropRate=0.3)
        self.assertLess(len(dropped), len(clean))
        clean_by_start = {token["startMs"]: token["text"] for token in clean}
        for token in dropped:
            self.assertEqual(clean_by_start[token["startMs"]], token["text"])

    def test_substitution_keeps_the_slot_and_changes_the_word(self):
        clean, _ = self.build()
        substituted, _ = self.build(substituteRate=1.0)
        self.assertEqual(len(clean), len(substituted))
        self.assertEqual(
            [token["startMs"] for token in clean],
            [token["startMs"] for token in substituted],
        )
        self.assertNotEqual(
            [token["text"] for token in clean],
            [token["text"] for token in substituted],
        )

    def test_a_preamble_delays_every_word_of_the_book(self):
        plain, plain_expected = self.build()
        with_preamble, preamble_expected = self.build(preamble=["this", "is", "a", "recording"])
        self.assertGreater(len(with_preamble), len(plain))
        self.assertGreater(preamble_expected["onsets"][0]["onsetMs"], plain_expected["onsets"][0]["onsetMs"])

    def test_the_same_case_produces_the_same_bytes(self):
        first, first_expected = self.build(dropRate=0.2, substituteRate=0.2)
        second, second_expected = self.build(dropRate=0.2, substituteRate=0.2)
        self.assertEqual(first, second)
        self.assertEqual(first_expected, second_expected)


class GroundTruthTest(unittest.TestCase):
    def test_one_onset_is_recorded_per_sentence(self):
        _, expected = make_fixture.build(
            {
                "reference": "reference.txt",
                "spoken": "reference.txt",
                "expectedGranularity": "WORD",
                "seed": 1,
                "baseMs": 120,
                "perCharacterMs": 45,
                "gapMs": 40,
                "preamble": [],
                "dropRate": 0.0,
                "substituteRate": 0.0,
            },
            reference=REFERENCE,
            spoken=REFERENCE,
        )
        self.assertEqual(len(expected["onsets"]), 3)
        self.assertEqual(expected["onsets"][0]["onsetMs"], 0)

    def test_a_narration_of_another_text_records_no_onsets(self):
        _, expected = make_fixture.build(
            {
                "reference": "reference.txt",
                "spoken": "other.txt",
                "expectedGranularity": "NONE",
                "seed": 1,
                "baseMs": 120,
                "perCharacterMs": 45,
                "gapMs": 40,
                "preamble": [],
                "dropRate": 0.0,
                "substituteRate": 0.0,
            },
            reference=REFERENCE,
            spoken="Xylem zephyr quokka nimbus. Fjord gazebo halcyon ibex.",
        )
        self.assertEqual(expected["onsets"], [])
        self.assertEqual(expected["granularity"], "NONE")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m unittest discover -s benchmarks/alignment/tests`
Expected: `ModuleNotFoundError: No module named 'make_fixture'`.

- [ ] **Step 3: Write the implementation**

Create `benchmarks/alignment/make_fixture.py`:

```python
#!/usr/bin/env python3
"""Generate a deterministic synthetic narration for one alignment fixture case.

This produces the hypothesis the alignment gate runs against and the ground-truth sentence onsets
it is scored on. It is not a speech simulator: durations are a linear function of word length and
every perturbation is drawn from a seeded generator, because the gate has to give the same answer
on every machine and every run. Perturbations only ever delete a word or replace its text; they
never move a surviving word, so the recorded onsets stay true no matter how damaged the
hypothesis is.
"""

from __future__ import annotations

import argparse
import json
import random
import re
import sys
from pathlib import Path

WORD = re.compile(r"[0-9A-Za-zÀ-ɏ'’]+")
SENTENCE = re.compile(r"(?<=[.!?])\s+")
QUOTE_WORDS = 6
CONFUSIONS = ["and", "the", "then", "her", "his", "that", "when", "uh", "a", "in"]


def words(text: str) -> list[str]:
    return WORD.findall(text)


def sentences(text: str) -> list[str]:
    return [part for part in SENTENCE.split(text.strip()) if part.strip()]


def timeline(tokens: list[str], base_ms: int, per_character_ms: int, gap_ms: int) -> list[dict]:
    cursor = 0
    result = []
    for token in tokens:
        duration = base_ms + per_character_ms * len(token)
        result.append({"text": token, "startMs": cursor, "endMs": cursor + duration, "confidence": 1.0})
        cursor += duration + gap_ms
    return result


def build(case: dict, reference: str, spoken: str) -> tuple[list[dict], dict]:
    """Return the perturbed hypothesis and the ground truth it should be scored against."""
    preamble = list(case["preamble"])
    spoken_words = words(spoken)
    tokens = timeline(
        preamble + spoken_words,
        base_ms=case["baseMs"],
        per_character_ms=case["perCharacterMs"],
        gap_ms=case["gapMs"],
    )
    book_tokens = tokens[len(preamble):]

    # Ground truth is read off the clean timeline, before anything is damaged, and only when the
    # narration is of this text. A narration of something else has no true onsets in this book.
    onsets = []
    if case["spoken"] == case["reference"]:
        cursor = 0
        for sentence in sentences(reference):
            sentence_words = words(sentence)
            if not sentence_words:
                continue
            onsets.append(
                {
                    "quote": " ".join(sentence_words[:QUOTE_WORDS]),
                    "onsetMs": book_tokens[cursor]["startMs"],
                }
            )
            cursor += len(sentence_words)

    generator = random.Random(case["seed"])
    hypothesis = []
    for token in tokens:
        if generator.random() < case["dropRate"]:
            continue
        if generator.random() < case["substituteRate"]:
            replacement = CONFUSIONS[generator.randrange(len(CONFUSIONS))]
            token = dict(token, text=replacement, confidence=0.4)
        hypothesis.append(token)

    return hypothesis, {"granularity": case["expectedGranularity"], "onsets": onsets}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case_dir", type=Path, help="fixture case directory holding case.json")
    arguments = parser.parse_args()

    case_dir: Path = arguments.case_dir
    case = json.loads((case_dir / "case.json").read_text(encoding="utf-8"))
    reference = (case_dir / case["reference"]).read_text(encoding="utf-8")
    spoken = (case_dir / case["spoken"]).read_text(encoding="utf-8")

    hypothesis, expected = build(case, reference=reference, spoken=spoken)
    (case_dir / "hypothesis.json").write_text(
        json.dumps(hypothesis, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (case_dir / "expected.json").write_text(
        json.dumps(expected, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"{case_dir.name}: {len(hypothesis)} tokens, {len(expected['onsets'])} onsets")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m unittest discover -s benchmarks/alignment/tests`
Expected: `OK`, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add benchmarks/alignment
git commit -m "feat(align): add the deterministic synthetic narration generator"
```

---

## Task 10: The fixture cases

Five cases, chosen because each is a different way real read-along fails: a clean reading, a narrator who talks before the book starts, a transcript full of holes, a narration that covers only part of the text, and a narration of something else entirely.

**Files:**
- Create: `test-fixtures/alignment/gate.json`
- Create: `test-fixtures/alignment/cases/clean-narration/{case.json,reference.txt}`
- Create: `test-fixtures/alignment/cases/narrator-preamble/{case.json,reference.txt}`
- Create: `test-fixtures/alignment/cases/asr-dropouts/{case.json,reference.txt}`
- Create: `test-fixtures/alignment/cases/partial-narration/{case.json,reference.txt,partial.txt}`
- Create: `test-fixtures/alignment/cases/wrong-edition/{case.json,reference.txt,unrelated.txt}`
- Create: `benchmarks/alignment/validate_fixture.py`
- Test: `benchmarks/alignment/tests/test_validate_fixture.py`

- [ ] **Step 1: Write the shared reference text**

This exact text goes into `reference.txt` in all five case directories. It carries no digits, abbreviations, or roman numerals, so the Kotlin spoken-form normalizer and the generator's plain word split cannot disagree about how many tokens it contains.

```
The lantern went out at half past four, and the harbour turned the colour of wet slate.

Mara counted the moored boats twice before she trusted the total. Seven were tied along the north wall, and the eighth berth held nothing but frayed rope.

She wrote the figure in her notebook, underlined it, and walked back along the breakwater without hurrying. Somebody would ask her about the missing hull before the morning was over, and she wanted an answer ready.
```

```bash
mkdir -p test-fixtures/alignment/cases/{clean-narration,narrator-preamble,asr-dropouts,partial-narration,wrong-edition}
```

Write the text above to `test-fixtures/alignment/cases/clean-narration/reference.txt`, then:

```bash
for case in narrator-preamble asr-dropouts partial-narration wrong-edition; do
  cp test-fixtures/alignment/cases/clean-narration/reference.txt "test-fixtures/alignment/cases/$case/reference.txt"
done
```

- [ ] **Step 2: Write the five case descriptors**

`test-fixtures/alignment/cases/clean-narration/case.json`:

```json
{
  "description": "A narrator who read the text exactly. Anything but near-perfect recovery here is an algorithm bug, not a hard case.",
  "reference": "reference.txt",
  "spoken": "reference.txt",
  "expectedGranularity": "WORD",
  "seed": 1,
  "baseMs": 120,
  "perCharacterMs": 45,
  "gapMs": 40,
  "preamble": [],
  "dropRate": 0.0,
  "substituteRate": 0.0
}
```

`test-fixtures/alignment/cases/narrator-preamble/case.json`:

```json
{
  "description": "Ten seconds of narration that is not in the book. Every onset is shifted, so an aligner that anchors on position instead of content fails this and only this.",
  "reference": "reference.txt",
  "spoken": "reference.txt",
  "expectedGranularity": "WORD",
  "seed": 2,
  "baseMs": 120,
  "perCharacterMs": 45,
  "gapMs": 40,
  "preamble": ["this", "recording", "is", "produced", "for", "the", "narratify", "alignment", "fixture", "suite"],
  "dropRate": 0.0,
  "substituteRate": 0.0
}
```

`test-fixtures/alignment/cases/asr-dropouts/case.json`. A 6% drop and 4% substitution rate should leave roughly 90% of tokens matchable, comfortably above `wordThreshold` at 0.85 — but that is an expectation about a seeded draw, not a guarantee. If Step 3 produces a case whose matched ratio falls below 0.86, try seeds 7, 11, and 13 and keep the first that lands between 0.86 and 0.94, noting the chosen seed in the `description`. That is picking a representative sample; editing `gate.json` to accommodate an unrepresentative one is not, and is the thing to avoid here.

```json
{
  "description": "A transcript with roughly one word in ten missing or misheard, which is what a small offline model on a noisy recording actually produces.",
  "reference": "reference.txt",
  "spoken": "reference.txt",
  "expectedGranularity": "WORD",
  "seed": 7,
  "baseMs": 120,
  "perCharacterMs": 45,
  "gapMs": 40,
  "preamble": [],
  "dropRate": 0.06,
  "substituteRate": 0.04
}
```

`test-fixtures/alignment/cases/partial-narration/case.json`:

```json
{
  "description": "An audiobook that covers only part of the text it was paired with. The result must be offered as sentences, never as words.",
  "reference": "reference.txt",
  "spoken": "partial.txt",
  "expectedGranularity": "SENTENCE",
  "seed": 3,
  "baseMs": 120,
  "perCharacterMs": 45,
  "gapMs": 40,
  "preamble": [],
  "dropRate": 0.0,
  "substituteRate": 0.0
}
```

`test-fixtures/alignment/cases/partial-narration/partial.txt` — the first two paragraphs of the reference plus the opening sentence of the third, verbatim. That is 61 of the reference's 80 words, so the matched share lands near 0.76: clear of `sentenceThreshold` at 0.50 and clear of `wordThreshold` at 0.85, which is what makes the case test the degradation rule rather than a threshold boundary.

```
The lantern went out at half past four, and the harbour turned the colour of wet slate.

Mara counted the moored boats twice before she trusted the total. Seven were tied along the north wall, and the eighth berth held nothing but frayed rope.

She wrote the figure in her notebook, underlined it, and walked back along the breakwater without hurrying.
```

`test-fixtures/alignment/cases/wrong-edition/case.json`:

```json
{
  "description": "The user paired the wrong audiobook with the book. Refusing is the only correct answer; a plausible-looking alignment here is the worst outcome the feature can produce.",
  "reference": "reference.txt",
  "spoken": "unrelated.txt",
  "expectedGranularity": "NONE",
  "seed": 4,
  "baseMs": 120,
  "perCharacterMs": 45,
  "gapMs": 40,
  "preamble": [],
  "dropRate": 0.0,
  "substituteRate": 0.0
}
```

`test-fixtures/alignment/cases/wrong-edition/unrelated.txt`:

```
Zephyr counted quokkas beside a gazebo of xylem. Halcyon ibex wandered past nimbus fjords.

Juniper lacquer festooned every obsidian trellis, and vermilion kestrels quarrelled above them.
```

- [ ] **Step 3: Generate the hypotheses and ground truth**

```bash
for case in test-fixtures/alignment/cases/*/; do python3 benchmarks/alignment/make_fixture.py "$case"; done
```

Expected: five lines, each naming a case with a token count and an onset count. The reference holds five sentences, so `clean-narration`, `narrator-preamble`, and `asr-dropouts` report 5 onsets each; `partial-narration` and `wrong-edition` report 0, because a narration of different text has no true onsets in this book.

- [ ] **Step 4: Write the gate**

Create `test-fixtures/alignment/gate.json`:

```json
{
  "medianAbsErrorMs": 250,
  "p95AbsErrorMs": 750,
  "minCoverage": 0.85,
  "falseSyncToleranceMs": 2000,
  "maxFalseSyncOnsets": 0
}
```

These are the gate, not knobs. If `clean-narration` cannot meet them, the aligner is wrong — a clean case has no ambiguity to lose to. Relaxing a number here is a research finding and belongs in `benchmarks/alignment/README.md` with the reason, not in a quiet edit.

- [ ] **Step 5: Write the failing validator test**

Create `benchmarks/alignment/tests/test_validate_fixture.py`:

```python
"""Tests for the fixture validator.

The generated files are checked in, so nothing stops someone hand-editing one. These are the
checks that catch it before the Kotlin gate starts failing for reasons that have nothing to do
with alignment.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import validate_fixture

REFERENCE = "Alpha bravo charlie delta. Echo foxtrot golf hotel."


class ValidateHypothesisTest(unittest.TestCase):
    def test_a_well_formed_hypothesis_passes(self):
        hypothesis = [
            {"text": "alpha", "startMs": 0, "endMs": 300, "confidence": 1.0},
            {"text": "bravo", "startMs": 340, "endMs": 640, "confidence": 1.0},
        ]
        self.assertEqual([], validate_fixture.check_hypothesis(hypothesis))

    def test_time_going_backwards_is_reported(self):
        hypothesis = [
            {"text": "alpha", "startMs": 900, "endMs": 1200, "confidence": 1.0},
            {"text": "bravo", "startMs": 100, "endMs": 400, "confidence": 1.0},
        ]
        self.assertEqual(1, len(validate_fixture.check_hypothesis(hypothesis)))

    def test_a_token_ending_before_it_starts_is_reported(self):
        hypothesis = [{"text": "alpha", "startMs": 900, "endMs": 400, "confidence": 1.0}]
        self.assertEqual(1, len(validate_fixture.check_hypothesis(hypothesis)))

    def test_a_blank_token_is_reported(self):
        hypothesis = [{"text": "  ", "startMs": 0, "endMs": 400, "confidence": 1.0}]
        self.assertEqual(1, len(validate_fixture.check_hypothesis(hypothesis)))


class ValidateExpectationTest(unittest.TestCase):
    def test_a_quote_present_in_the_reference_passes(self):
        expected = {"granularity": "WORD", "onsets": [{"quote": "Echo foxtrot golf", "onsetMs": 1200}]}
        self.assertEqual([], validate_fixture.check_expected(expected, REFERENCE))

    def test_a_quote_absent_from_the_reference_is_reported(self):
        expected = {"granularity": "WORD", "onsets": [{"quote": "kilo lima mike", "onsetMs": 1200}]}
        self.assertEqual(1, len(validate_fixture.check_expected(expected, REFERENCE)))

    def test_an_unknown_granularity_is_reported(self):
        expected = {"granularity": "PARAGRAPH", "onsets": []}
        self.assertEqual(1, len(validate_fixture.check_expected(expected, REFERENCE)))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `python3 -m unittest discover -s benchmarks/alignment/tests`
Expected: `ModuleNotFoundError: No module named 'validate_fixture'`.

- [ ] **Step 7: Write the validator**

Create `benchmarks/alignment/validate_fixture.py`:

```python
#!/usr/bin/env python3
"""Validate the checked-in alignment fixtures.

The Kotlin gate reads these files and reports a number. If a file is malformed the number is
still a number, and it looks like an alignment regression rather than a broken fixture, so the
shape is checked separately and first.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from make_fixture import words

ROOT = Path(__file__).resolve().parents[2]
CASES = ROOT / "test-fixtures" / "alignment" / "cases"
GRANULARITIES = {"WORD", "SENTENCE", "CHAPTER", "NONE"}


def fold(value: str) -> str:
    return "".join(character.lower() for character in value if character.isalnum())


def check_hypothesis(hypothesis: list) -> list[str]:
    errors: list[str] = []
    previous_start = -1
    for index, token in enumerate(hypothesis):
        if not isinstance(token.get("text"), str) or not token["text"].strip():
            errors.append(f"token {index}: text must be a non-blank string")
            continue
        start, end = token.get("startMs"), token.get("endMs")
        if not isinstance(start, int) or not isinstance(end, int) or start < 0 or end < start:
            errors.append(f"token {index}: startMs and endMs must be ordered non-negative integers")
            continue
        if start < previous_start:
            errors.append(f"token {index}: startMs moves backwards")
        previous_start = start
    return errors


def check_expected(expected: dict, reference: str) -> list[str]:
    errors: list[str] = []
    if expected.get("granularity") not in GRANULARITIES:
        errors.append(f"granularity {expected.get('granularity')!r} is not one of {sorted(GRANULARITIES)}")
    keys = [fold(word) for word in words(reference)]
    for onset in expected.get("onsets", []):
        quote = [fold(word) for word in words(onset.get("quote", ""))]
        if not quote:
            errors.append("an onset has an empty quote")
            continue
        if not any(keys[index:index + len(quote)] == quote for index in range(len(keys))):
            errors.append(f"quote {onset['quote']!r} does not occur in the reference")
        if not isinstance(onset.get("onsetMs"), int) or onset["onsetMs"] < 0:
            errors.append(f"quote {onset['quote']!r} must have a non-negative integer onsetMs")
    return errors


def main() -> int:
    errors: list[str] = []
    case_dirs = sorted(path for path in CASES.iterdir() if path.is_dir())
    if not case_dirs:
        print(f"no fixture cases found under {CASES}", file=sys.stderr)
        return 1
    for case_dir in case_dirs:
        case = json.loads((case_dir / "case.json").read_text(encoding="utf-8"))
        reference = (case_dir / case["reference"]).read_text(encoding="utf-8")
        hypothesis = json.loads((case_dir / "hypothesis.json").read_text(encoding="utf-8"))
        expected = json.loads((case_dir / "expected.json").read_text(encoding="utf-8"))
        for error in check_hypothesis(hypothesis) + check_expected(expected, reference):
            errors.append(f"{case_dir.name}: {error}")
        if expected["granularity"] != case["expectedGranularity"]:
            errors.append(f"{case_dir.name}: expected.json disagrees with case.json about granularity")

    for error in errors:
        print(error, file=sys.stderr)
    print(f"checked {len(case_dirs)} alignment fixture cases")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 8: Run the tests and the validator**

Run: `python3 -m unittest discover -s benchmarks/alignment/tests`
Expected: `OK`, 16 tests.

Run: `python3 benchmarks/alignment/validate_fixture.py`
Expected: `checked 5 alignment fixture cases`, exit status 0.

- [ ] **Step 9: Commit**

```bash
git add benchmarks/alignment test-fixtures/alignment
git commit -m "test(align): add the five alignment fixture cases and their validator"
```

---

## Task 11: The gate

This is the test that answers the question. It runs every fixture case through the real aligner and asserts four numbers: how far off a typical sentence onset is, how far off the worst ones are, how much of the text got matched at all, and how many times the aligner claimed word-level accuracy while being more than two seconds wrong. The last number is the one that must be zero.

**Files:**
- Create: `shared/align/src/jvmTest/kotlin/app/narratify/shared/align/AlignmentGateTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/align/src/jvmTest/kotlin/app/narratify/shared/align/AlignmentGateTest.kt`:

```kotlin
package app.narratify.shared.align

import com.narratify.domain.PublicationId
import com.narratify.domain.PublicationLocator
import com.narratify.domain.ResourceId
import com.narratify.domain.SemanticRole
import com.narratify.domain.SourceRange
import com.narratify.domain.SourceTextSpan
import com.narratify.domain.TextRange
import java.io.File
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The alignment quality gate.
 *
 * What this measures: whether the aligner recovers known word times from a transcript that has
 * been damaged in the ways real transcripts are damaged. What it does NOT measure: accuracy on
 * real audio. The narration in these fixtures is synthetic — durations are a linear function of
 * word length, there is no narrator, no microphone, and no acoustic model. A green run here means
 * the algorithm is sound, not that read-along works. That question belongs to the real-audio
 * evidence run described in `benchmarks/alignment/README.md`.
 */
class AlignmentGateTest {
    @Serializable
    private data class Gate(
        val medianAbsErrorMs: Long,
        val p95AbsErrorMs: Long,
        val minCoverage: Double,
        val falseSyncToleranceMs: Long,
        val maxFalseSyncOnsets: Int,
    )

    @Serializable
    private data class Case(val description: String, val reference: String, val expectedGranularity: AlignmentGranularity)

    @Serializable
    private data class Onset(val quote: String, val onsetMs: Long)

    @Serializable
    private data class Expected(val granularity: AlignmentGranularity, val onsets: List<Onset>)

    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = File("../../test-fixtures/alignment")
    private val publication = PublicationId("gate")
    private val resource = ResourceId("chapter-1.xhtml")

    /** One span per paragraph, which is the shape an EPUB resource arrives in. */
    private fun spansOf(text: String): List<SourceTextSpan> {
        val spans = mutableListOf<SourceTextSpan>()
        var offset = 0
        for (paragraph in text.split("\n\n")) {
            val trimmed = paragraph.trim()
            if (trimmed.isNotEmpty()) {
                val start = text.indexOf(trimmed, offset)
                spans.add(
                    SourceTextSpan(
                        displayText = trimmed,
                        locator = PublicationLocator(publicationId = publication, resourceId = resource),
                        semanticRole = SemanticRole.PARAGRAPH,
                        language = "en-US",
                        sourceRanges = listOf(SourceRange(resource, TextRange(start, start + trimmed.length))),
                    ),
                )
                offset = start + trimmed.length
            }
        }
        return spans
    }

    /** The index of the first book token of a quoted sentence opening. */
    private fun indexOfQuote(bookTokens: List<BookToken>, quote: String): Int {
        val wanted = quote.split(" ").map(AlignmentKey::fold).filter(String::isNotEmpty)
        val keys = bookTokens.map(BookToken::key)
        for (start in 0..keys.size - wanted.size) {
            if (keys.subList(start, start + wanted.size) == wanted) return start
        }
        return -1
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0L
        val position = ((sorted.size - 1) * fraction).toInt()
        return sorted[position]
    }

    @Test
    fun `every fixture case aligns within the gate`() {
        val gate = json.decodeFromString<Gate>(File(fixtures, "gate.json").readText())
        val caseDirs = File(fixtures, "cases").listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }
        assertTrue(caseDirs.isNotEmpty(), "no fixture cases found in ${fixtures.absolutePath}")

        val errors = mutableListOf<Long>()
        val coverages = mutableListOf<Double>()
        var falseSync = 0
        val report = StringBuilder()

        for (caseDir in caseDirs) {
            val case = json.decodeFromString<Case>(File(caseDir, "case.json").readText())
            val expected = json.decodeFromString<Expected>(File(caseDir, "expected.json").readText())
            val hypothesis = json.decodeFromString<List<AsrToken>>(File(caseDir, "hypothesis.json").readText())
            val bookTokens = BookTokenizer.tokenize(spansOf(File(caseDir, case.reference).readText()))

            val result = ForcedAligner.align(bookTokens, hypothesis)

            assertEquals(
                case.expectedGranularity,
                result.granularity,
                "${caseDir.name}: ${case.description}",
            )
            if (case.expectedGranularity == AlignmentGranularity.WORD) {
                coverages.add(result.matchedRatio)
            }

            for (onset in expected.onsets) {
                val tokenIndex = indexOfQuote(bookTokens, onset.quote)
                assertTrue(tokenIndex >= 0, "${caseDir.name}: quote not found in the book: ${onset.quote}")
                val error = abs(result.timings[tokenIndex].startMs - onset.onsetMs)
                errors.add(error)
                val claimedWord = result.spans
                    .first { tokenIndex in it.bookTokenStart until it.bookTokenEndExclusive }
                    .granularity == AlignmentGranularity.WORD
                if (claimedWord && error > gate.falseSyncToleranceMs) falseSync += 1
            }
            report.append("${caseDir.name}: granularity=${result.granularity} matched=${result.matchedRatio}\n")
        }

        val sorted = errors.sorted()
        val median = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        val coverage = coverages.average()
        report.append("median=${median}ms p95=${p95}ms coverage=$coverage falseSync=$falseSync\n")

        assertTrue(median <= gate.medianAbsErrorMs, "median onset error ${median}ms exceeds ${gate.medianAbsErrorMs}ms\n$report")
        assertTrue(p95 <= gate.p95AbsErrorMs, "p95 onset error ${p95}ms exceeds ${gate.p95AbsErrorMs}ms\n$report")
        assertTrue(coverage >= gate.minCoverage, "coverage $coverage is below ${gate.minCoverage}\n$report")
        assertTrue(
            falseSync <= gate.maxFalseSyncOnsets,
            "$falseSync onsets claimed word accuracy while being more than ${gate.falseSyncToleranceMs}ms wrong\n$report",
        )
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.AlignmentGateTest"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: If the gate fails, diagnose before touching a threshold**

The assertion messages print per-case granularity and matched ratio. Work through them in this order, and do not edit `gate.json` until all three are ruled out:

1. **`clean-narration` is not `WORD`, or its onsets are off at all.** There is nothing ambiguous in that case. The bug is in `AlignmentMatcher`, `AnchorFinder`, or `ForcedAligner.timings`.
2. **`narrator-preamble` fails while `clean-narration` passes.** The preamble is shifting the result, which means something is anchoring on position rather than content.
3. **`wrong-edition` is anything other than `NONE`.** Thresholds in `AlignmentOptions` are letting a non-match through. That is the most serious possible failure of this feature and outranks every other number here.

Only if all five cases are behaving and the *distribution* is wider than the gate is the threshold itself the question — and then record the new number and the reason in `benchmarks/alignment/README.md`, in the same commit.

- [ ] **Step 4: Commit**

```bash
git add shared/align
git commit -m "test(align): gate alignment on onset error, coverage, and false sync"
```

---

## Task 12: The whisper.cpp adapter

The engine takes an ASR hypothesis; nothing in this plan produces one from real audio. This adapter closes that gap so the real-audio evidence run is a command rather than a project. It is desktop evidence tooling, exactly like `benchmarks/tts/adapters/` — no weights, no binary, and no claim that this is how the app will transcribe anything on a phone.

**Files:**
- Create: `benchmarks/alignment/whisper_adapter.py`
- Create: `benchmarks/alignment/ADAPTER_PROTOCOL.md`
- Test: `benchmarks/alignment/tests/test_whisper_adapter.py`

- [ ] **Step 1: Write the failing test**

Create `benchmarks/alignment/tests/test_whisper_adapter.py`:

```python
"""Tests for the whisper.cpp output converter.

whisper.cpp emits byte-pair tokens, not words: "harbour" can arrive as " har" + "bour", and
timestamps and specials are interleaved with them. Getting the word boundaries wrong here would
be invisible in the output and would show up as an alignment quality problem, so the merge rules
are pinned rather than eyeballed.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import whisper_adapter


def token(text, start, end, probability=0.9):
    return {"text": text, "offsets": {"from": start, "to": end}, "p": probability}


class ConvertTest(unittest.TestCase):
    def test_leading_space_starts_a_new_word(self):
        result = whisper_adapter.convert(
            {"transcription": [{"tokens": [token(" the", 0, 300), token(" lantern", 340, 900)]}]}
        )
        self.assertEqual([entry["text"] for entry in result], ["the", "lantern"])

    def test_subword_pieces_are_merged_into_one_word(self):
        result = whisper_adapter.convert(
            {"transcription": [{"tokens": [token(" har", 0, 200), token("bour", 200, 420)]}]}
        )
        self.assertEqual([entry["text"] for entry in result], ["harbour"])
        self.assertEqual(result[0]["startMs"], 0)
        self.assertEqual(result[0]["endMs"], 420)

    def test_special_tokens_are_dropped(self):
        result = whisper_adapter.convert(
            {"transcription": [{"tokens": [token("[_BEG_]", 0, 0), token(" the", 0, 300)]}]}
        )
        self.assertEqual([entry["text"] for entry in result], ["the"])

    def test_punctuation_only_pieces_do_not_become_words(self):
        result = whisper_adapter.convert(
            {"transcription": [{"tokens": [token(" the", 0, 300), token(".", 300, 320)]}]}
        )
        self.assertEqual([entry["text"] for entry in result], ["the"])

    def test_confidence_of_a_merged_word_is_its_weakest_piece(self):
        result = whisper_adapter.convert(
            {"transcription": [{"tokens": [token(" har", 0, 200, 0.9), token("bour", 200, 420, 0.4)]}]}
        )
        self.assertAlmostEqual(result[0]["confidence"], 0.4)

    def test_segments_are_concatenated_in_order(self):
        result = whisper_adapter.convert(
            {
                "transcription": [
                    {"tokens": [token(" alpha", 0, 300)]},
                    {"tokens": [token(" bravo", 400, 700)]},
                ]
            }
        )
        self.assertEqual([entry["startMs"] for entry in result], [0, 400])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m unittest discover -s benchmarks/alignment/tests`
Expected: `ModuleNotFoundError: No module named 'whisper_adapter'`.

- [ ] **Step 3: Write the adapter**

Create `benchmarks/alignment/whisper_adapter.py`:

```python
#!/usr/bin/env python3
"""Convert whisper.cpp full-JSON output into an alignment hypothesis.

Desktop evidence tooling. No model weights and no binary are checked in, and nothing here is a
statement about how the app will transcribe audio on a device — that carries its own licensing,
distribution, and thermal gates and has not been decided.

Prepare audio and transcribe first:

    ffmpeg -i chapter-03.m4b -ar 16000 -ac 1 -c:a pcm_s16le chapter-03.wav
    whisper-cli -m ggml-base.en.bin -f chapter-03.wav --output-json-full --output-file chapter-03

Then convert:

    python3 benchmarks/alignment/whisper_adapter.py chapter-03.json --output hypothesis.json
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def _is_word_character(value: str) -> bool:
    return any(character.isalnum() for character in value)


def convert(payload: dict) -> list[dict]:
    """Merge whisper's byte-pair tokens into words carrying the audio span of all their pieces."""
    words: list[dict] = []
    for segment in payload.get("transcription", []):
        for token in segment.get("tokens", []):
            text = token.get("text", "")
            # Specials such as [_BEG_] and <|endoftext|> carry no audio and no word.
            if text.startswith("[_") or text.startswith("<|"):
                continue
            starts_word = text.startswith(" ") or not words
            stripped = text.strip()
            if not _is_word_character(stripped):
                continue
            offsets = token.get("offsets", {})
            start, end = int(offsets.get("from", 0)), int(offsets.get("to", 0))
            probability = float(token.get("p", 1.0))
            if starts_word:
                words.append(
                    {"text": stripped, "startMs": start, "endMs": end, "confidence": probability}
                )
            else:
                current = words[-1]
                current["text"] += stripped
                current["endMs"] = max(current["endMs"], end)
                current["confidence"] = min(current["confidence"], probability)
    return words


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="whisper.cpp --output-json-full file")
    parser.add_argument("--output", type=Path, required=True, help="hypothesis.json to write")
    arguments = parser.parse_args()

    hypothesis = convert(json.loads(arguments.input.read_text(encoding="utf-8")))
    arguments.output.write_text(json.dumps(hypothesis, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{arguments.output}: {len(hypothesis)} words")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m unittest discover -s benchmarks/alignment/tests`
Expected: `OK`, 22 tests.

- [ ] **Step 5: Write the protocol document**

Create `benchmarks/alignment/ADAPTER_PROTOCOL.md`:

````markdown
# Alignment hypothesis protocol

Any speech recognizer can feed the aligner by producing one JSON file: an array of word objects,
in the order they were spoken.

```json
[
  {"text": "the", "startMs": 0, "endMs": 320, "confidence": 0.97},
  {"text": "lantern", "startMs": 360, "endMs": 940, "confidence": 0.93}
]
```

| Field | Type | Rule |
|---|---|---|
| `text` | string | One word. Non-blank. Case, punctuation, and apostrophes are ignored by the aligner, so normalizing them is wasted work. |
| `startMs` | integer | Milliseconds from the start of the media item. Non-negative. Non-decreasing across the array. |
| `endMs` | integer | Not less than `startMs`. |
| `confidence` | number | 0.0 to 1.0. Currently recorded but not used to weight matching; a recognizer with no confidence should emit `1.0`. |

Rules the aligner depends on:

- **Words, not sub-word pieces.** A recognizer that emits byte-pair tokens must merge them, as
  `whisper_adapter.py` does. Un-merged pieces will not match book words and coverage collapses.
- **One media item per file.** Times are relative to that item. An M4B is one item per chapter
  mark; a folder of MP3s is one item per file.
- **Order is the contract.** The aligner never re-sorts. A recognizer that emits segments out of
  order must sort before writing.

The aligner does not need, and ignores: speaker labels, punctuation tokens, segment text,
language identification, and per-token log-probabilities beyond `confidence`.
````

- [ ] **Step 6: Commit**

```bash
git add benchmarks/alignment
git commit -m "feat(align): convert whisper.cpp output into alignment hypotheses"
```

---

## Task 13: Wiring the gate into CI and saying what it proves

Everything built so far runs on a laptop with no audio, no weights, and no device, which means it belongs in the same CI job as the rest of the foundation. The README is the part that keeps a green badge from being read as a working feature.

**Files:**
- Create: `benchmarks/alignment/README.md`
- Modify: `.github/workflows/verify.yml:37`
- Modify: `README.md:8`
- Modify: `IMPLEMENTATION_PLAN.md:430`

- [ ] **Step 1: Write the README**

Create `benchmarks/alignment/README.md`:

````markdown
# Narratify alignment gate

This directory holds the repeatable quality gate for syncing a user-supplied MP3 or M4B narration
to the matching ebook text. It contains no model weights, no audio, and no recognizer.

## What the gate proves, and what it does not

The checked-in fixtures are **synthetic**. Word durations are a linear function of word length,
there is no narrator, no microphone, and no acoustic model. The gate measures whether the aligner
recovers known word times from a transcript damaged the way real transcripts are damaged: words
dropped, words misheard, narration that is not in the book, narration of only part of the book,
and narration of an entirely different book.

A green gate means **the algorithm is sound**. It does not mean read-along works. Nobody should
cite this gate as evidence of accuracy on real audiobooks.

## Running it

```sh
python3 -m unittest discover -s benchmarks/alignment/tests
python3 benchmarks/alignment/validate_fixture.py
./gradlew :shared:align:jvmTest
```

## Regenerating the fixtures

The hypotheses and ground truth are generated and checked in, so the gate is reproducible without
running Python. Regenerate after changing any `case.json`:

```sh
for case in test-fixtures/alignment/cases/*/; do python3 benchmarks/alignment/make_fixture.py "$case"; done
python3 benchmarks/alignment/validate_fixture.py
```

## The thresholds

`test-fixtures/alignment/gate.json` holds the gate. `maxFalseSyncOnsets` is the one that matters
most: it counts places where the aligner claimed word-level accuracy while being more than two
seconds wrong. It is zero, and it stays zero. A highlight that is visibly lying is worse than no
highlight, because the reader stops trusting the whole feature and cannot tell which parts were
trustworthy.

Changing any threshold is a research finding. Record the new number and the reason here, in the
same commit as the change.

## Real-audio evidence (not yet run)

`whisper_adapter.py` converts whisper.cpp word timings into the hypothesis format described in
`ADAPTER_PROTOCOL.md`, so a real run is:

```sh
ffmpeg -i chapter-03.m4b -ar 16000 -ac 1 -c:a pcm_s16le chapter-03.wav
whisper-cli -m ggml-base.en.bin -f chapter-03.wav --output-json-full --output-file chapter-03
python3 benchmarks/alignment/whisper_adapter.py chapter-03.json --output hypothesis.json
```

No such run is recorded yet. Doing it properly needs LibriVox recordings paired with their
Gutenberg source texts, sentence onsets annotated by hand, and the result written up the way
`benchmarks/tts/GATE_0_KOKORO_EVIDENCE.md` writes up Gate 0. That is a separate piece of work,
and its numbers — not the ones in this directory — are what decide whether the feature ships.

## What is still gated

Running a recognizer on a phone is a separate decision with its own licensing, model
distribution, thermal, and battery gates, in the same shape as Gate 0 for neural TTS. Nothing in
this directory implies that decision has been made.
````

- [ ] **Step 2: Add the checks to CI**

In `.github/workflows/verify.yml`, append a step after the existing `Verify the TTS benchmark contract` step (which ends at line 37):

```yaml
      - name: Verify the alignment gate
        run: |
          python3 -m unittest discover -s benchmarks/alignment/tests
          python3 benchmarks/alignment/validate_fixture.py
```

The Kotlin side needs no new step: `./gradlew check` at line 26 already covers every subproject, and `:shared:align` is now one of them.

- [ ] **Step 3: Verify CI would pass, locally**

Run:

```bash
python3 -m unittest discover -s benchmarks/alignment/tests && python3 benchmarks/alignment/validate_fixture.py && ./gradlew check
```

Expected: `OK` (22 tests), `checked 5 alignment fixture cases`, `BUILD SUCCESSFUL`.

- [ ] **Step 4: Describe the module in the repository README**

In `README.md`, add one bullet to the `## Current modules` list after the `shared/text` bullet:

```markdown
- `shared/align`: deterministic anchor-and-fill alignment between a chapter's text and a transcript of its narration, with confidence-gated granularity that refuses to claim word-level sync it cannot support.
```

- [ ] **Step 5: Record where the research now stands**

In `IMPLEMENTATION_PLAN.md`, replace the deferred-roadmap paragraph at line 430:

```markdown
Exact ebook-to-audiobook alignment should be treated as a separate research project involving edition matching and offline forced alignment. It should not share a delivery milestone with ordinary position persistence. Its first half is built: `shared/align` holds the deterministic aligner and `benchmarks/alignment` holds a synthetic quality gate, both described in `docs/superpowers/plans/2026-09-14-audiobook-forced-alignment.md`. Two things remain before this can be scheduled as a feature — a real-audio evidence run against annotated LibriVox recordings, and a decision on running a recognizer on device, which carries its own licensing, distribution, and thermal gates.
```

Leave the exclusion at `IMPLEMENTATION_PLAN.md:35` exactly as it is. It is still true: nothing here aligns separately purchased editions, and the aligner's own answer for a mismatched edition is to refuse.

- [ ] **Step 6: Commit**

```bash
git add benchmarks/alignment README.md IMPLEMENTATION_PLAN.md .github/workflows/verify.yml
git commit -m "docs(align): gate alignment in CI and record what the gate does not prove"
```

---

## Verification

Run all of it before reporting completion:

```bash
python3 -m unittest discover -s benchmarks/alignment/tests && python3 benchmarks/alignment/validate_fixture.py && ./gradlew check
```

Expected: 22 Python tests `OK`, `checked 5 alignment fixture cases`, `BUILD SUCCESSFUL`.

Report the gate's own numbers, not just that it passed — the assertion message in
`AlignmentGateTest` prints median error, p95 error, coverage, and false-sync count, and those
four numbers are the deliverable of this plan. Run with `--info` if the test passes and the
report is not printed, or temporarily add a `println(report)` before the assertions.

## Follow-on plans

Do not fold these into this one.

1. **Gate A1: real-audio evidence.** Pair LibriVox recordings with Gutenberg texts, transcribe
   with `whisper_adapter.py`, annotate true sentence onsets by hand, and write up the result as
   `docs/alignment/GATE_A1_EVIDENCE.md`. This is the experiment that decides whether the feature
   is worth building; everything above is the instrument.
2. **On-device recognition.** Licensing, model distribution, thermal, and battery gates for
   running a recognizer on a phone, in the shape of `docs/NEURAL_TTS_EXECUTION_PLAN.md`.
3. **Persistence and playback.** Store maps in `derived_artifact` keyed on
   `(publication, media item, producer_version, schema_version)`, run alignment as a resumable
   per-chapter background job, and drive the existing word-highlighting renderer from
   `AlignedSpan` — the same path TTS highlighting already uses.
4. **Tier 2, independently.** M4B chapter marks mapped to EPUB spine items give chapter-level
   jumping with no recognizer, no gate, and no risk. It is already listed at
   `IMPLEMENTATION_PLAN.md:436` and does not depend on any of this.
