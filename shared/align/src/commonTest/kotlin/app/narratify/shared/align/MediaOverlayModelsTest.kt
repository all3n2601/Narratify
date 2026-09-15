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
