package app.narratify

import com.narratify.domain.PcmEncoding
import com.narratify.domain.PcmFormat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playback must never wait for the synthesizer between sentences, and the audio waiting to be
 * played must stay inside a declared bound rather than growing with the length of the book.
 */
class SpeechRenderQueueTest {
    private val format = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE)

    @Test
    fun rendersAheadOfTheConsumerButNeverDeeperThanItsBound() {
        val session = FakeSession(format)
        val queue = SpeechRenderQueue(session, depth = 2)

        queue.start(passages(5), rate = 1f)

        assertTrue(session.awaitCalls(3), "expected the queue to render its bound plus the one in flight")
        assertFalse(session.awaitCalls(4), "the queue rendered further ahead than its bound allows")
        assertEquals(3, session.requested.size)
        queue.close()
    }

    @Test
    fun deliversEveryPassageInOrderAndThenReportsTheEnd() {
        val queue = SpeechRenderQueue(FakeSession(format), depth = 2)
        queue.start(passages(4), rate = 1f)

        val spoken = (1..4).map { queue.take()?.passage?.text }

        assertEquals(listOf("passage 0", "passage 1", "passage 2", "passage 3"), spoken)
        assertNull(queue.take())
        queue.close()
    }

    @Test
    fun cancellingStopsRenderingAndUnblocksTheConsumer() {
        val session = FakeSession(format)
        val queue = SpeechRenderQueue(session, depth = 2)
        queue.start(passages(50), rate = 1f)
        assertTrue(session.awaitCalls(3))

        queue.cancel()

        assertNull(queue.take())
        val rendered = session.requested.size
        assertFalse(session.awaitCalls(rendered + 1), "rendering continued after cancellation")
        queue.close()
    }

    @Test
    fun runtimeFailureReachesTheConsumerInsteadOfStallingIt() {
        val session = FakeSession(format, failOn = "passage 1")
        val queue = SpeechRenderQueue(session, depth = 2)
        queue.start(passages(3), rate = 1f)

        assertEquals("passage 0", queue.take()?.passage?.text)
        val error = assertFailsWith<SpeechRenderFailure> { queue.take() }

        assertTrue(error.message!!.contains("no audio"), error.message!!)
        queue.close()
    }

    private fun passages(count: Int) = (0 until count).map { index ->
        val text = "passage $index"
        PlannedPassage(
            text = text,
            tokens = listOf(SpokenSourceRange(0, text.length, index * 100, index * 100 + text.length)),
            sourceStart = index * 100,
            sourceEnd = index * 100 + text.length,
        )
    }

    private class FakeSession(
        private val format: PcmFormat,
        private val failOn: String? = null,
    ) : NeuralTtsSession {
        val requested = ConcurrentLinkedQueue<String>()
        private val counted = java.util.concurrent.atomic.AtomicInteger()
        private val progress = java.util.concurrent.Semaphore(0)

        override fun synthesize(
            request: NeuralSynthesisRequest,
            callback: NeuralSynthesisCallback,
        ): NeuralSynthesisJob {
            requested += request.speechText
            counted.incrementAndGet()
            progress.release()
            if (request.speechText == failOn) {
                callback.onError("The voice produced no audio for this passage")
            } else {
                callback.onMetadata(format, emptyList())
                callback.onPcm(ByteArray(64))
                callback.onComplete()
            }
            return NeuralSynthesisJob {}
        }

        override fun close() = Unit

        /** True when at least [count] passages have been handed to the runtime. */
        fun awaitCalls(count: Int): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
            while (System.nanoTime() < deadline) {
                if (counted.get() >= count) return true
                progress.tryAcquire(25, TimeUnit.MILLISECONDS)
            }
            return counted.get() >= count
        }
    }

    @Test
    fun timesEachRenderSoDiagnosticsMeasureSynthesisNotPlayback() {
        val timed = ConcurrentLinkedQueue<String>()
        val queue = SpeechRenderQueue(
            FakeSession(format),
            depth = 2,
            recorderFor = { passage ->
                timed += passage.text
                TtsDiagnostics.recordUtterance(passage.text.length, passage.tokens.size)
            },
        )

        queue.start(passages(2), rate = 1f)
        queue.take()
        queue.take()

        assertEquals(listOf("passage 0", "passage 1"), timed.toList())
        queue.close()
    }

    @Test
    fun silenceIsFrameAlignedAndLastsTheRequestedTime() {
        val bytes = silencePcm(format, millis = 400)

        assertEquals(24_000 * 400 / 1_000 * 2, bytes.size)
        assertTrue(bytes.all { it == 0.toByte() })
    }
}
