package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioMediaTypeTest {
    @Test
    fun `mp3 and mp4 audio are core media types`() {
        assertEquals("audio/mpeg", AudioMediaType.forHref("audio/book.mp3"))
        assertEquals("audio/mp4", AudioMediaType.forHref("audio/book.m4a"))
        assertEquals("audio/mp4", AudioMediaType.forHref("audio/book.mp4"))
    }

    @Test
    fun `the extension is matched without regard to case`() {
        assertEquals("audio/mpeg", AudioMediaType.forHref("AUDIO/BOOK.MP3"))
    }

    @Test
    fun `an m4b is refused even though its contents would be acceptable`() {
        assertNull(AudioMediaType.forHref("audio/book.m4b"))
    }

    @Test
    fun `an unknown extension is refused`() {
        assertNull(AudioMediaType.forHref("audio/book.flac"))
        assertNull(AudioMediaType.forHref("audio/book"))
    }

    @Test
    fun `the refusal explains what to do about an m4b`() {
        val explanation = AudioMediaType.explainRefusal("audio/book.m4b")
        assertEquals(true, explanation.contains("m4a"))
    }
}
