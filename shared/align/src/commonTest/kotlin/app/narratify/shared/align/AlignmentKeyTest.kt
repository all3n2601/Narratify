package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AlignmentKeyTest {
    @Test
    fun `case and punctuation never decide a match`() {
        assertEquals(AlignmentKey.fold("Harbour,"), AlignmentKey.fold("harbour"))
        assertEquals(AlignmentKey.fold("“Seven!”"), AlignmentKey.fold("seven"))
    }

    @Test
    fun `apostrophes are dropped so transcribers and typesetters agree`() {
        assertEquals(AlignmentKey.fold("don’t"), AlignmentKey.fold("don't"))
        assertEquals("dont", AlignmentKey.fold("don't"))
    }

    @Test
    fun `digits survive folding`() {
        assertEquals("1984", AlignmentKey.fold("1984."))
    }

    @Test
    fun `a token with nothing to compare folds to the empty key`() {
        assertEquals("", AlignmentKey.fold("—"))
        assertEquals("", AlignmentKey.fold(""))
    }

    @Test
    fun `normalization form never decides a match`() {
        // "café" precomposed (U+00E9) against "café" decomposed (e + U+0301). The two literals are
        // visually identical on purpose — the escape is what distinguishes them.
        assertEquals(AlignmentKey.fold("café"), AlignmentKey.fold("café"))
        assertEquals("café", AlignmentKey.fold("café"))
    }

    @Test
    fun `composing is not stripping so an accent still distinguishes two words`() {
        assertNotEquals(AlignmentKey.fold("café"), AlignmentKey.fold("cafe"))
    }

    @Test
    fun `scripts outside Latin fold to themselves`() {
        assertEquals("море", AlignmentKey.fold("Море,"))
        assertEquals("海", AlignmentKey.fold("海。"))
    }
}
