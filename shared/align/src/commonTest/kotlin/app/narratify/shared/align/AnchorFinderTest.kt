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
