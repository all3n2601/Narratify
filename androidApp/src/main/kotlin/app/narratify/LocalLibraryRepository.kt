package app.narratify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.narratify.playback.AudioChapter
import app.narratify.playback.Mp4ChapterReader
import app.narratify.shared.data.AndroidDatabaseFactory
import app.narratify.shared.data.LocalLibraryStore
import app.narratify.shared.data.StoredLibraryBook
import com.narratify.domain.PlainTextDocument
import com.narratify.domain.PlainTextNormalizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.readium.r2.shared.publication.Locator

typealias LocalBook = StoredLibraryBook
typealias StoredTextPosition = app.narratify.shared.data.StoredTextPosition

/** Android file-import adapter. Durable metadata and positions are SQLDelight-backed. */
class LocalLibraryRepository(private val context: Context) {
    private val store = LocalLibraryStore(AndroidDatabaseFactory.create(context))
    private val epubService = EpubService(context)
    private val filesDirectory = File(context.filesDir, "publications").apply { mkdirs() }

    fun books(): List<LocalBook> = store.books().map(::ensureCover)

    fun hiddenBooks(): List<LocalBook> = store.hiddenBooks().map(::ensureCover)

    fun setHidden(bookId: String, hidden: Boolean): Result<Unit> = runCatching {
        require(store.setHidden(bookId, hidden, System.currentTimeMillis())) {
            "This book is no longer in the library."
        }
    }

    /**
     * Permanently removes Narratify's private copy and its database record. The file is first
     * renamed inside our managed directory so a database failure can still be rolled back.
     */
    fun delete(bookId: String): Result<Unit> = runCatching {
        val book = requireNotNull(store.book(bookId)) { "This book is no longer in the library." }
        val managedRoot = filesDirectory.canonicalFile
        val managedFiles = listOfNotNull(book.storageUri, book.coverUri)
            .map(::File)
            .map(File::getCanonicalFile)
            .distinct()
        require(managedFiles.all { it.parentFile == managedRoot }) {
            "Narratify can only remove its own managed files."
        }
        val stagedFiles = mutableListOf<Pair<File, File>>()
        try {
            managedFiles.filter(File::exists).forEachIndexed { index, source ->
                val staged = File(managedRoot, ".${book.id}.$index.deleting")
                require(!staged.exists()) { "A previous removal is still being cleaned up." }
                require(source.renameTo(staged)) { "The stored book could not be prepared for removal." }
                stagedFiles += source to staged
            }
        } catch (failure: Throwable) {
            stagedFiles.asReversed().forEach { (source, staged) -> staged.renameTo(source) }
            throw failure
        }
        try {
            require(store.deleteBook(bookId)) { "This book is no longer in the library." }
        } catch (failure: Throwable) {
            stagedFiles.asReversed().forEach { (source, staged) -> staged.renameTo(source) }
            throw failure
        }
        stagedFiles.forEach { (_, staged) ->
            if (!staged.delete()) staged.deleteOnExit()
        }
        missingCoverMarker(book.id).delete()
    }

    fun import(uri: Uri, suggestedName: String? = null): Result<LocalBook> = runCatching {
        val resolver = context.contentResolver
        val metadata = runCatching { resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            }
        } }.getOrNull()
        val displayName = metadata?.first?.takeIf(String::isNotBlank) ?: suggestedName?.takeIf(String::isNotBlank) ?: "Imported book"
        val declaredSize = metadata?.second
        val mediaType = runCatching { resolver.getType(uri) }.getOrNull()?.substringBefore(';')?.trim()?.lowercase()
        val extension = supportedExtension(displayName, mediaType)
        require(extension in SUPPORTED_EXTENSIONS) {
            "This build reads unencrypted EPUB, TXT, Markdown, MP3, M4A, and M4B files."
        }
        val maximumBytes = when (extension) {
            "epub" -> MAX_EPUB_BYTES
            in AUDIO_EXTENSIONS -> MAX_AUDIO_BYTES
            else -> MAX_TEXT_BYTES
        }
        require(declaredSize == null || declaredSize <= maximumBytes) {
            when (extension) {
                "epub" -> "EPUB files larger than 500 MB are not supported."
                in AUDIO_EXTENSIONS -> "Audio files larger than 4 GB are not supported."
                else -> "Text files larger than 20 MB are not supported yet."
            }
        }

        val id = UUID.randomUUID().toString()
        val normalizedExtension = when (extension) {
            "txt" -> "txt"
            "epub" -> "epub"
            "mp3" -> "mp3"
            "m4a" -> "m4a"
            "m4b" -> "m4b"
            else -> "md"
        }
        val partial = File(filesDirectory, ".$id.$normalizedExtension.part")
        val destination = File(filesDirectory, "$id.$normalizedExtension")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected file could not be opened." }
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maximumBytes) {
                            when (extension) {
                                "epub" -> "EPUB files larger than 500 MB are not supported."
                                in AUDIO_EXTENSIONS -> "Audio files larger than 4 GB are not supported."
                                else -> "Text files larger than 20 MB are not supported yet."
                            }
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
            store.findByFingerprint(fingerprint)?.let { duplicate ->
                partial.delete()
                if (duplicate.hidden) {
                    store.setHidden(duplicate.id, hidden = false, now = System.currentTimeMillis())
                }
                return@runCatching ensureCover(requireNotNull(store.book(duplicate.id)))
            }

            val epubMetadata = if (extension == "epub") epubService.inspect(partial) else null
            val audioMetadata = if (extension in AUDIO_EXTENSIONS) inspectAudio(displayName, partial) else null
            val document = if (extension in TEXT_EXTENSIONS) {
                readDocument(displayName, partial, extension != "txt")
            } else null
            require(partial.renameTo(destination)) { "The imported file could not be saved." }
            try {
                val stored = store.addBook(
                    id = id,
                    fingerprint = fingerprint,
                    format = when (extension) {
                        "txt" -> "TXT"
                        "epub" -> "EPUB"
                        "mp3" -> "MP3"
                        "m4a" -> "M4A"
                        "m4b" -> "M4B"
                        else -> "Markdown"
                    },
                    title = epubMetadata?.title ?: audioMetadata?.title ?: requireNotNull(document).title,
                    author = epubMetadata?.author ?: audioMetadata?.artist,
                    displayName = displayName,
                    storageUri = destination.absolutePath,
                    mediaType = mediaType,
                    byteSize = total,
                    durationUs = audioMetadata?.durationMs?.times(1_000L),
                    now = System.currentTimeMillis(),
                )
                val cover = epubMetadata?.cover ?: audioMetadata?.cover
                try {
                    if (cover != null) {
                        persistCover(stored, cover) ?: stored
                    } else {
                        missingCoverMarker(id).createNewFile()
                        stored
                    }
                } finally {
                    cover?.recycle()
                }
            } catch (failure: Throwable) {
                destination.delete()
                throw failure
            }
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    fun importRemote(address: String, suggestedName: String): Result<LocalBook> = runCatching {
        val source = URL(address)
        require(source.protocol == "https") { "Only secure HTTPS downloads are supported." }
        val temporary = File.createTempFile("catalog-", ".epub", context.cacheDir)
        try {
            val connection = source.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Narratify/1.0 (book import)")
                require(connection.responseCode in 200..299) { "The source returned HTTP ${connection.responseCode}." }
                require(connection.url.protocol == "https") { "The download redirected to an insecure address." }
                val length = connection.contentLengthLong
                require(length < 0 || length <= MAX_EPUB_BYTES) { "EPUB files larger than 500 MB are not supported." }
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            require(temporary.length() <= MAX_EPUB_BYTES) { "EPUB files larger than 500 MB are not supported." }
            import(Uri.fromFile(temporary), suggestedName).getOrThrow()
        } finally {
            temporary.delete()
        }
    }

    fun document(bookId: String): PlainTextDocument {
        val item = requireNotNull(store.book(bookId)) { "Book is no longer in the library." }
        require(item.format != "EPUB" && item.format !in AUDIO_FORMATS) {
            "This publication uses a dedicated reader."
        }
        val file = File(item.storageUri)
        require(file.isFile) { "The managed book file is missing." }
        return readDocument(item.displayName, file, item.format == "Markdown")
    }

    fun position(bookId: String): StoredTextPosition? = store.position(bookId)

    fun savePosition(bookId: String, position: StoredTextPosition) {
        store.savePosition(bookId, position, System.currentTimeMillis())
    }

    fun openEpub(bookId: String): OpenedEpub {
        val item = requireNotNull(store.book(bookId)) { "Book is no longer in the library." }
        require(item.format == "EPUB") { "This publication is not an EPUB." }
        val file = File(item.storageUri)
        require(file.isFile) { "The managed EPUB file is missing." }
        return epubService.open(file, store.epubPosition(bookId))
    }

    fun saveEpubPosition(bookId: String, locator: Locator) {
        store.saveEpubPosition(
            publicationId = bookId,
            locatorJson = locator.toJSON().toString(),
            resourceId = locator.href.toString(),
            resourceProgression = locator.locations.progression,
            totalProgression = locator.locations.totalProgression,
            textExact = locator.text.highlight,
            textPrefix = locator.text.before,
            textSuffix = locator.text.after,
            now = System.currentTimeMillis(),
        )
    }

    fun audioPositionMs(bookId: String): Long = store.audioPositionMs(bookId)

    private fun inspectAudio(displayName: String, file: File): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: throw IllegalArgumentException("This audio file has no playable duration.")
            AudioMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf(String::isNotBlank)
                    ?: displayName.substringBeforeLast('.').ifBlank { "Untitled audiobook" },
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf(String::isNotBlank),
                durationMs = duration,
                cover = retriever.embeddedPicture?.let(::decodeEmbeddedCover),
            )
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("This audiobook file could not be read.")
        } finally {
            retriever.release()
        }
    }

    /** Backfills artwork for books imported before cover support was added. */
    private fun ensureCover(book: LocalBook): LocalBook {
        book.coverUri?.let { uri ->
            if (File(uri).isFile) return book
        }
        if (book.format != "EPUB" && book.format !in AUDIO_FORMATS) return book
        val marker = missingCoverMarker(book.id)
        if (marker.exists()) return book.copy(coverUri = null)
        val source = File(book.storageUri)
        if (!source.isFile) return book
        val bitmap = runCatching {
            if (book.format == "EPUB") epubService.inspect(source).cover
            else inspectAudio(book.displayName, source).cover
        }.getOrNull()
        return try {
            if (bitmap == null) {
                marker.createNewFile()
                book.copy(coverUri = null)
            } else {
                persistCover(book, bitmap) ?: book.copy(coverUri = null)
            }
        } finally {
            bitmap?.recycle()
        }
    }

    private fun persistCover(book: LocalBook, bitmap: Bitmap): LocalBook? {
        val destination = File(filesDirectory, "${book.id}.cover.jpg")
        val partial = File(filesDirectory, ".${book.id}.cover.part")
        return runCatching {
            partial.outputStream().buffered().use { output ->
                require(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    "The cover image could not be saved."
                }
            }
            if (destination.exists()) require(destination.delete()) { "The old cover could not be replaced." }
            require(partial.renameTo(destination)) { "The cover image could not be installed." }
            val hash = fingerprintOf(destination)
            require(store.saveCover(book.id, destination.absolutePath, hash, destination.length(), System.currentTimeMillis()))
            missingCoverMarker(book.id).delete()
            book.copy(coverUri = destination.absolutePath)
        }.getOrElse {
            partial.delete()
            null
        }
    }

    /** The same SHA-256 hex digest `import` uses, so a narration and an import can never disagree about a file's identity. */
    private fun fingerprintOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Copies an audio file into managed storage and records it as this book's narration.
     *
     * Chapters are read here rather than on demand because the file is already open and a reader
     * who has just chosen it is the only person who will be shown the mapping. A file whose
     * chapters cannot be read is still attached — it simply has none, which the design treats as
     * ordinary rather than as a failed import.
     */
    fun attachNarration(bookId: String, uri: Uri): Result<NarrationAttachment> = runCatching {
        val book = requireNotNull(store.book(bookId)) { "This book is no longer in the library." }
        val resolver = context.contentResolver
        val metadata = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                        name to size
                    }
                }
        }.getOrNull()
        val displayName = metadata?.first?.takeIf(String::isNotBlank) ?: "Narration"
        val mediaType = runCatching { resolver.getType(uri) }.getOrNull()?.substringBefore(';')?.trim()?.lowercase()
        val extension = supportedExtension(displayName, mediaType)
        require(extension in AUDIO_EXTENSIONS) {
            "A narration must be an MP3, M4A, or M4B file."
        }
        require((metadata?.second ?: 0L) <= MAX_AUDIO_BYTES) {
            "Audio files larger than 4 GB are not supported."
        }

        val destination = File(filesDirectory, "${book.id}.narration.$extension")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "That file could not be opened." }
            destination.outputStream().use(input::copyTo)
        }

        // Needed only to close the last chapter, which ends where the audio does. Not persisted:
        // see the note on StoredNarration in Task 4.
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(destination.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        } ?: 0L

        val chapters = Mp4MoovReader.read(destination)
            ?.let { Mp4ChapterReader.read(it, durationMs) }
            .orEmpty()

        store.attachNarration(
            publicationId = book.id,
            storageUri = destination.absolutePath,
            displayName = displayName,
            mediaType = mediaType,
            contentHash = fingerprintOf(destination),
            byteSize = destination.length(),
            now = System.currentTimeMillis(),
        )
        NarrationAttachment(bookId = book.id, displayName = displayName, chapters = chapters)
    }

    fun detachNarration(bookId: String): Result<Unit> = runCatching {
        store.narration(bookId)?.storageUri?.let { path ->
            val managed = File(path).canonicalFile
            require(managed.parentFile == filesDirectory.canonicalFile) {
                "Narratify can only remove its own managed files."
            }
            managed.delete()
        }
        store.detachNarration(bookId)
    }

    private fun missingCoverMarker(bookId: String) = File(filesDirectory, ".$bookId.cover-none")

    private fun decodeEmbeddedCover(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1_200 || bounds.outHeight / sample > 1_800) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
    }

    private fun readDocument(displayName: String, file: File, isMarkdown: Boolean): PlainTextDocument {
        require(file.length() <= MAX_TEXT_BYTES) { "Text files larger than 20 MB are not supported yet." }
        val bytes = file.readBytes()
        val source = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("This text file is not valid UTF-8. Convert it to UTF-8 and try again.")
        }
        require(source.isNotBlank()) { "The selected file is empty." }
        return PlainTextNormalizer.parse(
            displayName,
            source,
            isMarkdown,
        )
    }

    private fun supportedExtension(displayName: String, mediaType: String?): String {
        val named = displayName.substringAfterLast('.', "").lowercase()
        if (named.isNotBlank()) return named
        return when (mediaType) {
            "application/epub+zip" -> "epub"
            "text/plain" -> "txt"
            "text/markdown", "text/x-markdown" -> "md"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/x-m4b", "audio/audiobook" -> "m4b"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            else -> ""
        }
    }

    companion object {
        private const val MAX_TEXT_BYTES = 20L * 1024L * 1024L
        private const val MAX_EPUB_BYTES = 500L * 1024L * 1024L
        private const val MAX_AUDIO_BYTES = 4L * 1024L * 1024L * 1024L
        private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b")
        private val AUDIO_FORMATS = setOf("MP3", "M4A", "M4B")
        private val SUPPORTED_EXTENSIONS = TEXT_EXTENSIONS + AUDIO_EXTENSIONS + "epub"
    }
}

private data class AudioMetadata(
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val cover: Bitmap?,
)

/** What a freshly attached narration knows about itself before its mapping is reviewed. */
data class NarrationAttachment(
    val bookId: String,
    val displayName: String,
    val chapters: List<AudioChapter>,
)
