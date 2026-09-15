package app.narratify.shared.text

import com.narratify.domain.PublicationId
import com.narratify.domain.PublicationLocator
import com.narratify.domain.ResourceId
import com.narratify.domain.SemanticRole
import com.narratify.domain.SourceRange
import com.narratify.domain.SourceTextSpan
import com.narratify.domain.SpokenTokenFlag
import com.narratify.domain.TextDirection
import com.narratify.domain.TextRange
import com.narratify.domain.TextVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TtsTextPreparerTest {
    @Test
    fun abbreviationsAndDecimalsDoNotCreateFalseSentences() {
        val text = "Dr. Mina arrived at 8 a.m. The meter read 3.14 exactly."
        assertEquals(2, SentenceSegmenter.segment(text, "en-US").size)
        val speech = prepare(text).joinToString(" ") { it.speechText }
        assertTrue(speech.startsWith("doctor Mina"))
        assertTrue(speech.contains("eight A M"))
        assertTrue(speech.contains("three point one four"))
    }

    @Test
    fun expandedNumberKeepsItsExactSourceRange() {
        val text = "Pay $12.50 now."
        val chunk = prepare(text).single()
        val token = chunk.tokens.first { it.displayText == "$12.50" }
        assertEquals("twelve dollars and fifty cents", token.spokenText)
        assertEquals(TextRange(104, 110), token.sourceRanges.single().range)
        assertTrue(SpokenTokenFlag.SYNTHETIC_EXPANSION in token.flags)
    }

    @Test
    fun headingIsAlwaysIsolatedAndFlagged() {
        val spans = listOf(span("Chapter XIV: A Beginning", SemanticRole.HEADING), span("A short first sentence. Another follows."))
        val chunks = TtsTextPreparer.prepare(spans)
        assertEquals(SemanticRole.HEADING, chunks.first().semanticRole)
        assertTrue(chunks.first().tokens.all { SpokenTokenFlag.HEADING in it.flags })
        assertFalse(chunks.drop(1).flatMap { it.tokens }.any { SpokenTokenFlag.HEADING in it.flags })
    }

    @Test
    fun footnotesAreSkippedByDefaultAndCanBeIncluded() {
        val note = span("A note about the source.", SemanticRole.FOOTNOTE)
        assertTrue(TtsTextPreparer.prepare(listOf(note)).isEmpty())
        val included = TtsTextPreparer.prepare(listOf(note), TextPreparationOptions(footnotePolicy = FootnotePolicy.INCLUDE_INLINE))
        assertTrue(included.single().tokens.all { SpokenTokenFlag.FOOTNOTE in it.flags })
    }

    @Test
    fun hiddenNonLinearCodeAndPageMarkersAreNeverSpoken() {
        val spans = listOf(
            span("hidden", visibility = TextVisibility.HIDDEN),
            span("nonlinear", visibility = TextVisibility.NON_LINEAR),
            span("println", role = SemanticRole.CODE),
            span("14", role = SemanticRole.PAGE_MARKER),
        )
        assertTrue(TtsTextPreparer.prepare(spans).isEmpty())
    }

    @Test
    fun punctuationAndEmojiDoNotLoseNeighbouringSourceOffsets() {
        val text = "Ready ✅—then go!"
        val chunk = prepare(text).single()
        assertFalse(chunk.speechText.contains("✅"))
        val then = chunk.tokens.first { it.displayText == "then" }
        assertEquals(TextRange(108, 112), then.sourceRanges.single().range)
        assertTrue(chunk.tokens.any { SpokenTokenFlag.PUNCTUATION in it.flags })
    }

    @Test
    fun rtlTextRetainsLanguageDirectionAndSource() {
        val source = span("مرحبا بالعالم. كيف حالك؟", language = "ar", direction = TextDirection.RIGHT_TO_LEFT)
        val chunks = TtsTextPreparer.prepare(listOf(source))
        assertEquals("ar", chunks.single().language)
        assertTrue(chunks.single().tokens.all { it.sourceRanges.isNotEmpty() })
    }

    @Test
    fun cjkTerminatorsCreateDeterministicSentences() {
        val text = "第一章。今日は晴れです！次へ進みます？"
        assertEquals(3, SentenceSegmenter.segment(text, "ja-JP").size)
        val chunks = prepare(text, language = "ja-JP")
        assertEquals(chunks.map { it.id }, prepare("第一章。今日は晴れです！次へ進みます？", language = "ja-JP").map { it.id })
    }

    @Test
    fun longSentenceSplitsAtPreferredPunctuationUnderHardLimit() {
        val clause = "the reader crossed the square, checked the map, and continued toward the station"
        val text = List(8) { clause }.joinToString("; ") + "."
        val chunks = TtsTextPreparer.prepare(listOf(span(text)), TextPreparationOptions(maximumChunkCharacters = 120, targetChunkCharacters = 100))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.tokens.sumOf { token -> token.displayText.length } <= 120 })
    }

    @Test
    fun languageChangesCannotShareAChunk() {
        val chunks = TtsTextPreparer.prepare(listOf(span("Good morning.", language = "en-US"), span("Bonjour.", language = "fr-FR")))
        assertEquals(listOf("en-US", "fr-FR"), chunks.map { it.language })
    }

    @Test
    fun adjacentSourceRangesAreSlicedWithoutLosingIdentity() {
        val resource = ResourceId("chapter")
        val text = "alpha beta"
        val source = span(text).copy(
            sourceRanges = listOf(SourceRange(resource, TextRange(10, 16)), SourceRange(resource, TextRange(30, 34))),
        )
        val beta = TtsTextPreparer.prepare(listOf(source)).single().tokens.first { it.displayText == "beta" }
        assertEquals(TextRange(30, 34), beta.sourceRanges.single().range)
    }

    @Test
    fun paragraphBreakAlwaysEndsAChunk() {
        val chunks = prepare("Short one.\n\nShort two.")

        assertEquals(2, chunks.size)
        assertEquals("Short one.", chunks.first().speechText)
    }

    private fun prepare(text: String, language: String = "en-US") = TtsTextPreparer.prepare(listOf(span(text, language = language)))

    private fun span(
        text: String,
        role: SemanticRole = SemanticRole.PARAGRAPH,
        language: String? = "en-US",
        direction: TextDirection = TextDirection.LEFT_TO_RIGHT,
        visibility: TextVisibility = TextVisibility.VISIBLE,
    ): SourceTextSpan {
        val resource = ResourceId("chapter")
        return SourceTextSpan(
            displayText = text,
            locator = PublicationLocator(PublicationId("book"), resourceId = resource, resourceIndex = 0),
            semanticRole = role,
            language = language,
            direction = direction,
            visibility = visibility,
            sourceRanges = listOf(SourceRange(resource, TextRange(100, 100 + text.length))),
        )
    }
}
