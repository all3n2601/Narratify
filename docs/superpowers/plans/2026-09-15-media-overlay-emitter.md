# EPUB3 Media Overlays Emitter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a finished `AlignmentMap` into an EPUB3 Media Overlay, so a computed alignment can be baked into the book and played by any EPUB3 reader rather than only by Narratify.

**Architecture:** A pure serializer in `:shared:align` with no I/O and no XML library. `MediaOverlayWriter.write` takes an `AlignmentMap`, an audio href, and a `TextAnchorResolver`, and returns SMIL text plus the package-document fragments that link it up. Resolution of text anchors is injected rather than computed, because token offsets live in whatever coordinate space the caller declared when it built its `SourceTextSpan`s — for plain text that is character offsets, for an EPUB via Readium it is something else entirely, and `TtsTextPreparer.sliceRanges` simply maps through whatever it was given. The writer refuses to emit anything for an alignment that did not earn word or sentence granularity.

**Tech Stack:** Kotlin Multiplatform (`:shared:align`), `kotlin.test`, Gradle. JVM-only golden-file and well-formedness tests in `jvmTest`.

**Context:** This builds on `docs/superpowers/plans/2026-09-14-audiobook-forced-alignment.md`, which produced the alignment engine and its quality gate. `AlignedSpan` already carries a book token range plus `startMs`/`endMs` — which is a SMIL `<par>` element with the names changed.

---

## Why refusal is the central behaviour

A Media Overlay is a published claim of exact synchronisation. Once written into an EPUB it travels, and any reader that opens the book will act on it.

The alignment engine already grades every result: `WORD` when the matched share cleared its threshold, `SENTENCE` when only the sentence is trustworthy, `CHAPTER` when nothing finer was established, and `NONE` when the narration and the text did not agree enough to claim anything. The emitter's job is to carry that judgement into the file format rather than quietly discarding it. A `CHAPTER` or `NONE` alignment produces no SMIL at all, and individual spans that fall below sentence granularity are skipped rather than emitted with a guessed clip range. SMIL tolerates text elements that no `<par>` points at; it does not tolerate being wrong.

## Scope

**In scope:** the serializer, the package-document fragments, a core-media-type guard, and tests including a checked-in golden file.

**Out of scope, deliberately:**

- **Injecting `id` attributes into content documents.** SMIL points at `chapter1.xhtml#someId`, so the text must already carry ids at the granularity being claimed. Adding them means parsing and rewriting XHTML, which needs a real XML library on three platforms and is its own project. `TextAnchorResolver` is the seam where that would plug in later.
- **Building or repackaging the EPUB container.** This emits text; something else writes the zip.
- **Running `epubcheck`.** It validates a whole EPUB, which this plan does not produce. The tests assert well-formed XML and a golden file instead.

## File structure

| File | Responsibility |
|---|---|
| `shared/align/src/commonMain/kotlin/app/narratify/shared/align/SmilClock.kt` | Milliseconds to the SMIL clock format and back. |
| `.../AudioMediaType.kt` | Maps an audio href to an EPUB3 core media type, refusing anything that is not one. |
| `.../MediaOverlayModels.kt` | `TextAnchorResolver`, `MediaOverlayDocument`. |
| `.../MediaOverlayWriter.kt` | The serializer: merging, refusal, SMIL text. |
| `.../PackageDocumentFragments.kt` | The manifest items and `media:duration` metadata that link a SMIL file to its content document. |
| `shared/align/src/commonTest/kotlin/.../*Test.kt` | Unit tests for each. |
| `shared/align/src/jvmTest/kotlin/.../MediaOverlayGoldenTest.kt` | Parses the emitted SMIL to prove it is well-formed XML, and diffs it against a checked-in golden file. |
| `test-fixtures/alignment/media-overlay/chapter-1.smil` | The golden file. |

---

## Task 1: The SMIL clock

SMIL clip boundaries are written as a clock value, not a number of milliseconds. Getting the format wrong produces a file that parses and then plays at the wrong time, which is the failure mode this whole project exists to avoid.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/SmilClock.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/SmilClockTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmilClockTest {
    @Test
    fun `zero is written in full rather than abbreviated`() {
        assertEquals("0:00:00.000", SmilClock.format(0L))
    }

    @Test
    fun `milliseconds keep three digits`() {
        assertEquals("0:00:03.120", SmilClock.format(3_120L))
        assertEquals("0:00:03.007", SmilClock.format(3_007L))
    }

    @Test
    fun `minutes and seconds keep two digits`() {
        assertEquals("0:02:05.000", SmilClock.format(125_000L))
    }

    @Test
    fun `hours are not padded and do not wrap`() {
        assertEquals("1:02:03.456", SmilClock.format(3_723_456L))
        assertEquals("27:00:00.000", SmilClock.format(97_200_000L))
    }

    @Test
    fun `a negative time has no meaning and is refused`() {
        assertFailsWith<IllegalArgumentException> { SmilClock.format(-1L) }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.SmilClockTest"`
Expected: compilation failure, `Unresolved reference: SmilClock`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.narratify.shared.align

/**
 * The SMIL clock value, `H:MM:SS.mmm`.
 *
 * Hours are not padded and deliberately do not wrap at 24: an audiobook is routinely longer than
 * a day, and a clip that wrapped would point at the beginning of the file instead of the end.
 */
object SmilClock {
    fun format(milliseconds: Long): String {
        require(milliseconds >= 0) { "A clip time must be non-negative" }
        val fraction = milliseconds % 1000
        val totalSeconds = milliseconds / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return "$hours:${pad(minutes, 2)}:${pad(seconds, 2)}.${pad(fraction, 3)}"
    }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.SmilClockTest"`
Expected: `BUILD SUCCESSFUL`, 5 tests passing.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): format milliseconds as a SMIL clock value"
```

---

## Task 2: The core media type guard

EPUB3 accepts a fixed set of audio types. An M4B is MP4/AAC with chapter marks and a different extension — remuxing it to `.m4a` is a stream copy, but shipping it as `.m4b` produces an EPUB that fails validation. That is worth catching in code with an error message that says what to do about it.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/AudioMediaType.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/AudioMediaTypeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioMediaTypeTest {
    @Test
    fun `mp3 and mp4 audio are core media types`() {
        assertEquals("audio/mpeg", AudioMediaType.forHref("audio/book.mp3"))
        assertEquals("audio/mp4", AudioMediaType.forHref("audio/book.m4a"))
        assertEquals("audio/mp4", AudioMediaType.forHref("audio/book.mp4"))
    }

    @Test
    fun `the extension is matched without regard to case`() {
        assertEquals("audio/mpeg", AudioMediaType.forHref("AUDIO/BOOK.MP3"))
    }

    @Test
    fun `an m4b is refused even though its contents would be acceptable`() {
        assertNull(AudioMediaType.forHref("audio/book.m4b"))
    }

    @Test
    fun `an unknown extension is refused`() {
        assertNull(AudioMediaType.forHref("audio/book.flac"))
        assertNull(AudioMediaType.forHref("audio/book"))
    }

    @Test
    fun `the refusal explains what to do about an m4b`() {
        val explanation = AudioMediaType.explainRefusal("audio/book.m4b")
        assertEquals(true, explanation.contains("m4a"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.AudioMediaTypeTest"`
Expected: compilation failure, `Unresolved reference: AudioMediaType`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.narratify.shared.align

/**
 * The EPUB3 core media types for audio.
 *
 * An `.m4b` is refused despite usually containing exactly the AAC-in-MP4 that `audio/mp4` names,
 * because the extension is not one a reading system is required to accept. The fix is a stream
 * copy, not a re-encode, so the refusal says so rather than leaving the caller to guess.
 */
object AudioMediaType {
    private val byExtension = mapOf(
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "mp4" to "audio/mp4",
        "aac" to "audio/mp4",
    )

    fun forHref(href: String): String? =
        byExtension[href.substringAfterLast('.', missingDelimiterValue = "").lowercase()]

    fun explainRefusal(href: String): String {
        val extension = href.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return if (extension == "m4b") {
            "$href is an M4B. Its audio is usually already acceptable, so remux rather than " +
                "re-encode it: ffmpeg -i $href -c copy -map 0:a ${href.dropLast(1)}a"
        } else {
            "$href is not an EPUB3 core audio media type. Convert it to MP3 or AAC in MP4."
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.AudioMediaTypeTest"`
Expected: `BUILD SUCCESSFUL`, 5 tests passing.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): accept only EPUB3 core audio media types"
```

---

## Task 3: The anchor seam and the emitted document

`AlignedSpan` knows a range of book tokens; SMIL needs the id of an element in the content document. Nothing in this module can bridge that, because token offsets are expressed in whatever coordinate space the caller declared. So the bridge is an interface the caller supplies.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/MediaOverlayModels.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/MediaOverlayModelsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaOverlayModelsTest {
    private fun span(start: Int, endExclusive: Int) =
        AlignedSpan(start, endExclusive, 0L, 100L, 1.0, AlignmentGranularity.WORD)

    @Test
    fun `a resolver is a function from a span to an element id`() {
        val resolver = TextAnchorResolver { "s${it.bookTokenStart}" }
        assertEquals("s4", resolver.anchor(span(4, 8)))
    }

    @Test
    fun `a resolver may decline a span it has no element for`() {
        val resolver = TextAnchorResolver { null }
        assertEquals(null, resolver.anchor(span(0, 4)))
    }

    @Test
    fun `a document reports what it emitted`() {
        val document = MediaOverlayDocument(smil = "<smil/>", durationMs = 1_000L, parCount = 3)
        assertEquals(3, document.parCount)
        assertEquals(1_000L, document.durationMs)
    }

    @Test
    fun `a document with no pars is not a document`() {
        assertFailsWith<IllegalArgumentException> {
            MediaOverlayDocument(smil = "<smil/>", durationMs = 1_000L, parCount = 0)
        }
    }

    @Test
    fun `a document cannot have a negative duration`() {
        assertFailsWith<IllegalArgumentException> {
            MediaOverlayDocument(smil = "<smil/>", durationMs = -1L, parCount = 1)
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.MediaOverlayModelsTest"`
Expected: compilation failure, `Unresolved reference: TextAnchorResolver`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.narratify.shared.align

/**
 * Supplies the id of the element in the content document that a span's text lives in.
 *
 * This is an interface rather than a calculation because nothing in this module can perform it.
 * A `SourceRange` is expressed in whatever coordinate space the caller used when it built its
 * `SourceTextSpan`s — character offsets into extracted plain text, positions in a Readium
 * locator, something else again — and `TtsTextPreparer` maps through that space without
 * interpreting it. Only the component that produced the spans knows how to reach markup from
 * them.
 *
 * Returning null means there is no element for this span, and the span is left out of the
 * overlay. That is legal: a reading system does not require every element to be narrated.
 */
fun interface TextAnchorResolver {
    fun anchor(span: AlignedSpan): String?
}

/** A finished Media Overlay, ready to be written into a publication. */
data class MediaOverlayDocument(
    val smil: String,
    val durationMs: Long,
    val parCount: Int,
) {
    init {
        require(smil.isNotBlank()) { "A media overlay must have content" }
        require(durationMs >= 0) { "Duration must be non-negative" }
        require(parCount > 0) { "A media overlay with no pars claims nothing and should not exist" }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.MediaOverlayModelsTest"`
Expected: `BUILD SUCCESSFUL`, 5 tests passing.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): add the media overlay anchor seam and document type"
```

---

## Task 4: The writer

The serializer proper. Three behaviours carry weight: refusing an alignment that did not earn the claim, skipping individual spans that did not, and merging consecutive spans that share an anchor so one element does not get several competing clip ranges.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/MediaOverlayWriter.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/MediaOverlayWriterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package app.narratify.shared.align

import com.narratify.domain.MediaItemId
import com.narratify.domain.PublicationId
import com.narratify.domain.ResourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaOverlayWriterTest {
    private fun span(
        start: Int,
        endExclusive: Int,
        startMs: Long,
        endMs: Long,
        granularity: AlignmentGranularity = AlignmentGranularity.WORD,
    ) = AlignedSpan(start, endExclusive, startMs, endMs, 1.0, granularity)

    private fun map(
        granularity: AlignmentGranularity,
        spans: List<AlignedSpan>,
        resourceId: String = "chapter-1.xhtml",
    ) = AlignmentMap(
        publicationId = PublicationId("p"),
        mediaItemId = MediaItemId("m"),
        resourceId = ResourceId(resourceId),
        granularity = granularity,
        spans = spans,
    )

    private val perSpan = TextAnchorResolver { "s${it.bookTokenStart}" }

    @Test
    fun `a word alignment becomes one par per span`() {
        val document = MediaOverlayWriter.write(
            map(AlignmentGranularity.WORD, listOf(span(0, 4, 0, 3_120), span(4, 8, 3_120, 6_480))),
            audioHref = "audio/book.m4a",
            resolver = perSpan,
        )
        assertEquals(2, document?.parCount)
        assertEquals(6_480L, document?.durationMs)
        assertTrue(document!!.smil.contains("""<text src="chapter-1.xhtml#s0"/>"""))
        assertTrue(document.smil.contains("""clipBegin="0:00:03.120" clipEnd="0:00:06.480""""))
    }

    @Test
    fun `an alignment that earned no claim produces nothing at all`() {
        assertNull(
            MediaOverlayWriter.write(
                // The span must be CHAPTER too: AlignmentMap refuses a summary that sits outside
                // the range of its spans, so a CHAPTER map full of WORD spans cannot be built.
                map(
                    AlignmentGranularity.CHAPTER,
                    listOf(span(0, 4, 0, 3_120, AlignmentGranularity.CHAPTER)),
                ),
                audioHref = "audio/book.m4a",
                resolver = perSpan,
            ),
        )
        assertNull(
            MediaOverlayWriter.write(
                map(AlignmentGranularity.NONE, emptyList()),
                audioHref = "audio/book.m4a",
                resolver = perSpan,
            ),
        )
    }

    @Test
    fun `a span that earned no claim is left out rather than guessed`() {
        val document = MediaOverlayWriter.write(
            map(
                AlignmentGranularity.SENTENCE,
                listOf(
                    span(0, 4, 0, 3_120),
                    span(4, 8, 3_120, 6_480, AlignmentGranularity.CHAPTER),
                    span(8, 12, 6_480, 9_000),
                ),
            ),
            audioHref = "audio/book.m4a",
            resolver = perSpan,
        )
        assertEquals(2, document?.parCount)
        assertTrue(document!!.smil.contains("#s0"))
        assertTrue(document.smil.contains("#s8"))
        assertTrue(!document.smil.contains("#s4"))
    }

    @Test
    fun `consecutive spans sharing an element become one par`() {
        val document = MediaOverlayWriter.write(
            map(AlignmentGranularity.WORD, listOf(span(0, 4, 0, 3_120), span(4, 8, 3_120, 6_480))),
            audioHref = "audio/book.m4a",
            resolver = TextAnchorResolver { "p1" },
        )
        assertEquals(1, document?.parCount)
        assertTrue(document!!.smil.contains("""clipBegin="0:00:00.000" clipEnd="0:00:06.480""""))
    }

    @Test
    fun `a span the resolver declines is left out`() {
        val document = MediaOverlayWriter.write(
            map(AlignmentGranularity.WORD, listOf(span(0, 4, 0, 3_120), span(4, 8, 3_120, 6_480))),
            audioHref = "audio/book.m4a",
            resolver = TextAnchorResolver { if (it.bookTokenStart == 0) "s0" else null },
        )
        assertEquals(1, document?.parCount)
    }

    @Test
    fun `an alignment nothing could be anchored to produces nothing`() {
        assertNull(
            MediaOverlayWriter.write(
                map(AlignmentGranularity.WORD, listOf(span(0, 4, 0, 3_120))),
                audioHref = "audio/book.m4a",
                resolver = TextAnchorResolver { null },
            ),
        )
    }

    @Test
    fun `audio that is not a core media type is refused`() {
        assertNull(
            MediaOverlayWriter.write(
                map(AlignmentGranularity.WORD, listOf(span(0, 4, 0, 3_120))),
                audioHref = "audio/book.m4b",
                resolver = perSpan,
            ),
        )
    }

    @Test
    fun `hrefs and ids are escaped so a quote cannot break the document`() {
        val document = MediaOverlayWriter.write(
            map(AlignmentGranularity.WORD, listOf(span(0, 4, 0, 3_120)), resourceId = "a&b.xhtml"),
            audioHref = "audio/book.m4a",
            resolver = TextAnchorResolver { """x"y""" },
        )
        assertTrue(document!!.smil.contains("a&amp;b.xhtml#x&quot;y"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.MediaOverlayWriterTest"`
Expected: compilation failure, `Unresolved reference: MediaOverlayWriter`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.narratify.shared.align

/**
 * Writes an [AlignmentMap] as an EPUB3 Media Overlay.
 *
 * A Media Overlay is a published claim of exact synchronisation: once written into a book it
 * travels, and any reading system that opens it will act on it. So the grading the aligner
 * already did is carried into the format rather than discarded. An alignment that reached only
 * chapter granularity, or none, produces nothing. An individual span below sentence granularity
 * is left out rather than given a guessed clip range — SMIL does not require every element to be
 * narrated, which makes silence the honest option.
 *
 * Returns null whenever there is nothing it can honestly emit.
 */
object MediaOverlayWriter {
    fun write(
        map: AlignmentMap,
        audioHref: String,
        resolver: TextAnchorResolver,
    ): MediaOverlayDocument? {
        if (map.granularity != AlignmentGranularity.WORD && map.granularity != AlignmentGranularity.SENTENCE) {
            return null
        }
        if (AudioMediaType.forHref(audioHref) == null) return null

        val anchored = map.spans
            .filter { it.granularity == AlignmentGranularity.WORD || it.granularity == AlignmentGranularity.SENTENCE }
            .mapNotNull { span -> resolver.anchor(span)?.let { anchor -> anchor to span } }
        if (anchored.isEmpty()) return null

        // One element must not receive several competing clip ranges, so consecutive spans that
        // resolve to the same id are played as one.
        val merged = mutableListOf<Triple<String, Long, Long>>()
        for ((anchor, span) in anchored) {
            val last = merged.lastOrNull()
            if (last != null && last.first == anchor) {
                merged[merged.lastIndex] = Triple(anchor, last.second, maxOf(last.third, span.endMs))
            } else {
                merged.add(Triple(anchor, span.startMs, span.endMs))
            }
        }

        val textHref = map.resourceId.value
        val smil = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<smil xmlns=\"http://www.w3.org/ns/SMIL\" ")
            append("xmlns:epub=\"http://www.idpf.org/2007/ops\" version=\"3.0\">\n")
            append("  <body>\n")
            append("    <seq id=\"seq1\" epub:textref=\"${textHref.escapeXml()}\" epub:type=\"chapter\">\n")
            merged.forEachIndexed { index, (anchor, startMs, endMs) ->
                append("      <par id=\"par${index + 1}\">\n")
                append("        <text src=\"${textHref.escapeXml()}#${anchor.escapeXml()}\"/>\n")
                append("        <audio src=\"${audioHref.escapeXml()}\" ")
                append("clipBegin=\"${SmilClock.format(startMs)}\" ")
                append("clipEnd=\"${SmilClock.format(endMs)}\"/>\n")
                append("      </par>\n")
            }
            append("    </seq>\n")
            append("  </body>\n")
            append("</smil>\n")
        }

        return MediaOverlayDocument(
            smil = smil,
            durationMs = merged.maxOf { it.third },
            parCount = merged.size,
        )
    }
}

internal fun String.escapeXml(): String = buildString(length) {
    for (character in this@escapeXml) {
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(character)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.MediaOverlayWriterTest"`
Expected: `BUILD SUCCESSFUL`, 8 tests passing.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): write an alignment as an EPUB3 media overlay"
```

---

## Task 5: The package document fragments

A SMIL file does nothing until the package document links it to its content document and declares its duration. Those two edits are easy to get subtly wrong, so they are generated rather than written by hand.

**Files:**
- Create: `shared/align/src/commonMain/kotlin/app/narratify/shared/align/PackageDocumentFragments.kt`
- Test: `shared/align/src/commonTest/kotlin/app/narratify/shared/align/PackageDocumentFragmentsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageDocumentFragmentsTest {
    @Test
    fun `the manifest links a content document to its overlay`() {
        val manifest = PackageDocumentFragments.manifestItems(
            textId = "c1",
            textHref = "chapter-1.xhtml",
            smilId = "c1smil",
            smilHref = "chapter-1.smil",
            audioId = "aud",
            audioHref = "audio/book.m4a",
        )
        assertTrue(manifest!!.contains("""media-overlay="c1smil""""))
        assertTrue(manifest.contains("""media-type="application/smil+xml""""))
        assertTrue(manifest.contains("""media-type="audio/mp4""""))
    }

    @Test
    fun `a manifest cannot be written for audio the format does not accept`() {
        assertNull(
            PackageDocumentFragments.manifestItems(
                textId = "c1",
                textHref = "chapter-1.xhtml",
                smilId = "c1smil",
                smilHref = "chapter-1.smil",
                audioId = "aud",
                audioHref = "audio/book.m4b",
            ),
        )
    }

    @Test
    fun `a per-overlay duration refines the overlay it belongs to`() {
        assertEquals(
            """<meta property="media:duration" refines="#c1smil">0:32:18.000</meta>""",
            PackageDocumentFragments.overlayDuration("c1smil", 1_938_000L),
        )
    }

    @Test
    fun `the total duration refines nothing`() {
        assertEquals(
            """<meta property="media:duration">4:12:05.000</meta>""",
            PackageDocumentFragments.totalDuration(15_125_000L),
        )
    }

    @Test
    fun `the active class is declared so a reader can style the spoken element`() {
        assertEquals(
            """<meta property="media:active-class">-epub-media-overlay-active</meta>""",
            PackageDocumentFragments.activeClass(),
        )
    }

    @Test
    fun `identifiers are escaped`() {
        assertTrue(PackageDocumentFragments.overlayDuration("""a"b""", 0L).contains("a&quot;b"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.PackageDocumentFragmentsTest"`
Expected: compilation failure, `Unresolved reference: PackageDocumentFragments`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.narratify.shared.align

/**
 * The package-document entries that turn a loose SMIL file into a working Media Overlay.
 *
 * These are generated rather than hand-written because the three links involved — the
 * `media-overlay` attribute pointing from the content document to the overlay, the overlay's own
 * duration, and the publication total — are each easy to write plausibly and wrongly, and a
 * reading system will simply refuse the book without saying which one is at fault.
 */
object PackageDocumentFragments {
    /** Returns null when the audio is not a type EPUB3 accepts. */
    fun manifestItems(
        textId: String,
        textHref: String,
        smilId: String,
        smilHref: String,
        audioId: String,
        audioHref: String,
    ): String? {
        val audioType = AudioMediaType.forHref(audioHref) ?: return null
        return buildString {
            append("<item id=\"${textId.escapeXml()}\" href=\"${textHref.escapeXml()}\" ")
            append("media-type=\"application/xhtml+xml\" media-overlay=\"${smilId.escapeXml()}\"/>\n")
            append("<item id=\"${smilId.escapeXml()}\" href=\"${smilHref.escapeXml()}\" ")
            append("media-type=\"application/smil+xml\"/>\n")
            append("<item id=\"${audioId.escapeXml()}\" href=\"${audioHref.escapeXml()}\" ")
            append("media-type=\"$audioType\"/>")
        }
    }

    fun overlayDuration(smilId: String, durationMs: Long): String =
        "<meta property=\"media:duration\" refines=\"#${smilId.escapeXml()}\">" +
            "${SmilClock.format(durationMs)}</meta>"

    fun totalDuration(durationMs: Long): String =
        "<meta property=\"media:duration\">${SmilClock.format(durationMs)}</meta>"

    fun activeClass(): String =
        "<meta property=\"media:active-class\">-epub-media-overlay-active</meta>"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.PackageDocumentFragmentsTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add shared/align
git commit -m "feat(align): generate the package document entries a media overlay needs"
```

---

## Task 6: Well-formedness and a golden file

The unit tests assert that particular substrings appear. That is not the same as the output being valid XML, and a serializer built from string concatenation can satisfy every substring assertion while emitting something no parser will accept.

**Files:**
- Create: `shared/align/src/jvmTest/kotlin/app/narratify/shared/align/MediaOverlayGoldenTest.kt`
- Create: `test-fixtures/alignment/media-overlay/chapter-1.smil`

- [ ] **Step 1: Write the failing test**

```kotlin
package app.narratify.shared.align

import com.narratify.domain.MediaItemId
import com.narratify.domain.PublicationId
import com.narratify.domain.ResourceId
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the emitted overlay is XML a parser accepts, and pins its exact text.
 *
 * The unit tests check that substrings appear, which a serializer can satisfy while producing
 * something unparseable. This parses the real output, and diffs it against a checked-in file so
 * that any change to the format is a visible change to a reviewable artifact.
 */
class MediaOverlayGoldenTest {
    private val golden = File("../../test-fixtures/alignment/media-overlay/chapter-1.smil")

    private fun document(): MediaOverlayDocument {
        val spans = listOf(
            AlignedSpan(0, 6, 0L, 3_120L, 1.0, AlignmentGranularity.WORD),
            AlignedSpan(6, 13, 3_120L, 6_480L, 1.0, AlignmentGranularity.WORD),
            AlignedSpan(13, 20, 6_480L, 11_907L, 0.6, AlignmentGranularity.SENTENCE),
        )
        val map = AlignmentMap(
            publicationId = PublicationId("p"),
            mediaItemId = MediaItemId("book.m4a"),
            resourceId = ResourceId("chapter-1.xhtml"),
            granularity = AlignmentGranularity.WORD,
            spans = spans,
        )
        return MediaOverlayWriter.write(
            map,
            audioHref = "audio/book.m4a",
            resolver = TextAnchorResolver { "s${it.bookTokenStart}" },
        )!!
    }

    @Test
    fun `the emitted overlay is well-formed xml`() {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val parsed = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(document().smil.toByteArray(Charsets.UTF_8)))
        assertEquals("smil", parsed.documentElement.localName)
        assertEquals(3, parsed.getElementsByTagNameNS("http://www.w3.org/ns/SMIL", "par").length)
    }

    @Test
    fun `the emitted overlay matches the checked-in golden file`() {
        val emitted = document().smil
        assertTrue(golden.isFile, "missing golden file at ${golden.absolutePath}")
        assertEquals(golden.readText(), emitted)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.MediaOverlayGoldenTest"`
Expected: the well-formedness test passes; the golden test fails with `missing golden file at ...`.

- [ ] **Step 3: Create the golden file**

Create `test-fixtures/alignment/media-overlay/chapter-1.smil` with exactly this content, including the trailing newline:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0">
  <body>
    <seq id="seq1" epub:textref="chapter-1.xhtml" epub:type="chapter">
      <par id="par1">
        <text src="chapter-1.xhtml#s0"/>
        <audio src="audio/book.m4a" clipBegin="0:00:00.000" clipEnd="0:00:03.120"/>
      </par>
      <par id="par2">
        <text src="chapter-1.xhtml#s6"/>
        <audio src="audio/book.m4a" clipBegin="0:00:03.120" clipEnd="0:00:06.480"/>
      </par>
      <par id="par3">
        <text src="chapter-1.xhtml#s13"/>
        <audio src="audio/book.m4a" clipBegin="0:00:06.480" clipEnd="0:00:11.907"/>
      </par>
    </seq>
  </body>
</smil>
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:align:jvmTest --tests "app.narratify.shared.align.MediaOverlayGoldenTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passing.

If the golden test fails on whitespace, do not reformat the golden file to match the code by eye — print the emitted string, compare it byte by byte, and fix whichever of the two is actually wrong. A golden file edited until it matches is worthless.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add shared/align test-fixtures/alignment/media-overlay
git commit -m "test(align): pin the media overlay output and prove it parses"
```

---

## Verification

```bash
./gradlew check && python3 -m unittest discover -s benchmarks/alignment/tests && python3 benchmarks/alignment/validate_fixture.py
```

Expected: `BUILD SUCCESSFUL`, 22 Python tests `OK`, `checked 7 alignment fixture cases`.

## Follow-on work

1. **A `TextAnchorResolver` for real EPUBs.** The seam exists; nothing implements it yet against a Readium publication. Doing so means deciding how to reach markup ids from a `SourceRange`, and what to do when the content document has no ids at the needed granularity.
2. **Injecting ids into content documents.** The harder half of the same problem: parsing XHTML, wrapping text ranges in `<span id>`, and rewriting the document. Needs an XML library on all three platforms.
3. **An `epubcheck` run.** Once something assembles a whole EPUB, validate it. That is the only way to know a reading system will actually accept what this emits.
