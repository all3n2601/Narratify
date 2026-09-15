package app.narratify.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.narratify.shared.data.db.NarratifyDatabase
import com.narratify.domain.TextAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalLibraryStoreTest {
    @Test
    fun `book and resilient text position survive a new store instance`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NarratifyDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val database = NarratifyDatabase(driver)
        val store = LocalLibraryStore(database)

        store.addBook(
            id = "book-1",
            fingerprint = "abc123",
            format = "Markdown",
            title = "A real book",
            displayName = "book.md",
            storageUri = "/managed/book.md",
            mediaType = "text/markdown",
            byteSize = 80,
            now = 100,
        )
        store.savePosition(
            publicationId = "book-1",
            position = StoredTextPosition(TextAnchor(42, "chapter", "first ", " end"), 0.4f),
            now = 200,
        )

        val restoredStore = LocalLibraryStore(NarratifyDatabase(driver))
        assertEquals("A real book", restoredStore.books().single().title)
        assertEquals(40, restoredStore.books().single().progress)
        val restored = assertNotNull(restoredStore.position("book-1"))
        assertEquals(42, restored.anchor.characterOffset)
        assertEquals("chapter", restored.anchor.exact)
        assertEquals(0.4f, restored.progression)
    }

    @Test
    fun `duplicate fingerprints resolve to their persisted book`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NarratifyDatabase.Schema.create(driver)
        val store = LocalLibraryStore(NarratifyDatabase(driver))
        store.addBook(
            id = "book-1",
            fingerprint = "same-content",
            format = "TXT",
            title = "Once",
            displayName = "once.txt",
            storageUri = "/managed/once.txt",
            mediaType = "text/plain",
            byteSize = 12,
            now = 100,
        )

        assertEquals("book-1", store.findByFingerprint("same-content")?.id)
        assertEquals(null, store.findByFingerprint("different"))
    }

    @Test
    fun `audio playback position is durable and updates library progress`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NarratifyDatabase.Schema.create(driver)
        val store = LocalLibraryStore(NarratifyDatabase(driver))
        store.addBook(
            id = "audio-1",
            fingerprint = "audio-content",
            format = "MP3",
            title = "A Spoken Book",
            displayName = "spoken.mp3",
            storageUri = "/managed/spoken.mp3",
            mediaType = "audio/mpeg",
            byteSize = 1_024,
            durationUs = 120_000_000,
            now = 100,
        )

        store.saveAudioPosition("audio-1", positionMs = 30_000, durationMs = 120_000, now = 200)

        val restored = LocalLibraryStore(NarratifyDatabase(driver))
        assertEquals(30_000, restored.audioPositionMs("audio-1"))
        assertEquals(25, restored.books().single().progress)
    }

    @Test
    fun `hidden books leave the main shelf and can be restored`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NarratifyDatabase.Schema.create(driver)
        val store = LocalLibraryStore(NarratifyDatabase(driver))
        store.addBook(
            id = "hidden-1",
            fingerprint = "hidden-content",
            format = "TXT",
            title = "Secret Garden",
            displayName = "garden.txt",
            storageUri = "/managed/garden.txt",
            mediaType = "text/plain",
            byteSize = 30,
            now = 100,
        )

        assertTrue(store.setHidden("hidden-1", hidden = true, now = 200))
        assertTrue(store.books().isEmpty())
        assertEquals("Secret Garden", store.hiddenBooks().single().title)
        assertTrue(store.book("hidden-1")?.hidden == true)

        assertTrue(store.setHidden("hidden-1", hidden = false, now = 300))
        assertEquals("Secret Garden", store.books().single().title)
        assertTrue(store.hiddenBooks().isEmpty())
    }

    @Test
    fun `permanent removal deletes the publication graph`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NarratifyDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val store = LocalLibraryStore(NarratifyDatabase(driver))
        store.addBook(
            id = "remove-1",
            fingerprint = "remove-content",
            format = "TXT",
            title = "Temporary",
            displayName = "temporary.txt",
            storageUri = "/managed/temporary.txt",
            mediaType = "text/plain",
            byteSize = 12,
            now = 100,
        )
        store.savePosition(
            publicationId = "remove-1",
            position = StoredTextPosition(TextAnchor(4, "text", "", ""), .5f),
            now = 200,
        )
        assertTrue(store.saveCover(
            publicationId = "remove-1",
            storageUri = "/managed/remove-1.cover.jpg",
            contentHash = "cover-hash",
            byteSize = 64,
            now = 250,
        ))
        assertEquals("/managed/remove-1.cover.jpg", store.book("remove-1")?.coverUri)

        assertTrue(store.deleteBook("remove-1"))
        assertNull(store.book("remove-1"))
        assertNull(store.position("remove-1"))
        assertTrue(store.books().isEmpty())
    }
}
