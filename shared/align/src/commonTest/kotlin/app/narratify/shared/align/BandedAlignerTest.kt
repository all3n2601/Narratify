package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BandedAlignerTest {
    private fun align(book: List<String>, hypothesis: List<String>) =
        BandedAligner.align(book, hypothesis, 0, book.size, 0, hypothesis.size)

    @Test
    fun `identical runs match position for position`() {
        val words = listOf("and", "the", "and", "the")
        assertEquals(listOf(Anchor(0, 0), Anchor(1, 1), Anchor(2, 2), Anchor(3, 3)), align(words, words))
    }

    @Test
    fun `a word the narrator skipped leaves the rest matched`() {
        val matches = align(listOf("and", "then", "the", "and"), listOf("and", "the", "and"))
        assertEquals(listOf("and", "the", "and"), matches!!.map { listOf("and", "then", "the", "and")[it.bookIndex] })
        assertTrue(matches.zipWithNext().all { (a, b) -> a.bookIndex < b.bookIndex && a.hypothesisIndex < b.hypothesisIndex })
    }

    @Test
    fun `a word the recognizer invented leaves the rest matched`() {
        val matches = align(listOf("and", "the"), listOf("and", "uh", "the"))
        assertEquals(listOf(Anchor(0, 0), Anchor(1, 2)), matches)
    }

    @Test
    fun `a misheard word is reported as unmatched rather than forced`() {
        val matches = align(listOf("and", "slate", "the"), listOf("and", "sleight", "the"))
        assertEquals(listOf(Anchor(0, 0), Anchor(2, 2)), matches)
    }

    @Test
    fun `an empty side matches nothing without failing`() {
        assertEquals(emptyList(), align(emptyList(), listOf("and")))
        assertEquals(emptyList(), align(listOf("and"), emptyList()))
    }

    @Test
    fun `a gap too large to align refuses instead of allocating`() {
        val book = List(600) { "word" }
        val hypothesis = List(600) { "word" }
        assertNull(align(book, hypothesis))
    }
}
