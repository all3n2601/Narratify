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
