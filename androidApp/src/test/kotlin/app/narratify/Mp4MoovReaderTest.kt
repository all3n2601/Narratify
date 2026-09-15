package app.narratify

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp4MoovReaderTest {
    @get:Rule val folder = TemporaryFolder()

    private fun box(name: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        return byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
        ) + name.toByteArray() + payload
    }

    private fun write(bytes: ByteArray): File =
        folder.newFile().apply { writeBytes(bytes) }

    @Test
    fun `finds the moov box when it follows the media data`() {
        val moov = box("moov", byteArrayOf(1, 2, 3, 4))
        val file = write(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(64)) + moov)
        assertArrayEquals(moov, Mp4MoovReader.read(file))
    }

    @Test
    fun `finds the moov box when it precedes the media data`() {
        val moov = box("moov", byteArrayOf(9, 9))
        val file = write(box("ftyp", ByteArray(8)) + moov + box("mdat", ByteArray(64)))
        assertArrayEquals(moov, Mp4MoovReader.read(file))
    }

    @Test
    fun `a file with no moov box reads as nothing`() {
        assertNull(Mp4MoovReader.read(write(box("ftyp", ByteArray(8)))))
    }

    @Test
    fun `a file that is not an mp4 at all reads as nothing`() {
        assertNull(Mp4MoovReader.read(write("ID3 this is an mp3".toByteArray())))
    }

    @Test
    fun `a truncated box header reads as nothing rather than throwing`() {
        assertNull(Mp4MoovReader.read(write(byteArrayOf(0, 0, 1))))
    }

    @Test
    fun `a box claiming to be larger than the file reads as nothing`() {
        val lying = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F) + "moov".toByteArray()
        assertNull(Mp4MoovReader.read(write(lying)))
    }

    @Test
    fun `an empty file reads as nothing`() {
        assertNull(Mp4MoovReader.read(write(ByteArray(0))))
    }
}
