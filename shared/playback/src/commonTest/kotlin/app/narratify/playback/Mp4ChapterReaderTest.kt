package app.narratify.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mp4ChapterReaderTest {
    /** A box is a big-endian length, a four-character name, then its payload. */
    private fun box(name: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        return byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
        ) + name.encodeToByteArray() + payload
    }

    /** Nero stores each chapter as a 100-nanosecond timestamp, a title length, then the title. */
    private fun chpl(chapters: List<Pair<Long, String>>): ByteArray {
        var payload = byteArrayOf(1, 0, 0, 0) + byteArrayOf(0, 0, 0, 0) + chapters.size.toByte()
        for ((startMs, title) in chapters) {
            val ticks = startMs * 10_000L
            val stamp = ByteArray(8) { index -> (ticks ushr (56 - index * 8)).toByte() }
            val bytes = title.encodeToByteArray()
            payload = payload + stamp + bytes.size.toByte() + bytes
        }
        return box("chpl", payload)
    }

    private fun file(chapters: List<Pair<Long, String>>, durationMs: Long = 20_000L): ByteArray =
        box("ftyp", ByteArray(8)) + box("moov", box("udta", chpl(chapters)))

    @Test
    fun `chapters are read in order with their titles`() {
        val chapters = Mp4ChapterReader.read(
            file(listOf(0L to "Opening", 5_000L to "The harbour", 12_500L to "The breakwater")),
            durationMs = 20_000L,
        )
        assertEquals(listOf("Opening", "The harbour", "The breakwater"), chapters.map { it.title })
        assertEquals(listOf(0L, 5_000L, 12_500L), chapters.map { it.startMs })
    }

    @Test
    fun `each chapter ends where the next begins`() {
        val chapters = Mp4ChapterReader.read(file(listOf(0L to "One", 5_000L to "Two")), durationMs = 20_000L)
        assertEquals(5_000L, chapters[0].endMs)
    }

    @Test
    fun `the last chapter ends at the end of the file`() {
        val chapters = Mp4ChapterReader.read(file(listOf(0L to "One", 5_000L to "Two")), durationMs = 20_000L)
        assertEquals(20_000L, chapters.last().endMs)
    }

    @Test
    fun `indices are contiguous from zero`() {
        val chapters = Mp4ChapterReader.read(file(listOf(0L to "One", 5_000L to "Two")), durationMs = 20_000L)
        assertEquals(listOf(0, 1), chapters.map { it.index })
    }

    @Test
    fun `a file with no chapter atom has no chapters`() {
        assertEquals(emptyList(), Mp4ChapterReader.read(box("ftyp", ByteArray(8)), durationMs = 20_000L))
    }

    @Test
    fun `an empty input has no chapters`() {
        assertEquals(emptyList(), Mp4ChapterReader.read(ByteArray(0), durationMs = 20_000L))
    }

    @Test
    fun `a truncated atom is treated as having no chapters rather than throwing`() {
        val whole = file(listOf(0L to "One", 5_000L to "Two"))
        for (cut in listOf(whole.size / 2, whole.size - 3, 9)) {
            assertEquals(
                emptyList(),
                Mp4ChapterReader.read(whole.copyOf(cut), durationMs = 20_000L),
                "a file cut to $cut bytes should yield no chapters",
            )
        }
    }

    @Test
    fun `a chapter starting after the file ends is dropped`() {
        val chapters = Mp4ChapterReader.read(
            file(listOf(0L to "One", 99_000L to "Impossible")),
            durationMs = 20_000L,
        )
        assertEquals(listOf("One"), chapters.map { it.title })
    }

    @Test
    fun `an untitled chapter keeps its place`() {
        val chapters = Mp4ChapterReader.read(file(listOf(0L to "", 5_000L to "Two")), durationMs = 20_000L)
        assertEquals(2, chapters.size)
        assertTrue(chapters[0].title.isEmpty())
    }
}
