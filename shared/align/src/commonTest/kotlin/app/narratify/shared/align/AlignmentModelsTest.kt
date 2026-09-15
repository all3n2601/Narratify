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
