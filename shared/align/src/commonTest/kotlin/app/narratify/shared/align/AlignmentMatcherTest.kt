package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlignmentMatcherTest {
    @Test
    fun `an exact transcript matches every token`() {
        val keys = "the lantern went out at half past four and the harbour turned grey".split(" ")
        val matches = AlignmentMatcher.match(keys, keys)
        assertEquals(keys.indices.toList(), matches.map(Anchor::bookIndex))
        assertEquals(keys.indices.toList(), matches.map(Anchor::hypothesisIndex))
    }

    @Test
    fun `matches always move forward on both sides`() {
        val book = ("alpha and the bravo and the charlie and the delta and the echo and the foxtrot").split(" ")
        val hypothesis = ("alpha and the bravo and uh the charlie and the delta the echo and the foxtrot").split(" ")
        val matches = AlignmentMatcher.match(book, hypothesis)
        assertTrue(matches.zipWithNext().all { (a, b) -> a.bookIndex < b.bookIndex && a.hypothesisIndex < b.hypothesisIndex })
    }

    @Test
    fun `common words inside a gap still match once the gap is narrow`() {
        // No token is unique chapter-wide, so the first anchoring pass finds nothing and the
        // result comes entirely from narrowing and exact gap alignment.
        val book = "the and the and the and".split(" ")
        val hypothesis = "the and the and the and".split(" ")
        assertEquals(6, AlignmentMatcher.match(book, hypothesis).size)
    }

    @Test
    fun `text the narrator never read stays unmatched instead of being pulled forward`() {
        val book = "alpha bravo charlie delta echo foxtrot".split(" ")
        val hypothesis = "alpha bravo foxtrot".split(" ")
        val matches = AlignmentMatcher.match(book, hypothesis)
        assertEquals(listOf(0, 1, 5), matches.map(Anchor::bookIndex))
    }

    @Test
    fun `audio the book never contained stays unmatched`() {
        val book = "alpha bravo charlie".split(" ")
        val hypothesis = "this is a recording alpha bravo charlie thank you for listening".split(" ")
        val matches = AlignmentMatcher.match(book, hypothesis)
        assertEquals(listOf(0, 1, 2), matches.map(Anchor::bookIndex))
        assertEquals(listOf(4, 5, 6), matches.map(Anchor::hypothesisIndex))
    }

    @Test
    fun `two texts with nothing in common produce no matches`() {
        val matches = AlignmentMatcher.match(
            "alpha bravo charlie".split(" "),
            "xylem zephyr quokka".split(" "),
        )
        assertEquals(emptyList(), matches)
    }

    @Test
    fun `a chapter-sized input completes without a stack overflow`() {
        val book = List(20_000) { "word$it" }
        val hypothesis = book.filterIndexed { index, _ -> index % 13 != 0 }
        val matches = AlignmentMatcher.match(book, hypothesis)
        assertEquals(hypothesis.size, matches.size)
    }
}
