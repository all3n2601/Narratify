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
