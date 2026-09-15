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
    fun `an apostrophe reads the same however it was typeset`() {
        assertEquals(AlignmentKey.fold("don’t"), AlignmentKey.fold("don't"))
        assertEquals("don't", AlignmentKey.fold("don't"))
    }

    @Test
    fun `a contraction is not a homograph of another word`() {
        assertNotEquals(AlignmentKey.fold("we'll"), AlignmentKey.fold("well"))
        assertNotEquals(AlignmentKey.fold("can't"), AlignmentKey.fold("cant"))
    }

    @Test
    fun `quotation marks around a word are not part of it`() {
        assertEquals("seven", AlignmentKey.fold("‘seven’"))
        assertEquals("seven", AlignmentKey.fold("\"seven\""))
    }

    @Test
    fun `a hyphenated word is not a homograph of the word without it`() {
        assertNotEquals(AlignmentKey.fold("re-form"), AlignmentKey.fold("reform"))
        assertEquals("re-form", AlignmentKey.fold("re-form"))
    }

    @Test
    fun `an abbreviation is not a homograph of a word`() {
        assertNotEquals(AlignmentKey.fold("U.S"), AlignmentKey.fold("us"))
    }

    @Test
    fun `a mark at the edge of a token is punctuation rather than part of the word`() {
        assertEquals("tis", AlignmentKey.fold("'tis"))
        assertEquals("readers", AlignmentKey.fold("readers'"))
        assertEquals("", AlignmentKey.fold("'"))
        assertEquals("", AlignmentKey.fold("-"))
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
        assertEquals(AlignmentKey.fold("café"), AlignmentKey.fold("cafe\u0301"))
        assertEquals("café", AlignmentKey.fold("cafe\u0301"))
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
