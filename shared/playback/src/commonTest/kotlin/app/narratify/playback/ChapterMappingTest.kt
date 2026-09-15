package app.narratify.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterMappingTest {
    private fun chapters(count: Int) = List(count) { index ->
        AudioChapter(index = index, title = "Chapter ${index + 1}", startMs = index * 1_000L, endMs = (index + 1) * 1_000L)
    }

    @Test
    fun `a fresh mapping pairs chapters with spine items in order`() {
        val mapping = ChapterMapping.positional(chapters(3), spineCount = 5)
        assertEquals(listOf(0, 1, 2), mapping.rows.map { it.spineIndex })
        assertTrue(mapping.rows.none { it.confirmed })
    }

    @Test
    fun `a chapter past the end of the book maps to nothing`() {
        val mapping = ChapterMapping.positional(chapters(4), spineCount = 2)
        assertEquals(listOf(0, 1, null, null), mapping.rows.map { it.spineIndex })
    }

    @Test
    fun `an offset shifts every unconfirmed row`() {
        val shifted = ChapterMapping.positional(chapters(3), spineCount = 5).offsetBy(1)
        assertEquals(listOf(1, 2, 3), shifted.rows.map { it.spineIndex })
    }

    @Test
    fun `an offset leaves a row the reader already fixed alone`() {
        val mapping = ChapterMapping.positional(chapters(3), spineCount = 5)
            .remap(chapterIndex = 1, spineIndex = 4)
            .offsetBy(1)
        assertEquals(listOf(1, 4, 3), mapping.rows.map { it.spineIndex })
    }

    @Test
    fun `an offset that would run off the front clears those rows rather than clamping`() {
        // Clamping would silently pile several chapters onto spine item zero and look deliberate.
        val shifted = ChapterMapping.positional(chapters(3), spineCount = 5).offsetBy(-2)
        assertEquals(listOf(null, null, 0), shifted.rows.map { it.spineIndex })
    }

    @Test
    fun `remapping marks only that row as confirmed`() {
        val mapping = ChapterMapping.positional(chapters(3), spineCount = 5).remap(chapterIndex = 2, spineIndex = 0)
        assertEquals(listOf(false, false, true), mapping.rows.map { it.confirmed })
        assertEquals(0, mapping.rows[2].spineIndex)
    }

    @Test
    fun `a row can be cleared so a chapter moves no reader position`() {
        val mapping = ChapterMapping.positional(chapters(2), spineCount = 5).remap(chapterIndex = 0, spineIndex = null)
        assertNull(mapping.rows[0].spineIndex)
        assertTrue(mapping.rows[0].confirmed)
    }

    @Test
    fun `an empty chapter list maps to nothing without failing`() {
        val mapping = ChapterMapping.positional(emptyList(), spineCount = 5)
        assertTrue(mapping.rows.isEmpty())
        assertFalse(mapping.hasAnySpineLink)
    }

    @Test
    fun `a mapping reports whether it links anything at all`() {
        assertTrue(ChapterMapping.positional(chapters(2), spineCount = 5).hasAnySpineLink)
        assertFalse(ChapterMapping.positional(chapters(2), spineCount = 0).hasAnySpineLink)
    }

    @Test
    fun `the chapter covering a spine item is the first one mapped to it`() {
        val mapping = ChapterMapping.positional(chapters(3), spineCount = 5)
        assertEquals(1, mapping.chapterForSpine(1)?.chapter?.index)
        assertNull(mapping.chapterForSpine(4))
    }
}
