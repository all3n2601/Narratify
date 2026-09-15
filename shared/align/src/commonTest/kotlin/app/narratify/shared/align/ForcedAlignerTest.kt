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
