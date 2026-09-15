package app.narratify

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads just the `moov` box out of an MP4 file.
 *
 * An audiobook is routinely a few gigabytes and the chapter marks live in one box that is usually
 * a few kilobytes, so the file is walked box by box and only that one is read into memory. Where
 * the box sits varies: a file written for streaming puts it before the media data and a file
 * written in one pass puts it after, so both orders are handled.
 *
 * Anything unexpected reads as null, which the caller treats the same as a file with no chapters.
 */
object Mp4MoovReader {
    private const val HEADER_BYTES = 8L
    /** Well beyond any real chapter table, and small enough that a corrupt length cannot exhaust memory. */
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024

    fun read(file: File): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { handle ->
            val length = handle.length()
            var cursor = 0L
            while (cursor + HEADER_BYTES <= length) {
                handle.seek(cursor)
                val size = handle.readInt().toLong() and 0xFFFFFFFFL
                val name = ByteArray(4).also(handle::readFully).decodeToString()
                if (size < HEADER_BYTES || cursor + size > length) return null
                if (name == "moov") {
                    if (size > MAX_MOOV_BYTES) return null
                    handle.seek(cursor)
                    return ByteArray(size.toInt()).also(handle::readFully)
                }
                cursor += size
            }
            null
        }
    }.getOrNull()
}
