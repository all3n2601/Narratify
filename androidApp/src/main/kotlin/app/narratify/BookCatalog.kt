package app.narratify

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class CatalogBook(
    val id: String,
    val title: String,
    val author: String,
    val sources: String,
    val detailUrl: String,
    val coverUrl: String? = null,
    val epubUrl: String? = null,
    val note: String? = null,
)

internal object CatalogParsers {
    fun gutendex(json: String): List<CatalogBook> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optInt("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val title = item.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val authors = item.optJSONArray("authors")
            val author = authors?.optJSONObject(0)?.optString("name")?.takeIf(String::isNotBlank) ?: "Unknown author"
            val formats = item.optJSONObject("formats")
            CatalogBook(
                id = "gutenberg:$id",
                title = title,
                author = author,
                sources = "Project Gutenberg",
                detailUrl = "https://www.gutenberg.org/ebooks/$id",
                coverUrl = formats?.optString("image/jpeg")?.takeIf(String::isNotBlank),
                epubUrl = formats?.optString("application/epub+zip")?.takeIf(String::isNotBlank),
                note = "Public-domain availability depends on your country.",
            )
        }
    }

    fun openLibrary(json: String): List<CatalogBook> {
        val docs = JSONObject(json).optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { index ->
            val item = docs.optJSONObject(index) ?: return@mapNotNull null
            val key = item.optString("key").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = item.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val authors = item.optJSONArray("author_name")
            val author = authors?.optString(0)?.takeIf(String::isNotBlank) ?: "Unknown author"
            val coverId = item.optLong("cover_i", -1).takeIf { it >= 0 }
            CatalogBook(
                id = "openlibrary:$key",
                title = title,
                author = author,
                sources = "Open Library",
                detailUrl = "https://openlibrary.org$key",
                coverUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
                note = item.optInt("first_publish_year", 0).takeIf { it > 0 }?.let { "First published $it" },
            )
        }
    }

    fun googleBooks(json: String): List<CatalogBook> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val info = item.optJSONObject("volumeInfo") ?: return@mapNotNull null
            val title = info.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val authors = info.optJSONArray("authors")
            CatalogBook(
                id = "google:$id",
                title = title,
                author = authors?.optString(0)?.takeIf(String::isNotBlank) ?: "Unknown author",
                sources = "Google Books",
                detailUrl = info.optString("infoLink").takeIf(String::isNotBlank)
                    ?: "https://books.google.com/books?id=$id",
                coverUrl = info.optJSONObject("imageLinks")?.optString("thumbnail")?.takeIf(String::isNotBlank)?.replace("http://", "https://"),
                note = info.optString("publishedDate").takeIf(String::isNotBlank)?.let { "Published $it" },
            )
        }
    }

    fun internetArchive(json: String): List<CatalogBook> {
        val docs = JSONObject(json).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { index ->
            val item = docs.optJSONObject(index) ?: return@mapNotNull null
            val identifier = item.optString("identifier").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = item.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val creatorValue = item.opt("creator")
            val author = when (creatorValue) {
                is String -> creatorValue
                is org.json.JSONArray -> creatorValue.optString(0)
                else -> null
            }?.takeIf(String::isNotBlank) ?: "Unknown author"
            CatalogBook(
                id = "archive:$identifier",
                title = title,
                author = author,
                sources = "Internet Archive",
                detailUrl = "https://archive.org/details/$identifier",
                coverUrl = "https://archive.org/services/img/$identifier",
                note = "Availability and borrowing terms vary by item.",
            )
        }
    }

    fun librivox(json: String): List<CatalogBook> {
        val books = JSONObject(json).optJSONArray("books") ?: return emptyList()
        return (0 until books.length()).mapNotNull { index ->
            val item = books.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = item.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val authorObject = item.optJSONArray("authors")?.optJSONObject(0)
            val author = listOf(authorObject?.optString("first_name"), authorObject?.optString("last_name"))
                .filterNotNull().filter(String::isNotBlank).joinToString(" ").ifBlank { "Unknown author" }
            CatalogBook(
                id = "librivox:$id",
                title = title,
                author = author,
                sources = "LibriVox",
                detailUrl = item.optString("url_librivox").takeIf(String::isNotBlank) ?: "https://librivox.org",
                note = "Human-read public-domain audiobook.",
            )
        }
    }

    fun merge(groups: List<List<CatalogBook>>): List<CatalogBook> {
        val merged = linkedMapOf<String, CatalogBook>()
        groups.flatten().forEach { candidate ->
            val key = normalize(candidate.title) + "|" + normalize(candidate.author)
            val current = merged[key]
            merged[key] = if (current == null) candidate else {
                val preferred = if (current.epubUrl == null && candidate.epubUrl != null) candidate else current
                preferred.copy(
                    sources = (current.sources.split(" · ") + candidate.sources.split(" · ")).distinct().joinToString(" · "),
                    // A result chosen for its downloadable EPUB should not lose artwork supplied
                    // by another catalog entry for the same title and author.
                    coverUrl = preferred.coverUrl ?: current.coverUrl ?: candidate.coverUrl,
                )
            }
        }
        return merged.values.sortedWith(compareByDescending<CatalogBook> { it.epubUrl != null }.thenBy { it.title.lowercase(Locale.ROOT) })
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}

class BookCatalogService {
    private val executor = Executors.newFixedThreadPool(5)

    fun search(query: String, completion: (List<CatalogBook>, Int) -> Unit) {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val requests = listOf(
            "https://gutendex.com/books?search=$encoded" to CatalogParsers::gutendex,
            "https://openlibrary.org/search.json?q=$encoded&limit=12&fields=key,title,author_name,first_publish_year,cover_i,ebook_access" to CatalogParsers::openLibrary,
            "https://www.googleapis.com/books/v1/volumes?q=$encoded&maxResults=12&printType=books" to CatalogParsers::googleBooks,
            "https://archive.org/advancedsearch.php?q=$encoded%20AND%20mediatype%3Atexts&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&rows=12&page=1&output=json" to CatalogParsers::internetArchive,
            "https://librivox.org/api/feed/audiobooks?title=%5E$encoded&format=json&extended=1&limit=12" to CatalogParsers::librivox,
        )
        val remaining = AtomicInteger(requests.size)
        val groups = mutableListOf<List<CatalogBook>>()
        val failures = AtomicInteger(0)
        requests.forEach { (url, parser) ->
            executor.execute {
                val result = runCatching { parser(fetch(url)) }
                synchronized(groups) { result.onSuccess(groups::add) }
                if (result.isFailure) failures.incrementAndGet()
                if (remaining.decrementAndGet() == 0) {
                    val snapshot = synchronized(groups) { groups.toList() }
                    completion(CatalogParsers.merge(snapshot), failures.get())
                }
            }
        }
    }

    fun close() = executor.shutdownNow()

    private fun fetch(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("User-Agent", "Narratify/1.0 (book catalog)")
            connection.setRequestProperty("Accept", "application/json")
            require(connection.responseCode in 200..299) { "Catalog returned HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
