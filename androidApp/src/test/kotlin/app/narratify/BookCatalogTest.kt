package app.narratify

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BookCatalogTest {
    @Test
    fun parsesDownloadableGutendexEPUB() {
        val result = CatalogParsers.gutendex(
            """{"results":[{"id":84,"title":"Frankenstein","authors":[{"name":"Shelley, Mary"}],"formats":{"application/epub+zip":"https://www.gutenberg.org/ebooks/84.epub3.images","image/jpeg":"https://example.test/cover.jpg"}}]}""",
        ).single()

        assertEquals("Frankenstein", result.title)
        assertEquals("Shelley, Mary", result.author)
        assertNotNull(result.epubUrl)
        assertTrue(result.epubUrl.startsWith("https://"))
    }

    @Test
    fun mergesDuplicateMetadataIntoDownloadableResult() {
        val metadata = CatalogBook(
            "open:1",
            "Frankenstein",
            "Mary Shelley",
            "Open Library",
            "https://openlibrary.org/works/1",
            coverUrl = "https://covers.openlibrary.org/frankenstein.jpg",
        )
        val download = CatalogBook("gutenberg:84", "Frankenstein", "Mary Shelley", "Project Gutenberg", "https://gutenberg.org/84", epubUrl = "https://gutenberg.org/84.epub")

        val merged = CatalogParsers.merge(listOf(listOf(metadata), listOf(download))).single()

        assertNotNull(merged.epubUrl)
        assertEquals(metadata.coverUrl, merged.coverUrl)
        assertTrue(merged.sources.contains("Open Library"))
        assertTrue(merged.sources.contains("Project Gutenberg"))
    }

    @Test
    fun providerWithoutItemsReturnsAnEmptyList() {
        assertTrue(CatalogParsers.googleBooks("""{"totalItems":0}""").isEmpty())
    }
}
