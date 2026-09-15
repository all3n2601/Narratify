package app.narratify.playback

/** One chapter mark, with the audio it covers. */
data class AudioChapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(index >= 0) { "Chapter index must be non-negative" }
        require(startMs >= 0) { "Chapter start must be non-negative" }
        require(endMs >= startMs) { "A chapter must not end before it starts" }
    }
}

/**
 * Reads chapter marks out of an MP4 container.
 *
 * Neither `MediaMetadataRetriever` nor Media3 exposes them, so the bytes are walked directly. Only
 * the Nero-style `moov/udta/chpl` atom is understood; a QuickTime chapter *track*, which is a
 * `tref/chap` reference into a text track's sample tables, is a much larger job and is not read
 * here.
 *
 * Every failure is the same failure: no chapters. A truncated file, an atom this reader does not
 * understand, and a file that genuinely has none all return an empty list, because the product
 * treats a narration without chapters as ordinary rather than broken. Throwing would turn a
 * playable file into an import error for no gain.
 */
object Mp4ChapterReader {
    private const val HEADER_BYTES = 8
    private const val NERO_TICKS_PER_MILLISECOND = 10_000L

    fun read(bytes: ByteArray, durationMs: Long): List<AudioChapter> {
        val payload = findBox(bytes, 0, bytes.size, listOf("moov", "udta", "chpl")) ?: return emptyList()
        return runCatching { parseNero(bytes, payload.first, payload.second, durationMs) }
            .getOrElse { emptyList() }
    }

    /** Returns the payload range of the last name in [path], or null if the path is not present. */
    private fun findBox(bytes: ByteArray, from: Int, to: Int, path: List<String>): Pair<Int, Int>? {
        if (path.isEmpty()) return from to to
        var cursor = from
        while (cursor + HEADER_BYTES <= to) {
            val size = readInt(bytes, cursor)
            if (size < HEADER_BYTES || cursor + size > to) return null
            val name = bytes.decodeToString(cursor + 4, cursor + HEADER_BYTES)
            if (name == path.first()) {
                return findBox(bytes, cursor + HEADER_BYTES, cursor + size, path.drop(1))
            }
            cursor += size
        }
        return null
    }

    private fun parseNero(bytes: ByteArray, from: Int, to: Int, durationMs: Long): List<AudioChapter> {
        // One byte of version, three of flags, four reserved, then the chapter count.
        var cursor = from + 8
        if (cursor >= to) return emptyList()
        val count = bytes[cursor].toInt() and 0xFF
        cursor += 1

        val starts = ArrayList<Pair<Long, String>>(count)
        repeat(count) {
            if (cursor + 9 > to) return emptyList()
            val ticks = readLong(bytes, cursor)
            cursor += 8
            val titleLength = bytes[cursor].toInt() and 0xFF
            cursor += 1
            if (cursor + titleLength > to) return emptyList()
            starts.add((ticks / NERO_TICKS_PER_MILLISECOND) to bytes.decodeToString(cursor, cursor + titleLength))
            cursor += titleLength
        }

        val withinFile = starts.filter { it.first <= durationMs }.sortedBy { it.first }
        return withinFile.mapIndexed { index, (startMs, title) ->
            AudioChapter(
                index = index,
                title = title,
                startMs = startMs,
                endMs = withinFile.getOrNull(index + 1)?.first ?: durationMs,
            )
        }
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun readLong(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (offset in 0 until 8) value = (value shl 8) or (bytes[at + offset].toLong() and 0xFF)
        return value
    }
}
