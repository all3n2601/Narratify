package app.narratify

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MiniPlayerTest {
    private fun narration(state: ReaderTtsController.State, message: String = "Reading aloud at 1.00×") =
        ReaderNarration.Session("book-1", "The Lamplighter", state, message)

    private val audiobook = MiniPlayer.AudiobookSnapshot(
        publicationId = "audio-1",
        title = "Sea Songs",
        playing = true,
        positionMs = 62_000,
        durationMs = 300_000,
    )

    @Test
    fun `nothing playing means no bar`() {
        assertNull(MiniPlayer.resolve(null, null, null))
    }

    @Test
    fun `a playing narration is shown with its status message`() {
        val state = MiniPlayer.resolve(narration(ReaderTtsController.State.PLAYING), null, null)!!

        assertEquals(MiniPlayer.Source.NARRATION, state.source)
        assertEquals("book-1", state.id)
        assertEquals("The Lamplighter", state.title)
        assertEquals("Reading aloud at 1.00×", state.subtitle)
        assertTrue(state.playing)
    }

    @Test
    fun `a paused narration stays on the bar so it can be resumed`() {
        val state = MiniPlayer.resolve(narration(ReaderTtsController.State.PAUSED, "Paused"), null, null)!!

        assertEquals(MiniPlayer.Source.NARRATION, state.source)
        assertTrue(!state.playing)
    }

    @Test
    fun `narration that is finished, idle, or broken does not hold the bar`() {
        listOf(
            ReaderTtsController.State.STOPPED,
            ReaderTtsController.State.INITIALIZING,
            ReaderTtsController.State.READY,
            ReaderTtsController.State.UNAVAILABLE,
            ReaderTtsController.State.ERROR,
        ).forEach { state ->
            assertNull(MiniPlayer.resolve(narration(state), null, null), "$state should not show the bar")
        }
    }

    @Test
    fun `an audiobook fills the bar when no narration is running`() {
        val state = MiniPlayer.resolve(null, null, audiobook)!!

        assertEquals(MiniPlayer.Source.AUDIOBOOK, state.source)
        assertEquals("Sea Songs", state.title)
        assertEquals("1:02 / 5:00", state.subtitle)
        assertEquals(62_000f / 300_000f, state.progress!!, 1e-6f)
    }

    @Test
    fun `a paused audiobook at the very start is not worth a bar`() {
        val idle = audiobook.copy(playing = false, positionMs = 0)

        assertNull(MiniPlayer.resolve(null, null, idle))
    }

    @Test
    fun `narration wins when both are somehow active`() {
        val state = MiniPlayer.resolve(narration(ReaderTtsController.State.PLAYING), null, audiobook)!!

        assertEquals(MiniPlayer.Source.NARRATION, state.source)
    }

    @Test
    fun `hours are shown only when the book is that long`() {
        val short = MiniPlayer.resolve(null, null, audiobook.copy(positionMs = 9_000, durationMs = 65_000))!!
        val long = MiniPlayer.resolve(null, null, audiobook.copy(positionMs = 3_725_000, durationMs = 7_200_000))!!

        assertEquals("0:09 / 1:05", short.subtitle)
        assertEquals("1:02:05 / 2:00:00", long.subtitle)
    }

    @Test
    fun `an EPUB narration takes the bar when no text narration is running`() {
        val state = MiniPlayer.resolve(
            narration = null,
            epub = EpubNarration.Session("epub-1", "Slow Down", playing = true, message = "Neural voice"),
            audiobook = audiobook,
        )!!

        assertEquals(MiniPlayer.Source.EPUB, state.source)
        assertEquals("Slow Down", state.title)
        assertEquals("Neural voice", state.subtitle)
        assertTrue(state.playing)
    }

    @Test
    fun `a paused EPUB narration stays on the bar`() {
        val state = MiniPlayer.resolve(
            narration = null,
            epub = EpubNarration.Session("epub-1", "Slow Down", playing = false, message = "Paused"),
            audiobook = null,
        )!!

        assertTrue(!state.playing)
        assertEquals("Paused", state.subtitle)
    }

    @Test
    fun `text narration still outranks an EPUB session`() {
        val state = MiniPlayer.resolve(
            narration = narration(ReaderTtsController.State.PLAYING),
            epub = EpubNarration.Session("epub-1", "Slow Down", playing = true, message = "Neural voice"),
            audiobook = null,
        )!!

        assertEquals(MiniPlayer.Source.NARRATION, state.source)
    }

    @Test
    fun `an audiobook without a known duration still shows its position`() {
        val state = MiniPlayer.resolve(null, null, audiobook.copy(durationMs = 0))!!

        assertEquals("1:02", state.subtitle)
        assertNull(state.progress)
    }
}
