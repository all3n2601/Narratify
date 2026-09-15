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
