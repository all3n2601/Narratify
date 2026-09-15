package app.narratify.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.narratify.shared.data.db.NarratifyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NarrationStoreTest {
    private fun store(): LocalLibraryStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NarratifyDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return LocalLibraryStore(NarratifyDatabase(driver))
    }

    private fun LocalLibraryStore.seedBook(id: String = "book-1"): String {
        addBook(
            id = id,
            fingerprint = "hash-$id",
            format = "EPUB",
            title = "The Harbour",
            displayName = "harbour.epub",
            storageUri = "/books/$id.epub",
            mediaType = "application/epub+zip",
            byteSize = 1_000L,
            now = 1L,
        )
        return id
    }

    @Test
    fun `a book has no narration until one is attached`() {
        val store = store()
        val id = store.seedBook()
        assertNull(store.narration(id))
    }

    @Test
    fun `an attached narration survives a new store instance reading the same database`() {
        val store = store()
        val id = store.seedBook()
        store.attachNarration(
            publicationId = id,
            storageUri = "/audio/$id.m4b",
            displayName = "harbour.m4b",
            mediaType = "audio/mp4",
            contentHash = "audio-hash",
            byteSize = 9_000L,
            now = 2L,
        )
        val narration = store.narration(id)
        assertEquals("/audio/$id.m4b", narration?.storageUri)
        assertEquals("harbour.m4b", narration?.displayName)
        assertEquals(9_000L, narration?.byteSize)
    }

    @Test
    fun `attaching a second narration replaces the first`() {
        val store = store()
        val id = store.seedBook()
        store.attachNarration(id, "/audio/one.m4b", "one.m4b", "audio/mp4", "hash-one", 1L, 2L)
        store.attachNarration(id, "/audio/two.m4b", "two.m4b", "audio/mp4", "hash-two", 1L, 3L)
        assertEquals("/audio/two.m4b", store.narration(id)?.storageUri)
    }

    @Test
    fun `the original file is untouched by attaching a narration`() {
        val store = store()
        val id = store.seedBook()
        store.attachNarration(id, "/audio/one.m4b", "one.m4b", "audio/mp4", "hash-one", 1L, 2L)
        assertEquals("/books/$id.epub", store.book(id)?.storageUri)
        assertEquals("EPUB", store.book(id)?.format)
    }

    @Test
    fun `chapters round trip in order`() {
        val store = store()
        val id = store.seedBook()
        store.saveChapters(
            id,
            listOf(
                StoredChapter(0, "One", 0L, 5_000L, 0, confirmed = false),
                StoredChapter(1, "Two", 5_000L, 9_000L, 3, confirmed = true),
            ),
        )
        val chapters = store.chapters(id)
        assertEquals(listOf("One", "Two"), chapters.map { it.title })
        assertEquals(listOf(0, 3), chapters.map { it.spineIndex })
        assertEquals(listOf(false, true), chapters.map { it.confirmed })
    }

    @Test
    fun `saving chapters replaces whatever was there before`() {
        val store = store()
        val id = store.seedBook()
        store.saveChapters(id, listOf(StoredChapter(0, "Old", 0L, 1L, 0, confirmed = false)))
        store.saveChapters(id, listOf(StoredChapter(0, "New", 0L, 1L, 1, confirmed = true)))
        assertEquals(listOf("New"), store.chapters(id).map { it.title })
    }

    @Test
    fun `the chapter for a spine item is found`() {
        val store = store()
        val id = store.seedBook()
        store.saveChapters(
            id,
            listOf(
                StoredChapter(0, "One", 0L, 5_000L, 0, confirmed = false),
                StoredChapter(1, "Two", 5_000L, 9_000L, 3, confirmed = false),
            ),
        )
        assertEquals("Two", store.chapterForSpine(id, 3)?.title)
        assertNull(store.chapterForSpine(id, 9))
    }

    @Test
    fun `removing the book removes its chapters`() {
        val store = store()
        val id = store.seedBook()
        store.saveChapters(id, listOf(StoredChapter(0, "One", 0L, 1L, 0, confirmed = false)))
        assertTrue(store.deleteBook(id))
        assertTrue(store.chapters(id).isEmpty())
    }

    @Test
    fun `detaching removes the narration and its chapters but keeps the book`() {
        val store = store()
        val id = store.seedBook()
        store.attachNarration(id, "/audio/one.m4b", "one.m4b", "audio/mp4", "hash-one", 1L, 2L)
        store.saveChapters(id, listOf(StoredChapter(0, "One", 0L, 1L, 0, confirmed = false)))
        store.detachNarration(id)
        assertNull(store.narration(id))
        assertTrue(store.chapters(id).isEmpty())
        assertEquals("The Harbour", store.book(id)?.title)
    }
}
