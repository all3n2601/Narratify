package app.narratify

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

class ReaderNarrationTest {
    private class FakeController : ReaderTtsController {
        var released = false
        var state = ReaderTtsController.State.READY
        var playedFrom: Int? = null

        override fun play(fromOffset: Int) {
            playedFrom = fromOffset
            state = ReaderTtsController.State.PLAYING
        }

        override fun pause() {
            state = ReaderTtsController.State.PAUSED
        }

        override fun stop() {
            state = ReaderTtsController.State.STOPPED
        }

        override fun setSpeed(value: Float) = Unit
        override fun speed(): Float = 1f
        override fun state(): ReaderTtsController.State = state
        override fun release() {
            released = true
        }
    }

    private class RecordingListener : ReaderTtsController.Listener {
        val states = mutableListOf<Pair<ReaderTtsController.State, String>>()
        val ranges = mutableListOf<Pair<Int, Int>>()

        override fun onState(state: ReaderTtsController.State, message: String) {
            states += state to message
        }

        override fun onSourceRange(start: Int, endExclusive: Int) {
            ranges += start to endExclusive
        }
    }

    @After
    fun cleanUp() = ReaderNarration.release()

    @Test
    fun `re-attaching the same book keeps the running narration`() {
        val first = FakeController()
        var created = 0
        val screen = RecordingListener()
        val active = ReaderNarration.attach("book-1", screen) { created++; first }

        ReaderNarration.detach(screen)
        val secondScreen = RecordingListener()
        val reattached = ReaderNarration.attach("book-1", secondScreen) { created++; FakeController() }

        assertSame(active, reattached)
        assertEquals(1, created)
        assertFalse(first.released)
    }

    @Test
    fun `opening a different book releases the previous narration`() {
        val first = FakeController()
        ReaderNarration.attach("book-1", RecordingListener()) { first }

        val second = FakeController()
        val active = ReaderNarration.attach("book-2", RecordingListener()) { second }

        assertTrue(first.released)
        assertSame(second, active)
        assertTrue(ReaderNarration.isActive("book-2"))
        assertFalse(ReaderNarration.isActive("book-1"))
    }

    @Test
    fun `a returning screen is replayed the current state and highlight`() {
        val screen = RecordingListener()
        val controller = FakeController()
        ReaderNarration.attach("book-1", screen) { controller }
        val relay = ReaderNarration.relayForTest()

        relay.onState(ReaderTtsController.State.PLAYING, "Reading aloud")
        relay.onSourceRange(10, 20)
        ReaderNarration.detach(screen)

        val returning = RecordingListener()
        ReaderNarration.attach("book-1", returning) { controller }

        assertEquals(ReaderTtsController.State.PLAYING to "Reading aloud", returning.states.single())
        assertEquals(10 to 20, returning.ranges.single())
    }

    @Test
    fun `a detached screen stops receiving updates`() {
        val screen = RecordingListener()
        ReaderNarration.attach("book-1", screen) { FakeController() }
        val relay = ReaderNarration.relayForTest()
        ReaderNarration.detach(screen)

        relay.onState(ReaderTtsController.State.PLAYING, "Reading aloud")
        relay.onSourceRange(1, 2)

        assertTrue(screen.states.isEmpty())
        assertTrue(screen.ranges.isEmpty())
    }

    @Test
    fun `releasing ends the session so nothing keeps speaking`() {
        val controller = FakeController()
        ReaderNarration.attach("book-1", RecordingListener()) { controller }

        ReaderNarration.release()

        assertTrue(controller.released)
        assertFalse(ReaderNarration.isActive("book-1"))
    }
}
