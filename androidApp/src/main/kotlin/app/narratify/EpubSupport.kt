package app.narratify

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.allAreHtml
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

data class EpubMetadata(
    val title: String,
    val author: String?,
    val resourceCount: Int,
    val cover: Bitmap?,
)

data class OpenedEpub(
    val publication: Publication,
    val navigatorFactory: EpubNavigatorFactory,
    val initialLocator: Locator?,
)

/** Readium 3.2 EPUB-only adapter. No DRM content protection is installed. */
class EpubService(context: Context) {
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = context.applicationContext,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
        contentProtections = emptyList(),
    )

    fun inspect(file: File): EpubMetadata {
        rejectEncryptedPackage(file)
        val publication = openPublication(file)
        return try {
            EpubMetadata(
                title = publication.metadata.title?.takeIf(String::isNotBlank) ?: file.nameWithoutExtension,
                author = publication.metadata.authors.firstOrNull()?.name,
                resourceCount = publication.readingOrder.size,
                cover = runBlocking { publication.coverFitting(Size(1_200, 1_800)) },
            )
        } finally {
            publication.close()
        }
    }

    /**
     * The reader outline for this EPUB: its table of contents, falling back to its reading order
     * when the package ships none. Reuses [ReaderOutline.fromTableOfContents] — the same builder
     * the reader activity uses — so a mapping the reader corrected is never contradicted by a
     * second, slightly different reading of the same package document.
     */
    fun outline(file: File): List<OutlineEntry> {
        rejectEncryptedPackage(file)
        val publication = openPublication(file)
        return try {
            val tableOfContents = runCatching { publication.tableOfContents }.getOrDefault(emptyList())
            val readingOrder = runCatching { publication.readingOrder }.getOrDefault(emptyList())
            ReaderOutline.fromTableOfContents(convert(tableOfContents), convert(readingOrder))
        } finally {
            publication.close()
        }
    }

    private fun convert(links: List<Link>): List<OutlineLink> = links.map {
        OutlineLink(title = it.title, href = it.url().toString(), children = convert(it.children))
    }

    fun open(file: File, locatorJson: String?): OpenedEpub {
        rejectEncryptedPackage(file)
        val publication = openPublication(file)
        val initialLocator = locatorJson?.let { encoded ->
            runCatching { Locator.fromJSON(org.json.JSONObject(encoded)) }.getOrNull()
        }
        return OpenedEpub(publication, EpubNavigatorFactory(publication), initialLocator)
    }

    private fun openPublication(file: File): Publication = runBlocking {
        val asset = assetRetriever.retrieve(file)
            .getOrElse { throw IllegalArgumentException("This EPUB could not be opened: $it") }
        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrElse { throw IllegalArgumentException("This EPUB could not be parsed: $it") }
        if (publication.isRestricted) {
            publication.close()
            throw IllegalArgumentException("This EPUB is DRM-protected or encrypted and cannot be opened.")
        }
        if (!publication.conformsTo(Publication.Profile.EPUB) && !publication.readingOrder.allAreHtml) {
            publication.close()
            throw IllegalArgumentException("The selected file is not a supported EPUB 2 or EPUB 3 publication.")
        }
        publication
    }

    private fun rejectEncryptedPackage(file: File) {
        try {
            ZipFile(file).use { archive ->
                val entries = archive.entries().asSequence().toList()
                require(entries.size <= MAX_ENTRY_COUNT) { "This EPUB contains too many resources." }
                var uncompressedBytes = 0L
                entries.forEach { entry ->
                    require(!entry.name.startsWith('/') && entry.name.split('/').none { it == ".." }) {
                        "This EPUB contains an unsafe resource path."
                    }
                    if (!entry.isDirectory) {
                        require(entry.size in 0..MAX_SINGLE_ENTRY_BYTES) { "This EPUB contains an invalid or oversized resource." }
                        uncompressedBytes += entry.size
                        require(uncompressedBytes <= MAX_UNCOMPRESSED_BYTES) { "This EPUB expands beyond the supported size limit." }
                        if (entry.compressedSize > 0 && entry.size > MIN_RATIO_CHECK_BYTES) {
                            require(entry.size / entry.compressedSize <= MAX_COMPRESSION_RATIO) {
                                "This EPUB contains a suspiciously compressed resource."
                            }
                        }
                    }
                }
                val names = entries.map { it.name.lowercase() }.toSet()
                if (names.any { it == "meta-inf/rights.xml" || it.endsWith("license.lcpl") }) {
                    throw IllegalArgumentException("This EPUB is DRM-protected or encrypted and cannot be opened.")
                }
                val encryption = entries.firstOrNull { it.name.equals("META-INF/encryption.xml", ignoreCase = true) } ?: return
                val xml = archive.getInputStream(encryption).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ENCRYPTION_XML_BYTES) { "This EPUB has invalid encryption metadata." }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                val algorithms = Regex("Algorithm\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE)
                    .findAll(xml)
                    .map { it.groupValues[1] }
                    .toList()
                if (algorithms.any { it !in FONT_OBFUSCATION_ALGORITHMS }) {
                    throw IllegalArgumentException("This EPUB is DRM-protected or encrypted and cannot be opened.")
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("This EPUB is damaged, encrypted, or not a valid ZIP package.")
        }
    }

    companion object {
        private const val MAX_ENCRYPTION_XML_BYTES = 2 * 1024 * 1024
        private const val MAX_ENTRY_COUNT = 20_000
        private const val MAX_SINGLE_ENTRY_BYTES = 256L * 1024L * 1024L
        private const val MAX_UNCOMPRESSED_BYTES = 1_024L * 1024L * 1024L
        private const val MIN_RATIO_CHECK_BYTES = 1L * 1024L * 1024L
        private const val MAX_COMPRESSION_RATIO = 1_000L
        private val FONT_OBFUSCATION_ALGORITHMS = setOf(
            "http://www.idpf.org/2008/embedding",
            "http://ns.adobe.com/pdf/enc#RC",
        )
    }
}
