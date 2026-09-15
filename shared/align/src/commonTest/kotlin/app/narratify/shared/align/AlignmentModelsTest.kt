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
    fun `granularity falls with the share of the text that was actually matched`() {
        val options = AlignmentOptions()
        assertEquals(AlignmentGranularity.WORD, options.granularityFor(0.97))
        assertEquals(AlignmentGranularity.SENTENCE, options.granularityFor(0.70))
        assertEquals(AlignmentGranularity.CHAPTER, options.granularityFor(0.30))
        assertEquals(AlignmentGranularity.NONE, options.granularityFor(0.05))
    }
}
